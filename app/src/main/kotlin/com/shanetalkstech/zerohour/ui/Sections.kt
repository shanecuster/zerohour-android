package com.shanetalkstech.zerohour.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.FLICK_SPEED_DP
import com.shanetalkstech.zerohour.data.SwipeOutcome
import com.shanetalkstech.zerohour.data.swipeArmed
import com.shanetalkstech.zerohour.data.swipeOutcome
import kotlin.math.roundToInt
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.EventRow
import uniffi.zerohour_ffi.TaskRow

// The Tasks and Events boxes of the main screen. Their columns follow the
// terminal app's main screen (tui/src/ui/list.rs in the zerohour repo):
//
//   Tasks:  marker  priority  title...  [date]  due   [priority name]
//   Events: marker  [bell]  title...  [date]  countdown  [repeat]
//   (running events come first, with "19 days left"; core sorts and words them)
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
 * - [pull]: pulling the list down at its top to sync (see [PullToSync]).
 *   It works on an empty list too.
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
    pull: ListPull,
    modifier: Modifier = Modifier,
) {
    val sectionColor = look.color(ColorSlot.TASK)
    TuiBox(
        look = look,
        borderColor = sectionColor,
        modifier = modifier,
        title = sectionTitle(look, taskIcon, stringResource(R.string.tasks), tasks.size, sectionColor),
    ) {
        // The list (or the empty note) inside the pull-to-sync wrapper.
        PullToSync(look, pull.syncIcon, pull.syncing, pull.onRelease) {
            if (tasks.isEmpty()) {
                EmptyNote(look, stringResource(R.string.no_tasks))
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
                // `fillMaxSize`: the list takes the whole box even when its lines
                // are fewer, so a drag in the blank space below them pulls too.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    pull: ListPull,
    modifier: Modifier = Modifier,
) {
    val sectionColor = look.color(ColorSlot.EVENT)
    TuiBox(
        look = look,
        borderColor = sectionColor,
        modifier = modifier,
        title = sectionTitle(look, eventIcon, stringResource(R.string.events), events.size, sectionColor),
    ) {
        // The list (or the empty note) inside the pull-to-sync wrapper.
        PullToSync(look, pull.syncIcon, pull.syncing, pull.onRelease) {
            if (events.isEmpty()) {
                EmptyNote(look, stringResource(R.string.no_events))
            } else {
                val markerCells = cellCount(eventIcon)
                // The terminal app uses a fixed 5 (narrow) or 13 (wide) columns;
                // never less than the longest countdown, so "today!" is not cut.
                val longest = events.maxOf { cellCount(it.countdown.text) }
                val countdownCells = maxOf(longest, if (narrow) 5 else 13)
                // The daily-reminder bell gets its own column, only when some event
                // has an end date, so a list without any looks as before (as in
                // the terminal app). Its width is the widest mark shown.
                val markCells = events.mapNotNull { it.mark }.maxOfOrNull { cellCount(it.text) }

                // `fillMaxSize`: the list takes the whole box even when its lines
                // are fewer, so a drag in the blank space below them pulls too.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events, key = { it.id }) { event ->
                        SwipeLine(
                            look = look,
                            onTap = { onEdit(event.id) },
                            onSwipeRight = { onComplete(event.id) },
                            onSwipeLeft = { onDelete(event.id, event.title) },
                        ) {
                            CellText(look, eventIcon, sectionColor, markerCells)
                            if (markCells != null) {
                                // The bell (accent while the range runs, muted
                                // before), the crossed-out bell when stopped, or a
                                // blank that keeps the titles lined up.
                                val mark = event.mark
                                Gap(look)
                                CellText(
                                    look,
                                    mark?.text.orEmpty(),
                                    look.color(mark?.slot ?: ColorSlot.MUTED),
                                    markCells,
                                )
                            }
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
 * What happens when the finger lets go of a dragged line: it decides where
 * the line goes with zerohour's rules ([swipeOutcome]: 60% of the width, or
 * a fast flick past 30%), then slides it there.
 *
 * Why not Compose's own `AnchoredDraggableDefaults.flingBehavior`: it treats
 * any flick faster than a fixed 125 dp/s as a swipe, whatever the distance,
 * and that speed cannot be changed. That made accidental swipes easy.
 *
 * A [FlingBehavior] is Compose's hook for "the finger let go with this
 * speed". `anchoredDraggable` calls [performFling] with the speed, inside a
 * [ScrollScope] whose `scrollBy` moves the line by that many pixels. When
 * the line ends exactly on a resting position, the state reports it as
 * settled, which is what runs the swipe's action.
 *
 * @param state the line's drag state (its offset and resting positions).
 * @param flickSpeed [FLICK_SPEED_DP] in pixels per second, for this screen.
 */
private class SwipeFling(
    private val state: AnchoredDraggableState<SwipeAnchor>,
    private val flickSpeed: Float,
) : FlingBehavior {
    /**
     * Picks the resting position and animates to it. Returns the speed left
     * over, always 0: the line uses it all (nothing else should move).
     * `suspend` lets it wait for the animation without blocking the screen.
     */
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val offset = state.requireOffset()
        // The COMPLETE position is the line's width (see `updateAnchors`).
        val width = state.anchors.positionOf(SwipeAnchor.COMPLETE)
        val target = when (swipeOutcome(offset, width, initialVelocity, flickSpeed)) {
            SwipeOutcome.RIGHT -> SwipeAnchor.COMPLETE
            SwipeOutcome.LEFT -> SwipeAnchor.DELETE
            SwipeOutcome.REST -> SwipeAnchor.REST
        }
        val targetOffset = state.anchors.positionOf(target)
        // Slide there on Compose's default spring, starting at the finger's
        // speed so the line does not jerk. Each step moves the line to the
        // animation's value: by the difference from where the line is now
        // (not from the last step), because `scrollBy` stops the line at
        // the outer positions and a step it could not take must not count.
        animate(initialValue = offset, targetValue = targetOffset, initialVelocity = initialVelocity) { value, _ ->
            scrollBy(value - state.requireOffset())
        }
        // Land exactly on the position (the state only settles within half
        // a pixel of it), in case the animation stopped a fraction short.
        scrollBy(targetOffset - state.requireOffset())
        return 0f
    }
}

/**
 * One list line: a row of cells, [LINE_ROWS] character rows tall, with its
 * contents centred vertically.
 *
 * Touch, as the plan sets out: one tap runs [onTap]; dragging the line to
 * the right and letting go runs [onSwipeRight], to the left [onSwipeLeft],
 * if the swipe was deliberate enough ([swipeOutcome]: 60% of the width, or
 * a fast flick past 30%); otherwise it springs back. While the finger is
 * still down, the phone vibrates each time the line crosses the 60% point
 * outwards, the moment from which letting go will act.
 * Either way the line then slides back into place (if the action removes it,
 * the next refresh does that). While it is dragged, the space it uncovers
 * says what letting go will do: "done" on the left in the "ok" colour,
 * "delete" on the right in the danger colour.
 *
 * The swipe uses Compose foundation's `AnchoredDraggable`: the line can rest
 * at one of three positions (the [SwipeAnchor]s), follows the finger in
 * between, and when let go slides to the one [SwipeFling] picks.
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
    // The finger's speed for a flick, converted from dp to this screen's
    // pixels, and the fling that applies zerohour's rules (see [SwipeFling]).
    val flickSpeed = with(LocalDensity.current) { FLICK_SPEED_DP.dp.toPx() }
    val fling = remember(state, flickSpeed) { SwipeFling(state, flickSpeed) }
    // Tells whether a finger is dragging the line right now (as opposed to
    // the line sliding on its own after being let go), so the vibration
    // below only happens under the finger. `anchoredDraggable` reports its
    // drags to this source; `collectIsDraggedAsState` turns them into a
    // true/false value Compose watches.
    val interactions = remember { MutableInteractionSource() }
    val fingerDown by interactions.collectIsDraggedAsState()
    val haptics = LocalHapticFeedback.current

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

        // Vibrate once when a dragged line crosses the 60% point outwards.
        // `armed` changes only at that point, so the effect below runs only
        // when it is crossed (either way); it vibrates on the way out, and
        // only while the finger is down, never as the line slides off.
        val armed = swipeArmed(dragged, width)
        LaunchedEffect(armed) {
            if (armed && fingerDown) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
        }

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
                    interactionSource = interactions,
                    flingBehavior = fling,
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

/**
 * The muted note shown in an empty list ("No tasks yet…"), in a column that
 * fills the box and can scroll. It never has anything to scroll, but being
 * scrollable makes a downward drag reach [PullToSync], so an empty list can
 * be pulled to sync like a full one.
 */
@Composable
private fun EmptyNote(look: TuiLook, text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MutedText(look, text)
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
