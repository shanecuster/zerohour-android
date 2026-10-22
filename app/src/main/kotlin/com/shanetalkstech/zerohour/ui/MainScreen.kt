package com.shanetalkstech.zerohour.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.CompleteOutcome
import uniffi.zerohour_ffi.Database
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.ItemForm
import uniffi.zerohour_ffi.ItemKind
import uniffi.zerohour_ffi.MainScreen
import uniffi.zerohour_ffi.StatsScreen

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

    /**
     * The content, ready to draw: the main [screen] (header, lists, tab bar
     * icons) and the [stats] for the Stats tab. Both are read together, so
     * switching tabs never waits for the database.
     */
    data class Loaded(val screen: MainScreen, val stats: StatsScreen) : LoadState

    /** Reading failed; [message] says why. */
    data class Failed(val message: String) : LoadState
}

/**
 * A message in the bottom row of the main screen ("Added "Call mum"").
 * [isError] draws it in the danger colour instead of the "ok" colour, as in
 * the terminal app.
 */
private data class Status(val text: String, val isError: Boolean = false)

/**
 * The add/edit form while it is open: the Rust-side [form] object, and
 * whether it was opened to add (start at "Task or Event?") or to edit.
 */
private data class OpenForm(val form: ItemForm, val adding: Boolean)

/** A line swiped left, waiting for "delete" or "keep": its [id] and [title]. */
private data class PendingDelete(val id: String, val title: String)

/**
 * The app's one route: the main screen (a row with the app name and the date,
 * the banner and tagline, the box of the chosen tab and the tab bar), or the
 * add/edit form in its place while one is open.
 *
 * It works out how many character columns and rows fit on the screen, asks the
 * Rust side for the content (on a background thread), and draws it. The
 * content is fetched again at the start of every minute, so "in 3h" and the
 * countdowns stay current while the app is open, and straight after anything
 * changes.
 *
 * Touch: the tab bar switches between Tasks, Events and Stats; "+ add"
 * above it opens a new form; one tap on a line opens it for editing; swiping
 * a line right completes it; swiping it left asks whether to delete it. The
 * back gesture on the Events or Stats tab goes back to Tasks, and only on
 * Tasks leaves the app.
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
    // Bumped after every change to the data, which makes the loading loop
    // below start over and read the new content at once.
    var reloads by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<Status?>(null) }
    var openForm by remember { mutableStateOf<OpenForm?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    // The tab on show. `rememberSaveable` also keeps it if Android recreates
    // the screen (for example after the system language changes), not only
    // across redraws.
    var tab by rememberSaveable { mutableStateOf(Tab.TASKS) }
    // Runs the Rust calls that change things; they end when the screen goes.
    val scope = rememberCoroutineScope()

    // Runs [action] against the database on a background thread, then shows
    // the status text it returns (if any) and reloads the screen. A failure
    // shows Rust's explanation instead, in the danger colour.
    fun runChange(action: (Database) -> String?) {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) { action(DatabaseHolder.get(context)) }?.let { Status(it) }
            } catch (e: FfiException) {
                Status(e.explanation(), isError = true)
            }
            reloads++
        }
    }

    // A line was swiped right: complete it, and say so the way the terminal
    // app does (or that events can't be completed).
    fun complete(id: String) = runChange { db ->
        when (val outcome = db.completeTask(id, nowText())) {
            CompleteOutcome.NotATask -> context.getString(R.string.status_event_not_done)
            is CompleteOutcome.Done -> outcome.nextDue?.let {
                context.getString(R.string.status_done_next, outcome.title, it)
            } ?: context.getString(R.string.status_done, outcome.title)
        }
    }

    // Opens a form made by [make] (a new one, or one filled in from an item).
    fun openFormWith(adding: Boolean, make: (Database) -> ItemForm) {
        status = null
        scope.launch {
            try {
                val form = withContext(Dispatchers.IO) { make(DatabaseHolder.get(context)) }
                openForm = OpenForm(form, adding)
            } catch (e: FfiException) {
                status = Status(e.explanation(), isError = true)
                reloads++
            }
        }
    }

    // Back on the Events or Stats tab returns to Tasks. While the form or the
    // delete box is open, their own back handling applies instead.
    BackHandler(enabled = tab != Tab.TASKS && openForm == null && pendingDelete == null) {
        tab = Tab.TASKS
    }

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
            // (the phone is turned) or something was changed (`reloads`). It
            // is cancelled when the screen goes away, which also ends the loop.
            LaunchedEffect(narrow, reloads) {
                while (true) {
                    state = load(context, narrow)
                    delay(millisUntilNextMinute())
                }
            }

            // `val s = state` takes one snapshot, so the branches below all see
            // the same value (and Kotlin can tell which case it is).
            val form = openForm
            when (val s = state) {
                LoadState.Loading -> MessageScreen(look, stringResource(R.string.loading), ColorSlot.MUTED)
                is LoadState.Failed -> MessageScreen(look, s.message, ColorSlot.DANGER)
                is LoadState.Loaded -> if (form != null) {
                    // The form takes the whole screen until it closes. Its
                    // Rust object is freed by the garbage collector once
                    // nothing refers to it.
                    FormScreen(
                        look = look,
                        form = form.form,
                        chooseKindFirst = form.adding,
                        taskIcon = s.screen.taskIcon,
                        eventIcon = s.screen.eventIcon,
                        // "+ add" on the Events tab suggests an event.
                        preselectedKind = if (tab == Tab.EVENTS) ItemKind.EVENT else ItemKind.TASK,
                        onClose = { message ->
                            openForm = null
                            status = message?.let { Status(it) }
                            reloads++
                        },
                    )
                } else {
                    MainScreenContent(
                        look = look,
                        screen = s.screen,
                        stats = s.stats,
                        columns = columns,
                        rows = rows,
                        narrow = narrow,
                        status = status,
                        tab = tab,
                        onSelectTab = { tab = it },
                        onAdd = { openFormWith(adding = true) { it.newForm() } },
                        onEdit = { id -> openFormWith(adding = false) { it.editForm(id) } },
                        onComplete = ::complete,
                        onDelete = { id, title ->
                            status = null
                            pendingDelete = PendingDelete(id, title)
                        },
                    )
                    val pending = pendingDelete
                    if (pending != null) {
                        // Back means "keep", like the terminal app's n / Esc.
                        BackHandler { pendingDelete = null }
                        // A layer over the screen that swallows taps, so only
                        // the box reacts while it is open.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(interactionSource = null, indication = null, onClick = {}),
                            contentAlignment = Alignment.Center,
                        ) {
                            ConfirmDeleteBox(
                                look = look,
                                columns = columns,
                                title = pending.title,
                                onDelete = {
                                    pendingDelete = null
                                    runChange { db ->
                                        db.deleteItem(pending.id, nowText())
                                        context.getString(R.string.status_deleted, pending.title)
                                    }
                                },
                                onKeep = { pendingDelete = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reads the main screen and the stats from the database, both as of the same
 * moment, on `Dispatchers.IO` (the pool of
 * background threads meant for disk and network work), so the UI never
 * freezes. `suspend` means the caller waits without blocking its thread.
 */
private suspend fun load(context: Context, narrow: Boolean): LoadState =
    withContext(Dispatchers.IO) {
        try {
            val db = DatabaseHolder.get(context)
            val now = nowText()
            LoadState.Loaded(db.mainScreen(now, narrow), db.statsScreen(now))
        } catch (e: FfiException) {
            LoadState.Failed(e.explanation())
        }
    }

/** Milliseconds from now until the clock next reaches a whole minute. */
private fun millisUntilNextMinute(): Long {
    val minute = 60_000L
    return minute - System.currentTimeMillis() % minute
}

/**
 * The loaded main screen, laid out like the terminal app's main screen, but
 * with one box at a time and a tab bar to switch:
 *
 * ```
 *   zerohour · demo        Thu Oct 22 2026
 *       ▀▀█ █▀▀ █▀█ █▀█ █ █ ...
 *         5 days to Lake trip
 * ╭ ☐ Tasks (8) ─────────────────────────╮
 * │ ☐ ▲ Send project update      3h      │
 * │ ...                                  │
 * ╰──────────────────────────────────────╯
 *   Added "Call mum"                + add
 * ╭────────────┬────────────┬────────────╮
 * │  ☐ Tasks   │  ◷ Events  │  ▤ Stats   │
 * ╰────────────┴────────────┴────────────╯
 * ```
 *
 * Unlike the terminal app there is no frame around the whole screen: on a
 * phone every column counts, so the boxes run to the edges. The header and
 * the bottom row keep their text where the frame's edges had it.
 *
 * The Tasks and Events tabs each show their box, filling all the height
 * there is (so the terminal app's "split" and "fit" layouts, which share the
 * height between the two boxes, do not apply here), with the banner above.
 * The Stats tab has no banner and no "+ add" row, as the terminal app's stats
 * screen has neither.
 *
 * [stats] is what the Stats tab shows. [columns] and [rows] are how many
 * character cells fit on the screen. [status] is the message for the bottom row, if any; without one, the row
 * shows the gesture hint ("→ done  ← delete  tap edit"). [tab] is the tab on
 * show, and [onSelectTab] runs when another one is tapped. [onAdd] runs when
 * "+ add" is tapped, [onEdit] when a line is tapped (with the item's ID),
 * [onComplete] when one is swiped right, [onDelete] when one is swiped left
 * (with its ID and title).
 */
@Composable
private fun MainScreenContent(
    look: TuiLook,
    screen: MainScreen,
    stats: StatsScreen,
    columns: Int,
    rows: Int,
    narrow: Boolean,
    status: Status?,
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onComplete: (String) -> Unit,
    onDelete: (id: String, title: String) -> Unit,
) {
    val settings = screen.settings
    // The banner needs: a list tab, the setting on, room for its letters with
    // a column to spare on each side, and a tall enough screen.
    val bannerShown = tab != Tab.STATS &&
        settings.showBanner &&
        columns >= bannerColumns(screen.banner) + 2 &&
        rows >= BANNER_MIN_ROWS

    Column(modifier = Modifier.fillMaxSize()) {
        HeaderRow(look, headerTitle(look, screen.demo), screen.today)
        if (bannerShown) {
            BannerView(look, screen.banner)
        }

        // The chosen tab's content takes all the height the rows around it
        // leave (`weight(1f)`: the whole share of the free space).
        val fill = Modifier
            .weight(1f)
            .fillMaxWidth()
        when (tab) {
            Tab.TASKS -> TasksBox(
                look = look,
                tasks = screen.tasks,
                taskIcon = screen.taskIcon,
                narrow = narrow,
                showDates = columns >= DATE_COLUMNS,
                onEdit = onEdit,
                onComplete = onComplete,
                onDelete = onDelete,
                modifier = fill,
            )
            Tab.EVENTS -> EventsBox(
                look = look,
                events = screen.events,
                eventIcon = screen.eventIcon,
                narrow = narrow,
                showDates = columns >= DATE_COLUMNS,
                onEdit = onEdit,
                onComplete = onComplete,
                onDelete = onDelete,
                modifier = fill,
            )
            Tab.STATS -> StatsTab(
                look = look,
                stats = stats,
                columns = columns,
                narrow = narrow,
                modifier = fill,
            )
        }
        if (tab != Tab.STATS) {
            FooterRow(
                look = look,
                // A status message when there is one, the gesture hint otherwise.
                message = status?.let {
                    AnnotatedString(
                        it.text,
                        SpanStyle(color = look.color(if (it.isError) ColorSlot.DANGER else ColorSlot.OK)),
                    )
                } ?: gestureHint(look),
                onAdd = onAdd,
                // Drawn on top of its neighbours, so its enlarged "+ add" tap
                // area wins where it reaches over the box's bottom line above
                // and the tab bar's top line below.
                modifier = Modifier.zIndex(1f),
            )
        }
        TabBar(
            look = look,
            columns = columns,
            selected = tab,
            taskIcon = screen.taskIcon,
            eventIcon = screen.eventIcon,
            statsIcon = screen.statsIcon,
            onSelect = onSelectTab,
        )
    }
}

/**
 * The top row of the main screen: [title] on the left and [today]'s date,
 * muted, on the right, one column in from each edge (where the terminal app's
 * frame sets them into its top border).
 */
@Composable
private fun HeaderRow(look: TuiLook, title: AnnotatedString, today: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cell.columns(1)),
    ) {
        // `weight(1f)` gives the title whatever the date leaves, so on a very
        // narrow screen the title is cut, never the date.
        BasicText(
            text = title,
            style = look.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = " $today ",
            style = look.text.copy(color = look.color(ColorSlot.MUTED)),
            maxLines = 1,
        )
    }
}

/**
 * "→ done  ← delete  tap edit": what the gestures on a line do, styled like
 * the terminal app's key-hint footer (each key in the accent colour and bold,
 * its word muted, two spaces between pairs).
 */
@Composable
private fun gestureHint(look: TuiLook): AnnotatedString {
    // Read the texts here: `stringResource` only works in a composable, and
    // the string builder below is not one.
    val pairs = listOf(
        stringResource(R.string.hint_done_key) to stringResource(R.string.hint_done),
        stringResource(R.string.hint_delete_key) to stringResource(R.string.hint_delete),
        stringResource(R.string.hint_edit_key) to stringResource(R.string.hint_edit),
    )
    return buildAnnotatedString {
        // `forEachIndexed` also gives each pair's position, so the gap goes
        // only between pairs, not after the last one.
        pairs.forEachIndexed { index, (key, word) ->
            if (index > 0) append("  ")
            withStyle(SpanStyle(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold)) {
                append(key)
            }
            withStyle(SpanStyle(color = look.color(ColorSlot.MUTED))) { append(" $word") }
        }
    }
}

/**
 * The bottom row of the main screen: [message] on the left (it ends in "…"
 * if it is too long), and the tappable "+ add" on the right, which runs
 * [onAdd]. Both sit one column in from the edges, where the terminal app's
 * frame shows them in its bottom border. [modifier] is for the caller's
 * drawing order.
 */
@Composable
private fun FooterRow(look: TuiLook, message: AnnotatedString, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.cell.columns(1)),
    ) {
        // The message gets the width "+ add" leaves, less one blank column
        // between them. The leading space matches the header's title.
        BasicText(
            text = AnnotatedString(" ") + message,
            style = look.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = look.cell.columns(1)),
        )
        // One text row is too short for a finger: the tap area is made at
        // least 48dp each way, the text stays where it is.
        LargeTapArea(onClick = onAdd) {
            BasicText(
                text = stringResource(R.string.add_button),
                style = look.text.copy(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

/**
 * The header's title, " zerohour · demo ": the app name in the accent colour,
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
