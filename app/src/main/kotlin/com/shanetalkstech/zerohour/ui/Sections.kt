package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import com.shanetalkstech.zerohour.R
import kotlin.math.roundToInt
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.EventRow
import uniffi.zerohour_ffi.TaskRow

// The Tasks and Events boxes of the main screen. Their columns follow the
// terminal app's main screen (tui/src/ui/list.rs in the zerohour repo):
//
//   Tasks:  marker  priority  title...  [date]  due   [priority name]
//   Events: marker  title...  [date]  countdown  [repeat]
//
// with one blank column between columns. The parts in [brackets] appear only
// on wide screens, as in the terminal app. All texts and colour slots come
// from the Rust side; this file only lays them out.

/**
 * Each list line is this many character rows tall. The terminal uses one row
 * per line; on a phone, two give a finger something to aim at (the plan's
 * "taller rows for fingers") while staying on the character grid.
 */
const val LINE_ROWS = 2

/** Width of the date column ("Thu Oct 08" or "Oct 07 2027"), as in the terminal app. */
private const val DATE_CELLS = 11

/** Width of the priority name column on wide screens ("Medium"). */
private const val PRIORITY_NAME_CELLS = 6

/** Width of the repeat column on wide screens ("↻ every 12 weeks" plus a space). */
private const val REPEAT_CELLS = 17

/**
 * The Tasks box: the open tasks, already sorted by core.
 *
 * - [taskIcon]: the marker icon for the chosen icon mode.
 * - [narrow]: true on screens under 60 columns (short texts, fewer columns).
 * - [showDates]: true on screens 90 columns or wider (adds the date column).
 * - [onEdit]: one tap on a line, with the task's ID (opens it for editing).
 *   The marker in front of each line is only a picture of a checkbox:
 *   tapping it edits too, it does not tick the task off.
 * - [onComplete]: the line was swiped right (complete the task).
 * - [onDelete]: the line was swiped left, with the ID and the plain title
 *   (ask whether to delete it).
 * - [modifier]: decides the box's size (the caller shares the height out).
 */
@Composable
fun TasksBox(
    look: TuiLook,
    tasks: List<TaskRow>,
    taskIcon: String,
    narrow: Boolean,
    showDates: Boolean,
    onEdit: (String) -> Unit,
    onComplete: (String) -> Unit,
    onDelete: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionColor = look.color(ColorSlot.TASK)
    TuiBox(
        look = look,
        borderColor = sectionColor,
        modifier = modifier,
        title = sectionTitle(look, taskIcon, stringResource(R.string.tasks), tasks.size, sectionColor),
    ) {
        if (tasks.isEmpty()) {
            MutedText(look, stringResource(R.string.no_tasks))
        } else {
            // Fixed column widths, worked out once for the whole list so the
            // columns line up from row to row. `maxOf` gives the largest value
            // the block returns for any task.
            val markerCells = cellCount(taskIcon)
            val priorityCells = tasks.maxOf { cellCount(it.priorityIcon) }
            val dueCells = dueColumnCells(tasks.maxOf { cellCount(it.due.text) }, narrow)

            // `LazyColumn` only builds the lines that are on screen, and
            // scrolls when there are more than fit in the box. `key` tells it
            // which line is which, so lines keep their identity on refresh.
            LazyColumn {
                items(tasks, key = { it.id }) { task ->
                    SwipeLine(
                        look = look,
                        onTap = { onEdit(task.id) },
                        onSwipeRight = { onComplete(task.id) },
                        onSwipeLeft = { onDelete(task.id, task.plainTitle) },
                    ) {
                        CellText(look, taskIcon, sectionColor, markerCells)
                        Gap(look)
                        CellText(look, task.priorityIcon, look.color(task.prioritySlot), priorityCells)
                        Gap(look)
                        TitleText(look, task.title, look.color(task.titleSlot))
                        if (showDates) {
                            Gap(look)
                            CellText(look, task.dateText, look.color(ColorSlot.MUTED), DATE_CELLS)
                        }
                        Gap(look)
                        CellText(look, task.due.text, look.color(task.due.slot), dueCells, task.due.bold)
                        if (!narrow) {
                            Gap(look)
                            CellText(
                                look,
                                task.priorityName,
                                look.color(task.prioritySlot),
                                PRIORITY_NAME_CELLS,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Events box: the visible events with their countdowns, already sorted
 * soonest first by core. The parameters mean the same as for [TasksBox].
 * Swiping an event right is passed on too; the Rust side answers that events
 * can't be completed, and the line springs back.
 */
@Composable
fun EventsBox(
    look: TuiLook,
    events: List<EventRow>,
    eventIcon: String,
    narrow: Boolean,
    showDates: Boolean,
    onEdit: (String) -> Unit,
    onComplete: (String) -> Unit,
    onDelete: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionColor = look.color(ColorSlot.EVENT)
    TuiBox(
        look = look,
        borderColor = sectionColor,
        modifier = modifier,
        title = sectionTitle(look, eventIcon, stringResource(R.string.events), events.size, sectionColor),
    ) {
        if (events.isEmpty()) {
            MutedText(look, stringResource(R.string.no_events))
        } else {
            val markerCells = cellCount(eventIcon)
            // The terminal app uses a fixed 5 (narrow) or 13 (wide) columns;
            // never less than the longest countdown, so "today!" is not cut.
            val longest = events.maxOf { cellCount(it.countdown.text) }
            val countdownCells = maxOf(longest, if (narrow) 5 else 13)

            LazyColumn {
                items(events, key = { it.id }) { event ->
                    SwipeLine(
                        look = look,
                        onTap = { onEdit(event.id) },
                        onSwipeRight = { onComplete(event.id) },
                        onSwipeLeft = { onDelete(event.id, event.title) },
                    ) {
                        CellText(look, eventIcon, sectionColor, markerCells)
                        Gap(look)
                        TitleText(look, event.title, look.color(ColorSlot.FOREGROUND))
                        if (showDates) {
                            Gap(look)
                            CellText(look, event.dateText, look.color(ColorSlot.MUTED), DATE_CELLS)
                        }
                        Gap(look)
                        CellText(
                            look,
                            event.countdown.text,
                            look.color(event.countdown.slot),
                            countdownCells,
                            event.countdown.bold,
                        )
                        if (!narrow) {
                            Gap(look)
                            CellText(look, event.repeatText, look.color(ColorSlot.MUTED), REPEAT_CELLS)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Width of the Tasks box's due column, given the longest due text shown. The
 * same rule as the terminal app: on narrow screens the longest text, at least
 * 5; on wide screens one more than the longest, at least 13 (the extra blank
 * keeps a long timed text from running into the priority name after it).
 */
private fun dueColumnCells(longest: Int, narrow: Boolean): Int =
    if (narrow) maxOf(longest, 5) else maxOf(longest + 1, 13)

/**
 * A section box's title, " ☐ Tasks (8) ": the icon and name in the section
 * colour (the name bold), and the count muted. Colour is never the only clue:
 * Tasks and Events also have different icons.
 */
private fun sectionTitle(
    look: TuiLook,
    icon: String,
    name: String,
    count: Int,
    sectionColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(" ")
    withStyle(SpanStyle(color = sectionColor)) { append(icon) }
    withStyle(SpanStyle(color = sectionColor, fontWeight = FontWeight.Bold)) { append(" $name ") }
    withStyle(SpanStyle(color = look.color(ColorSlot.MUTED))) { append("($count) ") }
}

/** Where a swiped line can come to rest. */
private enum class SwipeAnchor {
    /** Pushed off to the left: ask to delete. */
    DELETE,

    /** In place (the normal state). */
    REST,

    /** Pushed off to the right: complete. */
    COMPLETE,
}

/**
 * How far a line must be dragged before letting go swipes it: 40% of its
 * width. Less springs back. A quick flick swipes it too, whatever the
 * distance (Compose's own rule for anchored drags).
 */
private const val SWIPE_THRESHOLD = 0.4f

/**
 * One list line: a row of cells, [LINE_ROWS] character rows tall, with its
 * contents centred vertically.
 *
 * Touch, as the plan sets out: one tap runs [onTap]; dragging the line to
 * the right and letting go runs [onSwipeRight], to the left [onSwipeLeft].
 * Either way the line then slides back into place (if the action removes it,
 * the next refresh does that). While it is dragged, the space it uncovers
 * says what letting go will do: "done" on the left in the "ok" colour,
 * "delete" on the right in the danger colour.
 *
 * The swipe uses Compose foundation's `AnchoredDraggable`: the line can rest
 * at one of three positions (the [SwipeAnchor]s), follows the finger in
 * between, and settles on the nearest sensible one when let go.
 * `RowScope.()` lets the contents use `Modifier.weight`, which [TitleText]
 * needs.
 */
@Composable
private fun SwipeLine(
    look: TuiLook,
    onTap: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val state = remember { AnchoredDraggableState(initialValue = SwipeAnchor.REST) }

    // When the line comes to rest off to one side, do that side's action and
    // slide it back. `settledValue` changes only once a drag has finished.
    LaunchedEffect(state.settledValue) {
        when (state.settledValue) {
            SwipeAnchor.COMPLETE -> onSwipeRight()
            SwipeAnchor.DELETE -> onSwipeLeft()
            SwipeAnchor.REST -> return@LaunchedEffect
        }
        state.animateTo(SwipeAnchor.REST)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(look.cell.rows(LINE_ROWS)),
    ) {
        // The resting positions, in pixels: a whole line's width either side.
        // They depend on the width, so they are set (again) after each layout.
        val width = constraints.maxWidth.toFloat()
        SideEffect {
            state.updateAnchors(
                DraggableAnchors {
                    SwipeAnchor.DELETE at -width
                    SwipeAnchor.REST at 0f
                    SwipeAnchor.COMPLETE at width
                },
            )
        }
        // How far the line is dragged right now (0 before the first layout).
        val dragged = if (state.offset.isNaN()) 0f else state.offset

        // Underneath: what letting go will do.
        if (dragged > 0f) {
            SwipeHint(look, R.string.swipe_done, ColorSlot.OK, Alignment.CenterStart)
        } else if (dragged < 0f) {
            SwipeHint(look, R.string.swipe_delete, ColorSlot.DANGER, Alignment.CenterEnd)
        }

        // On top: the line itself, moved by the drag. It paints the theme
        // background so the hint underneath only shows where it has moved
        // away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(look.cell.rows(LINE_ROWS))
                .offset { IntOffset(dragged.roundToInt(), 0) }
                .background(look.color(ColorSlot.BACKGROUND))
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state,
                        positionalThreshold = { distance -> distance * SWIPE_THRESHOLD },
                    ),
                )
                .clickable(onClick = onTap),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The word shown in the space a dragged line uncovers, at one end of the
 * line ([alignment]), bold in [slot]'s colour.
 */
@Composable
private fun SwipeHint(look: TuiLook, text: Int, slot: ColorSlot, alignment: Alignment) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        BasicText(
            text = stringResource(text),
            style = look.text.copy(color = look.color(slot), fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = look.cell.columns(1)),
        )
    }
}

/** One blank column between two columns, like the terminal app's column gap. */
@Composable
private fun Gap(look: TuiLook) {
    Spacer(Modifier.width(look.cell.columns(1)))
}

/**
 * Text in a fixed-width column of [cells] characters, cut off if it is longer
 * (core keeps these texts short, so in practice it always fits).
 */
@Composable
private fun CellText(look: TuiLook, text: String, color: Color, cells: Int, bold: Boolean = false) {
    BasicText(
        text = text,
        modifier = Modifier.width(look.cell.columns(cells)),
        style = look.text.copy(
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/**
 * The title column: it takes all the width the fixed columns leave
 * (`weight(1f)`), and a title that is too long ends in "…", as in the
 * terminal app. `RowScope` is needed because `weight` only exists inside a Row.
 */
@Composable
private fun RowScope.TitleText(look: TuiLook, text: String, color: Color) {
    BasicText(
        text = text,
        modifier = Modifier.weight(1f),
        style = look.text.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A line of de-emphasized text, for the empty-box messages. */
@Composable
fun MutedText(look: TuiLook, text: String) {
    BasicText(text = text, style = look.text.copy(color = look.color(ColorSlot.MUTED)))
}
