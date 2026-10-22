package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.WeekBar

/** Bars this wide or wider get the "10/05" label; narrower ones get "05". */
private const val LONG_LABEL_CELLS = 5

/** One blank column between bars, as in the terminal app. */
private const val BAR_GAP_CELLS = 1

/** A character cell is split into this many steps of height, like ▁▂▃▄▅▆▇█. */
private const val TICKS_PER_CELL = 8

/**
 * The "Completions per week" box: one bar per week, oldest on the left and
 * this week (in the accent colour) on the right, with each week's Monday
 * under its bar and the count at the foot of the bar.
 *
 * ```
 * ╭ Completions per week ────────────────╮
 * │             ▆▆          ▆▆    ▆▆     │
 * │    ▅▅    ▇▇ ██    ▆▆ ██ ██    ██     │
 * │ 4  6  2  8  9  3  7  9  6  5  9  1   │
 * │ 03 10 17 24 31 07 14 21 28 05 12 19  │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * (The row of counts is the bars' bottom row: each count sits on its bar's
 * colour.)
 *
 * It follows the rules of the terminal app's chart (ratatui's `BarChart`, as
 * the terminal app sets it up), so both draw the same bars:
 *
 * - The bars share the width: each is (width − gaps) ÷ number of bars
 *   columns wide, at least 1, with one blank column between bars.
 * - The tallest bar reaches the top. Every bar's height is measured in
 *   eighths of a row: count × rows × 8 ÷ the highest count, rounded down.
 * - The count is printed at the foot of its bar, in the background colour on
 *   the bar's colour, when it is not 0 and fits the bar's width (a count
 *   exactly as wide as the bar needs a bar at least one row tall).
 * - The label is "10/05" under bars at least 5 columns wide, "05" otherwise.
 *
 * The terminal draws a bar's top with the eighth-block characters ▁ to █.
 * Here each bar is painted as one rectangle whose height is a whole number of
 * those eighths: it looks the same, without the thin seams the font leaves
 * between stacked █ characters (the banner is painted for the same reason).
 *
 * When nothing has been completed yet, the box shows a hint instead.
 * [modifier] gives the box its size.
 */
@Composable
fun WeekChart(look: TuiLook, weeks: List<WeekBar>, empty: Boolean, modifier: Modifier = Modifier) {
    TuiBox(
        look = look,
        borderColor = look.color(ColorSlot.BORDER),
        modifier = modifier,
        title = AnnotatedString(
            " ${stringResource(R.string.stats_chart_title)} ",
            SpanStyle(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold),
        ),
        // The bars use the box's whole inside, as in the terminal app.
        paddingCells = 0,
    ) {
        if (empty) {
            // A leading space like the terminal app's hint; this one wraps
            // onto more lines instead of being cut at the box's edge.
            MutedText(look, " ${stringResource(R.string.stats_chart_empty)}")
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // The inside of the box, in whole character cells.
                val columns = (maxWidth / look.cell.width).toInt()
                val rows = (maxHeight / look.cell.height).toInt()
                Bars(look, weeks, columns, rows)
            }
        }
    }
}

/**
 * Paints the bars, counts and labels on a Canvas sized [columns] by [rows]
 * cells. The bottom row is for the labels; the rows above it are for the bars.
 */
@Composable
private fun Bars(look: TuiLook, weeks: List<WeekBar>, columns: Int, rows: Int) {
    // A TextMeasurer lays out text so a Canvas can draw it at an exact spot.
    val measurer = rememberTextMeasurer()
    val barRows = rows - 1
    if (weeks.isEmpty() || barRows < 1) return

    val count = weeks.size
    val barCells = maxOf((columns - BAR_GAP_CELLS * (count - 1)) / count, 1)
    // `maxOf { }` is the largest count; never below 1, so no division by 0.
    val highest = maxOf(weeks.maxOf { it.count.toLong() }, 1L)
    val background = look.color(ColorSlot.BACKGROUND)
    val muted = look.color(ColorSlot.MUTED)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellWidth = look.cell.width.toPx()
        val cellHeight = look.cell.height.toPx()
        // The bottom edge of the bars, where the label row starts.
        val barsBottom = barRows * cellHeight

        weeks.forEachIndexed { index, week ->
            val firstCell = index * (barCells + BAR_GAP_CELLS)
            // A bar that would run past the right edge is left out, as the
            // terminal app does.
            if (firstCell + barCells > columns) return@forEachIndexed
            val left = firstCell * cellWidth
            val barColor = look.color(if (week.current) ColorSlot.ACCENT else ColorSlot.TASK)

            // The height in eighths of a row ("ticks"), rounded down.
            val ticks = week.count.toLong() * barRows * TICKS_PER_CELL / highest
            val height = ticks * cellHeight / TICKS_PER_CELL
            drawRect(
                color = barColor,
                topLeft = Offset(left, barsBottom - height),
                size = Size(barCells * cellWidth, height),
            )

            // The count, in the bar's bottom row, centred (leaning left when
            // it can't be exactly centred, like the terminal app).
            val value = week.count.toString()
            val valueCells = cellCount(value)
            val fits = valueCells < barCells || (valueCells == barCells && ticks >= TICKS_PER_CELL)
            if (week.count != 0u && fits) {
                val valueLeft = left + (barCells - valueCells) / 2 * cellWidth
                val rowTop = barsBottom - cellHeight
                // Its cells get the bar's colour as background, and the
                // digits the screen's background colour.
                drawRect(
                    color = barColor,
                    topLeft = Offset(valueLeft, rowTop),
                    size = Size(valueCells * cellWidth, cellHeight),
                )
                drawText(
                    textMeasurer = measurer,
                    text = value,
                    topLeft = Offset(valueLeft, rowTop),
                    style = look.text.copy(color = background),
                )
            }

            // The label under the bar: cut to the bar's width, centred.
            val label = (if (barCells >= LONG_LABEL_CELLS) week.longLabel else week.shortLabel).take(barCells)
            val labelLeft = left + (barCells - cellCount(label)) / 2 * cellWidth
            drawText(
                textMeasurer = measurer,
                text = label,
                topLeft = Offset(labelLeft, barsBottom),
                style = look.text.copy(color = muted),
            )
        }
    }
}
