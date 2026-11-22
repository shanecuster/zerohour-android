package com.shanetalkstech.zerohour.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.pastThreshold
import com.shanetalkstech.zerohour.data.pulledDistance
import uniffi.zerohour_ffi.ColorSlot

// "Pull down on a list to sync", in the terminal look: no round spinner, but
// one line of text that appears at the top of the box as the list is pulled.
// Built from Compose foundation and ui only (no Material library).

/** How far, in text rows, a pull must go before letting go syncs. */
private const val PULL_THRESHOLD_ROWS = 3

/** How far, as a multiple of the threshold, the list can be pulled at most. */
private const val PULL_MAX_THRESHOLDS = 2

/**
 * What a list needs to offer the pull, bundled so the Tasks and Events boxes
 * (and the screen that passes it to them) take one parameter instead of
 * three. The fields are those of [PullToSync] with the same names.
 */
class ListPull(val syncIcon: String, val syncing: Boolean, val onRelease: () -> Unit)

/**
 * Wraps a scrollable list ([content]) so that pulling it down while it is at
 * the top, past [PULL_THRESHOLD_ROWS] rows, and letting go calls [onRelease].
 * Letting go earlier only springs back.
 *
 * While pulling, a line appears above the list, in the style of the gesture
 * hint: "↓ pull to sync" (muted), then "↑ release to sync" (accent) once
 * past the threshold, when the phone also vibrates once. While [syncing] is
 * true the line stays one row high and reads "⇅ syncing…" ([syncIcon] is
 * core's sync icon in the chosen icon mode); the caller sets it only for a
 * sync the pull started or joined, so a tap on the header or the launch sync
 * does not move the list.
 *
 * How the pull is noticed: [content] must be scrollable (a `LazyColumn`, or a
 * `verticalScroll` column for an empty list). Every scroll it makes is first
 * offered to its parents through Compose's *nested scroll* system, and what it
 * cannot use (the downward drag when it is already at the top) is offered
 * again afterwards. This wrapper is such a parent ([NestedScrollConnection]):
 * it takes that leftover drag as the pull. A horizontal swipe on a line (done
 * or delete) is a different gesture that Compose tells apart by the drag's
 * first direction, so it never reaches this wrapper.
 */
@Composable
fun PullToSync(
    look: TuiLook,
    syncIcon: String,
    syncing: Boolean,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // The sizes in pixels, which is what the scroll system works in.
    // `with(density)` makes `toPx()` (Dp to pixels) available.
    val rowPx = with(density) { look.cell.rows(1).toPx() }
    val threshold = rowPx * PULL_THRESHOLD_ROWS
    val maxPull = threshold * PULL_MAX_THRESHOLDS

    // How far the list is pulled right now, in pixels (0 when not pulled).
    var pulled by remember { mutableFloatStateOf(0f) }
    // True once this pull has vibrated, so a finger moving back and forth
    // across the threshold vibrates only once per pull.
    var buzzed by remember { mutableStateOf(false) }
    // The connection below is remembered across redraws, so it reads the
    // newest callback through this holder instead of the first one it saw.
    val currentOnRelease by rememberUpdatedState(onRelease)

    // The nested-scroll connection: the three moments of a drag it hears about.
    // `object : NestedScrollConnection { ... }` makes a one-off object that
    // implements the interface, overriding only the parts needed here.
    val connection = remember(threshold, maxPull) {
        object : NestedScrollConnection {
            /**
             * Before the list scrolls: if the list is pulled and the finger
             * moves back up, shrink the pull first (and keep that movement
             * from the list), so the list does not scroll until the pull is
             * gone. Only for a finger's drag (`UserInput`), not a fling.
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || pulled <= 0f || available.y >= 0f) {
                    return Offset.Zero
                }
                val before = pulled
                pulled = pulledDistance(pulled, available.y, maxPull)
                // The part of the movement used up (negative, like the movement).
                return Offset(0f, pulled - before)
            }

            /**
             * After the list scrolled: a downward drag it could not use means
             * it is at the top, so that drag becomes the pull. Crossing the
             * threshold vibrates once per pull.
             */
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulled = pulledDistance(pulled, available.y, maxPull)
                if (!buzzed && pastThreshold(pulled, threshold)) {
                    buzzed = true
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
                return Offset(0f, available.y)
            }

            /**
             * The finger was lifted (a fling, possibly at zero speed, always
             * follows a drag). If the list is pulled: past the threshold it
             * syncs; either way the pull springs back. The fling's speed is
             * used up so the list does not also fly off. `suspend` means this
             * function can wait (here, for the spring-back animation) without
             * blocking the screen.
             */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled <= 0f) return Velocity.Zero
                if (pastThreshold(pulled, threshold)) currentOnRelease()
                buzzed = false
                // `animate` runs from the current pull down to 0 on the
                // default spring, calling the block with each new value.
                animate(initialValue = pulled, targetValue = 0f) { value, _ -> pulled = value }
                return available
            }
        }
    }

    // The line's height: the pull itself, but at least one row while syncing.
    val shownPx = maxOf(pulled, if (syncing) rowPx else 0f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(connection),
    ) {
        if (shownPx > 0f) {
            // The words and colour for the moment: syncing wins, then
            // whether letting go now would sync. A `Pair` (`a to b`) carries
            // the two values out of the `when`.
            val (text, slot) = when {
                syncing -> "$syncIcon ${stringResource(R.string.sync_state_running)}" to ColorSlot.ACCENT
                pastThreshold(pulled, threshold) -> stringResource(R.string.pull_release) to ColorSlot.ACCENT
                else -> stringResource(R.string.pull_hint) to ColorSlot.MUTED
            }
            // The line sits at the bottom of the space the pull opens, so it
            // comes down with the list; `clipToBounds` cuts it while the
            // space is still less than a row high.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { shownPx.toDp() })
                    .clipToBounds(),
                contentAlignment = Alignment.BottomStart,
            ) {
                BasicText(
                    text = text,
                    style = look.text.copy(color = look.color(slot), fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // The list takes the rest of the box.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}
