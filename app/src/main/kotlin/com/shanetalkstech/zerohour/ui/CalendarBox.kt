package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.CalendarDay
import uniffi.zerohour_ffi.CalendarView
import uniffi.zerohour_ffi.ColorSlot

/**
 * Each day cell is this many characters wide: the terminal app uses 3 (two
 * digits and a space); one more gives a finger a little more room.
 */
private const val DAY_CELLS = 4

/** The box's width in cells: seven days, one blank column each side, the borders. */
const val CALENDAR_BOX_CELLS = 7 * DAY_CELLS + 2 + 2

/**
 * The month-grid date picker that opens over the add/edit form, like the
 * terminal app's (Ctrl+D or F2 there):
 *
 * ```
 * ╭ October 2026 ──────────────────╮
 * │ ‹ prev       today      next › │
 * │  Su  Mo  Tu  We  Th  Fr  Sa    │
 * │  27  28  29  30   1   2   3    │
 * │  ...six weeks...               │
 * │ week: Su                cancel │
 * ╰────────────────────────────────╯
 * ```
 *
 * Which dates are in which cell and how each looks (muted for other months
 * and past days, the field's date bold, today underlined) come from the Rust
 * side ([CalendarView]). Tapping a day picks it. The callbacks:
 *
 * - [onShift]: −1 or +1, show the month before or after.
 * - [onToday]: jump back to this month.
 * - [onToggleWeekStart]: switch between Sunday and Monday first.
 * - [onPick]: a day was tapped (its ISO date).
 * - [onCancel]: close without picking.
 */
@Composable
fun CalendarBox(
    look: TuiLook,
    calendar: CalendarView,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onToggleWeekStart: () -> Unit,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val accent = look.color(ColorSlot.ACCENT)
    val muted = look.color(ColorSlot.MUTED)
    TuiBox(
        look = look,
        borderColor = accent,
        // The box paints its own background, because it sits on top of the form.
        modifier = Modifier
            .width(look.cell.columns(CALENDAR_BOX_CELLS))
            .background(look.color(ColorSlot.BACKGROUND)),
        title = AnnotatedString(
            " ${calendar.title} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
        paddingCells = 1,
    ) {
        Column {
            // Month navigation, spread across the top.
            Row(modifier = Modifier.fillMaxWidth()) {
                TapText(look, stringResource(R.string.calendar_prev), accent, onClick = { onShift(-1) })
                Spacer(Modifier.weight(1f))
                TapText(look, stringResource(R.string.calendar_today), accent, onClick = onToday)
                Spacer(Modifier.weight(1f))
                TapText(look, stringResource(R.string.calendar_next), accent, onClick = { onShift(1) })
            }
            // The weekday headers, "Su Mo ...", right-aligned over the digits.
            Row {
                for (label in calendar.dayLabels) {
                    BasicText(
                        text = label.padStart(DAY_CELLS - 1).padEnd(DAY_CELLS),
                        style = look.text.copy(color = muted),
                    )
                }
            }
            // Six weeks of seven days.
            for (week in calendar.weeks) {
                Row {
                    for (day in week) {
                        DayCell(look, day, onPick)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                // "week: Su" shows the first day now; tapping switches it.
                val first = calendar.dayLabels.firstOrNull().orEmpty()
                TapText(
                    look,
                    stringResource(R.string.calendar_week_start, first),
                    muted,
                    onClick = onToggleWeekStart,
                )
                Spacer(Modifier.weight(1f))
                TapText(look, stringResource(R.string.cancel), muted, onClick = onCancel)
            }
        }
    }
}

/**
 * One day of the grid: its number right-aligned in a [DAY_CELLS]-wide cell,
 * two rows tall, in the colour and style the Rust side chose. Tapping it
 * picks that date.
 */
@Composable
private fun DayCell(look: TuiLook, day: CalendarDay, onPick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(look.cell.columns(DAY_CELLS))
            .height(look.cell.rows(LINE_ROWS))
            .clickable { onPick(day.iso) },
        contentAlignment = Alignment.CenterStart,
    ) {
        // "  7" or " 27": the digits right-aligned under the two-letter
        // headers. Only the digits get the style, so today's underline does
        // not run under the blanks in front.
        val digits = day.day.toString()
        BasicText(
            text = buildAnnotatedString {
                append(" ".repeat(DAY_CELLS - 1 - digits.length))
                withStyle(
                    SpanStyle(
                        fontWeight = if (day.bold) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (day.underline) TextDecoration.Underline else null,
                    ),
                ) { append(digits) }
            },
            style = look.text.copy(color = look.color(day.slot)),
        )
    }
}
