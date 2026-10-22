package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.ColorSlot

// The bar at the bottom of the main screen that switches between the three
// views. The terminal app has no tab bar (its Tab key flips between the main
// screen and the stats screen); this one is drawn in the same style as the
// app's boxes:
//
//   ╭────────────┬────────────┬────────────╮
//   │  ☐ Tasks   │  ◷ Events  │  ▤ Stats   │
//   ╰────────────┴────────────┴────────────╯

/** The three views the tab bar switches between. */
enum class Tab {
    /** The Tasks box. */
    TASKS,

    /** The Events box. */
    EVENTS,

    /** The stats screen. */
    STATS,
}

/** The tab bar is three character rows tall: border, labels, border. */
const val TAB_BAR_ROWS = 3

/**
 * The tab bar, [columns] character cells wide (the whole screen).
 *
 * Each tab is an equal share of the width between the outer border and the
 * two dividers; the last one also takes the part of a cell left over at the
 * right edge. A tab reacts to taps over its whole height, borders included,
 * so it is about 60dp tall: easy to hit without enlarging anything.
 *
 * The [selected] tab's label is drawn in the accent colour and bold, the
 * others muted (the terminal app's style for the chosen option). The icons
 * come from core: [taskIcon], [eventIcon] and [statsIcon]. [onSelect] runs
 * with the tab that was tapped.
 */
@Composable
fun TabBar(
    look: TuiLook,
    columns: Int,
    selected: Tab,
    taskIcon: String,
    eventIcon: String,
    statsIcon: String,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell = look.cell
    val borderColor = look.color(ColorSlot.BORDER)
    // The cells between the left and right border, minus the two dividers,
    // shared out between the three tabs.
    val tabCells = maxOf((columns - 4) / 3, 1)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(cell.rows(TAB_BAR_ROWS))
            .drawBehind {
                val cellWidth = cell.width.toPx()
                val cellHeight = cell.height.toPx()
                drawTuiBorder(borderColor, cellWidth, cellHeight)
                // The two dividers: vertical lines through the middle of the
                // cell after the first and second tab, from the top border
                // line to the bottom one, where they make the ┬ and ┴ joins.
                val thickness = (cellWidth * 0.15f).coerceAtLeast(1f)
                for (divider in 1..2) {
                    // Cells before this divider: the left border plus
                    // `divider` tabs plus the dividers before it.
                    val cellsBefore = 1 + divider * tabCells + (divider - 1)
                    val x = (cellsBefore + 0.5f) * cellWidth
                    drawLine(
                        color = borderColor,
                        start = Offset(x, cellHeight / 2),
                        end = Offset(x, size.height - cellHeight / 2),
                        strokeWidth = thickness,
                    )
                }
            },
    ) {
        // The left border's column.
        Spacer(Modifier.width(cell.columns(1)))
        TabCell(
            look = look,
            label = "$taskIcon ${stringResource(R.string.tasks)}",
            isSelected = selected == Tab.TASKS,
            onClick = { onSelect(Tab.TASKS) },
            modifier = Modifier.width(cell.columns(tabCells)),
        )
        Spacer(Modifier.width(cell.columns(1)))
        TabCell(
            look = look,
            label = "$eventIcon ${stringResource(R.string.events)}",
            isSelected = selected == Tab.EVENTS,
            onClick = { onSelect(Tab.EVENTS) },
            modifier = Modifier.width(cell.columns(tabCells)),
        )
        Spacer(Modifier.width(cell.columns(1)))
        TabCell(
            look = look,
            label = "$statsIcon ${stringResource(R.string.stats)}",
            isSelected = selected == Tab.STATS,
            onClick = { onSelect(Tab.STATS) },
            // `weight(1f)`: everything that is left, less the right border.
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(cell.columns(1)))
    }
}

/**
 * One tab: its [label] centred in a column the full height of the bar, which
 * runs [onClick] when tapped. [modifier] sets its width.
 */
@Composable
private fun TabCell(
    look: TuiLook,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val style = if (isSelected) {
        look.text.copy(color = look.color(ColorSlot.ACCENT), fontWeight = FontWeight.Bold)
    } else {
        look.text.copy(color = look.color(ColorSlot.MUTED))
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
