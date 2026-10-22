package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

// The one kind of "button" the app has: text you can tap, the way a terminal
// app would show a key hint. There are no Material buttons, so everything
// stays on the character grid in the theme's colours.

/**
 * The smallest area a finger can reliably hit: Android's accessibility
 * guideline is 48dp by 48dp.
 */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Tappable text, at least [minRows] character rows tall so a finger can hit
 * it, with the text centred vertically in that space.
 *
 * `clickable` (from Compose foundation) makes the area react to taps and
 * shows a light press highlight while it is held.
 *
 * [largeTarget] makes the area that reacts to a tap at least
 * [MIN_TOUCH_TARGET] each way, without moving or resizing the text (see
 * [LargeTapArea]).
 */
@Composable
fun TapText(
    look: TuiLook,
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    minRows: Int = LINE_ROWS,
    largeTarget: Boolean = false,
) {
    // Both versions draw exactly the same text in exactly the same space;
    // only the area that listens for taps differs.
    if (largeTarget) {
        LargeTapArea(onClick = onClick, modifier = modifier) {
            TapLabel(look, text, color, bold, minRows, Modifier)
        }
    } else {
        TapLabel(look, text, color, bold, minRows, modifier.clickable(onClick = onClick))
    }
}

/**
 * The text of a [TapText], at least [minRows] rows tall and centred
 * vertically in them. Tapping is added by the caller through [modifier].
 */
@Composable
private fun TapLabel(look: TuiLook, text: String, color: Color, bold: Boolean, minRows: Int, modifier: Modifier) {
    Box(
        modifier = modifier.heightIn(min = look.cell.rows(minRows)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            style = look.text.copy(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

/**
 * Draws [content] at its normal size, but reacts to taps in an area at least
 * [MIN_TOUCH_TARGET] wide and tall, centred on it.
 *
 * The text keeps its place on the character grid, because the layout still
 * sees only the size of [content]. The bigger tap area is an invisible box on
 * top that reaches past the edges. Compose finds what was tapped by checking
 * each element's own bounds, not its parent's, so taps on the part that
 * sticks out still land here. This box is drawn after (on top of) the things
 * around it, so it wins where it overlaps them.
 *
 * The press highlight is still drawn over [content] only, not the whole tap
 * area: the invisible box reports its presses to a shared
 * `MutableInteractionSource`, and `indication` on the outer box draws the
 * highlight from those presses.
 */
@Composable
fun LargeTapArea(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // `remember` keeps the same source across redraws, so a press in progress
    // is not lost.
    val presses = remember { MutableInteractionSource() }
    Box(modifier = modifier.indication(presses, LocalIndication.current)) {
        content()
        // `matchParentSize` gives this box the size of [content] as its
        // limits, without changing the outer box's size.
        Box(
            modifier = Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    // A custom layout step: measure the tap area at least
                    // 48dp each way (or the content's size if that is bigger),
                    // report the content's size to the parent, and place the
                    // tap area centred over it (a negative offset when it is
                    // bigger).
                    val minPx = MIN_TOUCH_TARGET.roundToPx()
                    val width = maxOf(constraints.maxWidth, minPx)
                    val height = maxOf(constraints.maxHeight, minPx)
                    val area = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        area.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
                    }
                }
                // No highlight of its own (`indication = null`): see above.
                .clickable(interactionSource = presses, indication = null, onClick = onClick),
        )
    }
}
