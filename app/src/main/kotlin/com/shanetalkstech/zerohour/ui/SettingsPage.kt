package com.shanetalkstech.zerohour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.shanetalkstech.zerohour.BuildConfig
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.Database
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.IconMode
import uniffi.zerohour_ffi.SettingsScreen
import uniffi.zerohour_ffi.ThemeInfo
import uniffi.zerohour_ffi.TimeFormat
import uniffi.zerohour_ffi.WeekStart
import uniffi.zerohour_ffi.listThemes

/** The labels in front of the choices are padded to this many cells ("Week starts"). */
private const val LABEL_CELLS = 11

/** Theme names are padded to this many cells before the swatches, as in the terminal app. */
private const val THEME_NAME_CELLS = 14

/**
 * What the settings screen has read from the database: the current
 * [settings] and whether the demo database is in use ([demo]).
 */
private data class SettingsState(val settings: SettingsScreen, val demo: Boolean)

/**
 * The settings screen, opened with the gear in the header. It fills the
 * screen like the add/edit form:
 *
 * ```
 * ╭ ⚙ Settings ──────────────────────────╮
 * │ Theme                                │
 * │ › Everforest     ██████████          │
 * │   Gruvbox        ██████████          │
 * │   ...                                │
 * │                                      │
 * │   Icons       ‹ Nerd ›               │
 * │   Time        ‹ 12-hour ›            │
 * │   Week starts ‹ Sunday ›             │
 * │   Banner      ‹ on ›                 │
 * │   Data        ‹ demo ›   (debug only)│
 * │                                      │
 * │   Sync ›                             │
 * │                                      │
 * │ [ close ]                            │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * Every change is saved at once, like the terminal app's keys. Tapping a
 * theme applies it straight away: [onChanged] makes the app read the
 * settings again, so the whole screen redraws in the new colours (that is
 * the "live preview"). The swatches show each theme's OWN colours, as in the
 * terminal app's picker, so themes can be compared before choosing.
 *
 * The "Data" row exists only in debug builds: it switches between the demo
 * database and a real one. While the demo is in use the "Sync" row is greyed
 * out, because the demo never syncs. [onOpenSync] opens the sync screen,
 * [onClose] closes this one (the back gesture does too).
 */
@Composable
fun SettingsPage(
    look: TuiLook,
    settingsIcon: String,
    onChanged: () -> Unit,
    onOpenSync: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Core's themes never change while the app runs, so they are read once.
    val themes = remember { listThemes() }
    var state by remember { mutableStateOf<SettingsState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after every change, so the settings are read again.
    var version by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onClose)

    // Reads the settings on a background thread when the screen opens and
    // after every change.
    LaunchedEffect(version) {
        state = try {
            withContext(Dispatchers.IO) {
                val db = DatabaseHolder.get(context)
                SettingsState(db.settingsScreen(), DatabaseHolder.usesDemo(context))
            }
        } catch (e: FfiException) {
            error = e.explanation()
            null
        }
    }

    // Saves one change on a background thread, then re-reads this screen and
    // tells the app to redraw with the new settings. A failure shows Rust's
    // explanation under the rows.
    fun change(action: (Database) -> Unit) {
        scope.launch {
            error = try {
                withContext(Dispatchers.IO) { action(DatabaseHolder.get(context)) }
                null
            } catch (e: FfiException) {
                e.explanation()
            }
            version++
            onChanged()
        }
    }

    val accent = look.color(ColorSlot.ACCENT)
    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " $settingsIcon ${stringResource(R.string.settings_title)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            val current = state
            if (current != null) {
                val s = current.settings
                SectionLabel(look, stringResource(R.string.settings_theme))
                for (theme in themes) {
                    ThemeLine(
                        look = look,
                        theme = theme,
                        chosen = theme.name == s.themeName,
                        onClick = { change { it.setTheme(theme.name) } },
                    )
                }
                Spacer(Modifier.height(look.cell.rows(1)))

                ChoiceRow(look, stringResource(R.string.settings_icons), iconModeName(s.iconMode)) { forward ->
                    change { it.setIconMode(stepIconMode(s.iconMode, forward)) }
                }
                ChoiceRow(look, stringResource(R.string.settings_time), timeFormatName(s.timeFormat)) {
                    val other = if (s.timeFormat == TimeFormat.HOUR12) TimeFormat.HOUR24 else TimeFormat.HOUR12
                    change { db -> db.setTimeFormat(other) }
                }
                ChoiceRow(look, stringResource(R.string.settings_week), weekStartName(s.weekStart)) {
                    val other = if (s.weekStart == WeekStart.SUNDAY) WeekStart.MONDAY else WeekStart.SUNDAY
                    change { db -> db.setWeekStart(other) }
                }
                ChoiceRow(
                    look,
                    stringResource(R.string.settings_banner),
                    stringResource(if (s.showBanner) R.string.value_on else R.string.value_off),
                ) {
                    change { db -> db.setShowBanner(!s.showBanner) }
                }
                if (BuildConfig.DEBUG) {
                    // Debug builds only: demo data in memory, or a real
                    // database file (the one sync is tried with).
                    ChoiceRow(
                        look,
                        stringResource(R.string.settings_data),
                        stringResource(if (current.demo) R.string.data_demo else R.string.data_real),
                    ) {
                        scope.launch {
                            withContext(Dispatchers.IO) { DatabaseHolder.chooseDemo(context, !current.demo) }
                            version++
                            onChanged()
                        }
                    }
                }
                Spacer(Modifier.height(look.cell.rows(1)))

                if (current.demo) {
                    // Greyed out: the demo never syncs.
                    MutedText(look, "  ${stringResource(R.string.settings_sync)}")
                    MutedText(look, "    ${stringResource(R.string.sync_demo_note)}")
                } else {
                    TapText(
                        look,
                        "  ${stringResource(R.string.settings_sync)}",
                        look.color(ColorSlot.FOREGROUND),
                        onClick = onOpenSync,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(look.cell.rows(1)))
            }

            val message = error
            if (message != null) {
                BasicText(text = message, style = look.text.copy(color = look.color(ColorSlot.DANGER)))
                Spacer(Modifier.height(look.cell.rows(1)))
            }
            TapText(look, stringResource(R.string.close_button), look.color(ColorSlot.MUTED), onClick = onClose)
        }
    }
}

/** A heading line inside the settings box, muted like the form's labels. */
@Composable
private fun SectionLabel(look: TuiLook, text: String) {
    BasicText(text = text, style = look.text.copy(color = look.color(ColorSlot.MUTED)))
}

/**
 * One theme in the list: "› " when [chosen], the name (accent and bold when
 * chosen), then five swatches in the theme's own colours (background, Tasks,
 * Events, accent, danger), as in the terminal app. The whole line is one tap
 * target, [LINE_ROWS] rows tall.
 */
@Composable
private fun ThemeLine(look: TuiLook, theme: ThemeInfo, chosen: Boolean, onClick: () -> Unit) {
    val nameStyle = if (chosen) {
        SpanStyle(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold)
    } else {
        SpanStyle(color = look.color(ColorSlot.FOREGROUND))
    }
    val swatches = listOf(ColorSlot.BACKGROUND, ColorSlot.TASK, ColorSlot.EVENT, ColorSlot.ACCENT, ColorSlot.DANGER)
    val line = buildAnnotatedString {
        withStyle(nameStyle) {
            append(if (chosen) "› " else "  ")
            append(theme.name.padEnd(THEME_NAME_CELLS))
        }
        // `theme.colors.color(slot)` looks the slot up in THAT theme's
        // palette, not the one on screen.
        for (slot in swatches) {
            withStyle(SpanStyle(color = theme.colors.color(slot))) { append("██") }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = look.cell.rows(LINE_ROWS))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = line, style = look.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * One setting: "  Label       ‹ value ›", in the form's style. The label is
 * muted; the arrows are accent-coloured, with tap areas of at least 48dp.
 * [onStep] runs with `true` for "›" (forward) and `false` for "‹" (back);
 * settings with two values switch either way.
 */
@Composable
private fun ChoiceRow(look: TuiLook, label: String, value: String, onStep: (forward: Boolean) -> Unit) {
    val accent = look.color(ColorSlot.ACCENT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = look.cell.rows(LINE_ROWS)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "  " + label.padEnd(LABEL_CELLS) + " ",
            style = look.text.copy(color = look.color(ColorSlot.MUTED)),
        )
        TapText(look, "‹ ", accent, onClick = { onStep(false) }, largeTarget = true)
        BasicText(text = value, style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)))
        TapText(look, " › ", accent, onClick = { onStep(true) }, largeTarget = true)
    }
}

/** The icon mode after (or before, when not [forward]) [mode]: Nerd, Unicode, ASCII, round. */
private fun stepIconMode(mode: IconMode, forward: Boolean): IconMode {
    val all = IconMode.entries
    val step = if (forward) 1 else all.size - 1
    return all[(all.indexOf(mode) + step) % all.size]
}

/** The name shown for an icon mode. */
@Composable
private fun iconModeName(mode: IconMode): String = stringResource(
    when (mode) {
        IconMode.NERD -> R.string.icons_nerd
        IconMode.UNICODE -> R.string.icons_unicode
        IconMode.ASCII -> R.string.icons_ascii
    },
)

/** The name shown for a time format. */
@Composable
private fun timeFormatName(format: TimeFormat): String = stringResource(
    when (format) {
        TimeFormat.HOUR12 -> R.string.time_12
        TimeFormat.HOUR24 -> R.string.time_24
    },
)

/** The name shown for the first day of the week. */
@Composable
private fun weekStartName(start: WeekStart): String = stringResource(
    when (start) {
        WeekStart.SUNDAY -> R.string.week_sunday
        WeekStart.MONDAY -> R.string.week_monday
    },
)
