package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import com.shanetalkstech.zerohour.R
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
 * - [modifier]: decides the box's size (the caller shares the height out).
 */
@Composable
fun TasksBox(
    look: TuiLook,
    tasks: List<TaskRow>,
    taskIcon: String,
    narrow: Boolean,
    showDates: Boolean,
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
                    ListLine(look) {
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
 */
@Composable
fun EventsBox(
    look: TuiLook,
    events: List<EventRow>,
    eventIcon: String,
    narrow: Boolean,
    showDates: Boolean,
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
                    ListLine(look) {
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
 * How many rows the Events box needs to show every event without scrolling:
 * its lines plus the top and bottom border. Used by the "fit" layout.
 * An empty box still gets one line for its message.
 */
fun eventsBoxRows(eventCount: Int): Int = maxOf(eventCount, 1) * LINE_ROWS + 2

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

/**
 * One list line: a row of cells, [LINE_ROWS] character rows tall, with its
 * contents centred vertically. `RowScope.()` lets the contents use
 * `Modifier.weight`, which [TitleText] needs.
 */
@Composable
private fun ListLine(look: TuiLook, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(look.cell.rows(LINE_ROWS)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
