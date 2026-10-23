package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.SyncState
import kotlin.math.abs
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.SyncOutcome
import uniffi.zerohour_ffi.SyncStatus

// The words and colours for sync, shared by the header and the Sync page.
// They follow the terminal app's header indicator and sync popup
// (tui/src/ui/mod.rs and tui/src/ui/sync_settings.rs in the zerohour repo).

/** One line of text about sync and its colour slot. */
data class SyncLine(val text: String, val slot: ColorSlot)

/**
 * The header's short indicator, or null when there is none (sync switched
 * off, or the demo database, as in the terminal app):
 *
 * - syncing: "⇅ …" in the accent colour;
 * - offline: "○ offline" in the warn colour (a warning, not an error);
 * - failed: "✗ error" in the danger colour;
 * - otherwise the last sync on this phone: "⇅ 14:30" (or "⇅ Oct 21" before
 *   today) in the "ok" colour, or "⇅ never" muted. After a sync that found
 *   the clocks more than a minute apart, it is in the warn colour.
 *
 * The icons are core's, in the chosen icon mode (⇅ ○ ✗ in Unicode mode).
 */
@Composable
fun syncIndicator(status: SyncStatus, state: SyncState): SyncLine? {
    if (!status.enabled) return null
    val icons = status.icons
    return when (state) {
        SyncState.Running -> SyncLine("${icons.sync} …", ColorSlot.ACCENT)
        is SyncState.Offline -> SyncLine("${icons.offline} ${stringResource(R.string.sync_offline_short)}", ColorSlot.WARN)
        is SyncState.Failed -> SyncLine("${icons.error} ${stringResource(R.string.sync_error_short)}", ColorSlot.DANGER)
        SyncState.Idle, is SyncState.Done -> {
            val last = status.lastSyncedShort
            if (last == null) {
                SyncLine("${icons.sync} ${stringResource(R.string.sync_never_short)}", ColorSlot.MUTED)
            } else {
                val skewed = state is SyncState.Done && state.outcome.clockSkewSecs != null
                SyncLine("${icons.sync} $last", if (skewed) ColorSlot.WARN else ColorSlot.OK)
            }
        }
    }
}

/**
 * The Sync page's status line, in the terminal app's words: "Status: last
 * synced Oct 22 2:30 PM · 12 documents synced" (or never synced, syncing…,
 * offline (server not reachable), error: …).
 */
@Composable
fun syncStatusLine(status: SyncStatus, state: SyncState): String {
    val what = when (state) {
        SyncState.Running -> stringResource(R.string.sync_state_running)
        is SyncState.Offline -> stringResource(R.string.sync_state_offline)
        is SyncState.Failed -> stringResource(R.string.sync_state_error, state.detail)
        SyncState.Idle, is SyncState.Done -> status.lastSyncedLong?.let {
            stringResource(R.string.sync_state_synced, it)
        } ?: stringResource(R.string.sync_state_never)
    }
    return stringResource(R.string.sync_status_line, what, status.syncedDocs.toString())
}

/**
 * The messages after a sync finished, most important first: items that need
 * a newer zerohour (danger), the clock warning (warn), then "Synced: 3 in,
 * 1 out." (ok). After a failed or offline sync, its reason. Empty while
 * nothing has finished. The main screen shows the first one in its bottom
 * row; the Sync page shows them all.
 */
@Composable
fun syncResultLines(state: SyncState): List<SyncLine> {
    val context = LocalContext.current
    return when (state) {
        SyncState.Idle, SyncState.Running -> emptyList()
        // Core's words already start with "offline:".
        is SyncState.Offline -> listOf(SyncLine(state.detail, ColorSlot.WARN))
        is SyncState.Failed -> listOf(SyncLine(stringResource(R.string.sync_result_error, state.detail), ColorSlot.DANGER))
        is SyncState.Done -> buildList {
            val outcome: SyncOutcome = state.outcome
            if (outcome.skippedNewer > 0uL) {
                val n = outcome.skippedNewer.toInt()
                add(SyncLine(pluralStringResource(R.plurals.sync_needs_newer, n, n), ColorSlot.DANGER))
            }
            outcome.clockSkewSecs?.let { seconds ->
                // Minutes, rounded up, as in the terminal app.
                val minutes = ((abs(seconds) + 59) / 60).toString()
                val id = if (seconds > 0) R.string.sync_clock_ahead else R.string.sync_clock_behind
                add(SyncLine(context.getString(id, minutes), ColorSlot.WARN))
            }
            add(
                SyncLine(
                    stringResource(R.string.sync_result_done, outcome.pulled.toString(), outcome.pushed.toString()),
                    ColorSlot.OK,
                ),
            )
        }
    }
}

/**
 * The one-time question before the first sync on a phone that already has
 * [items] tasks and events, in the delete box's style but in the warn colour:
 *
 * ```
 * ╭ First sync ────────────────────╮
 * │ This phone has 5 items. The    │
 * │ first sync adds them to the    │
 * │ server's; anything typed on    │
 * │ both devices will appear twice.│
 * │                                │
 * │ [ continue ]   [ cancel ]      │
 * ╰────────────────────────────────╯
 * ```
 *
 * [columns] is the screen's width in cells (the box never gets wider).
 */
@Composable
fun FirstSyncBox(look: TuiLook, columns: Int, items: Int, onContinue: () -> Unit, onCancel: () -> Unit) {
    val warn = look.color(ColorSlot.WARN)
    TuiBox(
        look = look,
        borderColor = warn,
        // The box paints its own background, because it sits on top of the list.
        modifier = Modifier
            .width(look.cell.columns(minOf(CONFIRM_BOX_CELLS, columns)))
            .background(look.color(ColorSlot.BACKGROUND)),
        title = AnnotatedString(
            stringResource(R.string.first_sync_title),
            SpanStyle(color = warn, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column {
            BasicText(
                text = pluralStringResource(R.plurals.first_sync_question, items, items),
                style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
            )
            Spacer(Modifier.height(look.cell.rows(1)))
            Row {
                TapText(look, stringResource(R.string.first_sync_continue), warn, onClick = onContinue, bold = true)
                Spacer(Modifier.width(look.cell.columns(3)))
                TapText(
                    look,
                    stringResource(R.string.cancel_button),
                    look.color(ColorSlot.ACCENT),
                    onClick = onCancel,
                    bold = true,
                )
            }
        }
    }
}
