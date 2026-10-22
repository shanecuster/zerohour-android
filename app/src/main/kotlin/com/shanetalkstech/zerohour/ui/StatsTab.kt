package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.StatsScreen

// The Stats tab. It follows the terminal app's stats screen
// (tui/src/ui/stats.rs in the zerohour repo): four summary cards, a bar chart
// of completions per week, and the completions by priority. The numbers all
// come from core's stats module through the Rust side; this file only lays
// them out.

/** Each summary card is four rows tall: border, big number, small text, border. */
private const val CARD_ROWS = 4

/**
 * The Stats tab's content, between the header row and the tab bar:
 *
 * ```
 * ╭ Completed ────────╮╭ Streak ──────────╮
 * │ 92                ││ 9 days           │
 * │ week 3 · month 12 ││ best 9           │
 * ╰───────────────────╯╰──────────────────╯
 * ╭ Overdue ──────────╮╭ On time ─────────╮
 * │ 1                 ││ 93%              │
 * │ past due          ││ 7 done late      │
 * ╰───────────────────╯╰──────────────────╯
 * ╭ Completions per week ──────────────────╮
 * │ ...the bars, see [WeekChart]...        │
 * ╰────────────────────────────────────────╯
 * ```
 *
 * [columns] is the screen's width in character cells. [narrow] (under 60
 * columns, a phone held upright) puts the cards in two rows of two and uses
 * the shorter texts that fit their width, as the terminal app's narrow mode
 * puts them in a 2 by 2 grid; otherwise the four cards share one row.
 * [modifier] gives the tab its size.
 */
@Composable
fun StatsTab(
    look: TuiLook,
    stats: StatsScreen,
    columns: Int,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val cards = summaryCards(stats, narrow)
    Column(modifier = modifier) {
        if (narrow) {
            // Two rows of two. Each card on the left gets half the columns
            // (whole cells, so its border stays on the grid); the right one
            // takes the rest.
            for (pair in cards.chunked(2)) {
                CardRow(look, pair, columns)
            }
        } else {
            CardRow(look, cards, columns)
        }
        // The chart takes all the height the other parts leave.
        WeekChart(
            look = look,
            weeks = stats.weeks,
            empty = stats.totalAll == 0u,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

/**
 * One summary card's content: its [title], the [big] number, the [small]
 * text under it, and the colour slot of the number ([slot]).
 */
private data class SummaryCard(
    val title: String,
    val big: String,
    val small: String,
    val slot: ColorSlot,
)

/**
 * The four cards, with the terminal app's colours: Completed in the accent
 * colour, Streak in "ok", Overdue in danger or ok (the Rust side decides),
 * and On time in the Tasks colour. The texts are the terminal app's, with a
 * shorter small line on narrow screens where its own would be cut off.
 */
@Composable
private fun summaryCards(stats: StatsScreen, narrow: Boolean): List<SummaryCard> {
    // `toInt()`: UniFFI turns Rust's `u32` into Kotlin's `UInt`, and Android's
    // plural texts pick their form from an `Int`.
    val streak = stats.currentStreak.toInt()
    return listOf(
        SummaryCard(
            title = stringResource(R.string.stats_completed),
            big = stats.totalAll.toString(),
            small = stringResource(R.string.stats_week_month, stats.totalWeek.toString(), stats.totalMonth.toString()),
            slot = ColorSlot.ACCENT,
        ),
        SummaryCard(
            title = stringResource(R.string.stats_streak),
            big = pluralStringResource(R.plurals.stats_days, streak, streak),
            small = stringResource(R.string.stats_best, stats.longestStreak.toString()),
            slot = ColorSlot.OK,
        ),
        SummaryCard(
            title = stringResource(R.string.stats_overdue),
            big = stats.overdueCount.toString(),
            small = stringResource(if (narrow) R.string.stats_past_due_short else R.string.stats_past_due),
            slot = stats.overdueSlot,
        ),
        SummaryCard(
            title = stringResource(R.string.stats_on_time),
            big = stats.onTimeText,
            small = if (narrow) {
                stringResource(R.string.stats_late_short, stats.late.toString())
            } else {
                stringResource(R.string.stats_on_time_late, stats.onTime.toString(), stats.late.toString())
            },
            slot = ColorSlot.TASK,
        ),
    )
}

/**
 * [cards] side by side in a row [CARD_ROWS] tall, sharing [columns] cells:
 * every card but the last gets an equal whole number of cells, and the last
 * one takes what is left, so the borders stay on the character grid.
 */
@Composable
private fun CardRow(look: TuiLook, cards: List<SummaryCard>, columns: Int) {
    val cardCells = columns / cards.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(look.cell.rows(CARD_ROWS)),
    ) {
        cards.forEachIndexed { index, card ->
            val width = if (index < cards.lastIndex) {
                Modifier.width(look.cell.columns(cardCells))
            } else {
                Modifier.weight(1f)
            }
            Card(look, card, width)
        }
    }
}

/**
 * One summary card: a rounded box in the border colour with its title muted
 * in the top edge, the big number bold in its colour, and the small text
 * muted. Like the terminal app, both lines start with one blank column, and
 * a line too long for the card ends in "…".
 */
@Composable
private fun Card(look: TuiLook, card: SummaryCard, modifier: Modifier) {
    TuiBox(
        look = look,
        borderColor = look.color(ColorSlot.BORDER),
        modifier = modifier,
        title = AnnotatedString(" ${card.title} ", SpanStyle(color = look.color(ColorSlot.MUTED))),
        // No blank columns from the box: the texts bring their own leading
        // space, as in the terminal app, which leaves one more cell for text.
        paddingCells = 0,
    ) {
        Column {
            CardLine(card.big, look.text.copy(color = look.color(card.slot), fontWeight = FontWeight.Bold))
            CardLine(card.small, look.text.copy(color = look.color(ColorSlot.MUTED)))
        }
    }
}

/** One line of a card: " " and [text] in [style], cut with "…" if too long. */
@Composable
private fun CardLine(text: String, style: TextStyle) {
    BasicText(
        text = " $text",
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
