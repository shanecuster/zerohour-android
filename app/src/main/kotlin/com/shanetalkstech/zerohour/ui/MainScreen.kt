package com.shanetalkstech.zerohour.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.nowText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.LayoutMode
import uniffi.zerohour_ffi.MainScreen

/**
 * Below this many columns the screen uses the short texts and drops the
 * secondary columns. The same limit as the terminal app's narrow mode; a phone
 * held upright (about 40 to 45 columns) is always narrow.
 */
private const val NARROW_COLUMNS = 60

/** From this many columns up, the date column is shown (as in the terminal app). */
private const val DATE_COLUMNS = 90

/**
 * The banner only shows when the screen is at least this many rows tall, so it
 * never squeezes the Tasks and Events boxes (the terminal app's rule; a phone
 * held upright has room, one turned sideways usually does not).
 */
private const val BANNER_MIN_ROWS = 30

/**
 * What the main screen is showing right now.
 *
 * A `sealed interface` is a closed set of cases: the compiler knows every
 * kind of `LoadState`, so a `when` over it needs no `else` branch.
 */
private sealed interface LoadState {
    /** The database is still being opened. */
    data object Loading : LoadState

    /** The screen's content, ready to draw. */
    data class Loaded(val screen: MainScreen) : LoadState

    /** Reading failed; [message] says why. */
    data class Failed(val message: String) : LoadState
}

/**
 * The main screen (read-only for now): the frame with the app name and the
 * date, the banner and tagline, and the Tasks and Events boxes.
 *
 * It works out how many character columns and rows fit on the screen, asks the
 * Rust side for the content (on a background thread), and draws it. The
 * content is fetched again at the start of every minute, so "in 3h" and the
 * countdowns stay current while the app is open.
 */
@Composable
fun MainRoute() {
    val context = LocalContext.current
    // Before the database is read there is no saved theme yet; use core's
    // default for the loading screen.
    val fallbackTheme = remember { defaultTheme() }
    val cell = rememberCellMetrics(BaseTextStyle)
    // `mutableStateOf` holds a value Compose watches: assigning a new one
    // redraws whatever read it. `by` lets us read and write it like a plain
    // variable; `remember` keeps it across redraws.
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    // The theme colours: the saved theme once loaded, the default before.
    val palette = (state as? LoadState.Loaded)?.screen?.settings?.theme?.colors
        ?: fallbackTheme.colors
    val look = TuiLook(palette, BaseTextStyle, cell)

    // Paint the whole screen, including behind the status and navigation
    // bars, with the theme background...
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(look.color(ColorSlot.BACKGROUND)),
    ) {
        // ...but keep the content out from under the bars and the camera
        // cut-out (`safeDrawing`). `BoxWithConstraints` tells its content how
        // much room it has (`maxWidth`, `maxHeight`).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Dividing one `Dp` by another gives a plain number; `toInt()`
            // drops the part of a cell that does not fit.
            val columns = (maxWidth / cell.width).toInt()
            val rows = (maxHeight / cell.height).toInt()
            val narrow = columns < NARROW_COLUMNS

            // Runs when this screen appears, and again if `narrow` changes
            // (the phone is turned). It is cancelled when the screen goes away,
            // which also ends the loop.
            LaunchedEffect(narrow) {
                while (true) {
                    state = load(context, narrow)
                    delay(millisUntilNextMinute())
                }
            }

            // `val s = state` takes one snapshot, so the branches below all see
            // the same value (and Kotlin can tell which case it is).
            when (val s = state) {
                LoadState.Loading -> MessageScreen(look, stringResource(R.string.loading), ColorSlot.MUTED)
                is LoadState.Failed -> MessageScreen(look, s.message, ColorSlot.DANGER)
                is LoadState.Loaded -> MainScreenContent(look, s.screen, columns, rows, narrow)
            }
        }
    }
}

/**
 * Reads the main screen from the database, on `Dispatchers.IO` (the pool of
 * background threads meant for disk and network work), so the UI never
 * freezes. `suspend` means the caller waits without blocking its thread.
 */
private suspend fun load(context: Context, narrow: Boolean): LoadState =
    withContext(Dispatchers.IO) {
        try {
            LoadState.Loaded(DatabaseHolder.get(context).mainScreen(nowText(), narrow))
        } catch (e: FfiException) {
            // The Rust error's explanation, per kind of error.
            val detail = when (e) {
                is FfiException.Database -> e.detail
                is FfiException.InvalidInput -> e.detail
            }
            LoadState.Failed(detail)
        }
    }

/** Milliseconds from now until the clock next reaches a whole minute. */
private fun millisUntilNextMinute(): Long {
    val minute = 60_000L
    return minute - System.currentTimeMillis() % minute
}

/**
 * The loaded main screen, laid out like the terminal app:
 *
 * ```
 * ╭ zerohour · demo ──── Thu Oct 22 2026 ╮
 * │      ▀▀█ █▀▀ █▀█ █▀█ █ █ ...         │
 * │        5 days to Lake trip           │
 * │╭ ☐ Tasks (8) ───────────────────────╮│
 * ││ ☐ ▲ Send project update      3h    ││
 * │╰────────────────────────────────────╯│
 * │╭ ◷ Events (5) ──────────────────────╮│
 * ││ ◷ Lake trip                  5d    ││
 * │╰────────────────────────────────────╯│
 * ╰──────────────────────────────────────╯
 * ```
 *
 * [columns] and [rows] are how many character cells fit on the screen.
 */
@Composable
private fun MainScreenContent(
    look: TuiLook,
    screen: MainScreen,
    columns: Int,
    rows: Int,
    narrow: Boolean,
) {
    val settings = screen.settings
    // The banner needs: the setting on, room for its letters inside the frame
    // with a column to spare on each side, and a tall enough screen.
    val bannerShown = settings.showBanner &&
        columns >= bannerColumns(screen.banner) + 4 &&
        rows >= BANNER_MIN_ROWS
    val bannerRows = if (bannerShown) bannerRows(screen.banner) else 0

    TuiBox(
        look = look,
        borderColor = look.color(ColorSlot.BORDER),
        modifier = Modifier.fillMaxSize(),
        title = headerTitle(look, screen.demo),
        rightTitle = AnnotatedString(
            " ${screen.today} ",
            SpanStyle(color = look.color(ColorSlot.MUTED)),
        ),
        paddingCells = 0,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (bannerShown) {
                BannerView(look, screen.banner)
            }

            // How the two boxes share the height left over. `weight(1f)` means
            // "a share of the free space"; two boxes with weight 1 get half
            // each. In the "fit" layout the Events box is sized to its lines
            // instead (but never more than half), and Tasks get the rest.
            val eventsModifier = when (settings.layout) {
                LayoutMode.SPLIT -> Modifier.weight(1f)
                LayoutMode.FIT -> {
                    // Rows inside the frame: minus its top and bottom border.
                    val freeRows = rows - 2 - bannerRows
                    val half = maxOf(freeRows / 2, 3)
                    Modifier.height(look.cell.rows(eventsBoxRows(screen.events.size).coerceIn(3, half)))
                }
            }

            TasksBox(
                look = look,
                tasks = screen.tasks,
                taskIcon = screen.taskIcon,
                narrow = narrow,
                showDates = columns >= DATE_COLUMNS,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            EventsBox(
                look = look,
                events = screen.events,
                eventIcon = screen.eventIcon,
                narrow = narrow,
                showDates = columns >= DATE_COLUMNS,
                modifier = eventsModifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The frame's title, " zerohour · demo ": the app name in the accent colour,
 * bold, and " · demo" muted when the demo database is showing.
 */
@Composable
private fun headerTitle(look: TuiLook, demo: Boolean): AnnotatedString {
    // `stringResource` can only be called from a composable, so the texts are
    // read here, before the (non-composable) string builder runs.
    val name = stringResource(R.string.app_name)
    val demoSuffix = stringResource(R.string.demo_suffix)
    return buildAnnotatedString {
        append(" ")
        withStyle(SpanStyle(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold)) {
            append(name)
        }
        if (demo) {
            withStyle(SpanStyle(color = look.color(ColorSlot.MUTED))) { append(demoSuffix) }
        }
        append(" ")
    }
}

/**
 * A full-screen frame with one message in it: "Loading…" while the database
 * opens, or the explanation in the danger colour if it could not be read.
 */
@Composable
private fun MessageScreen(look: TuiLook, message: String, slot: ColorSlot) {
    val isError = slot == ColorSlot.DANGER
    TuiBox(
        look = look,
        borderColor = look.color(if (isError) ColorSlot.DANGER else ColorSlot.BORDER),
        modifier = Modifier.fillMaxSize(),
        title = if (isError) {
            AnnotatedString(
                stringResource(R.string.error_title),
                SpanStyle(color = look.color(ColorSlot.DANGER), fontWeight = FontWeight.Bold),
            )
        } else {
            null
        },
    ) {
        BasicText(text = message, style = look.text.copy(color = look.color(slot)))
    }
}
