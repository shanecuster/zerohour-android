package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// The one kind of "button" the app has: text you can tap, the way a terminal
// app would show a key hint. There are no Material buttons, so everything
// stays on the character grid in the theme's colours.

/**
 * Tappable text, at least [minRows] character rows tall so a finger can hit
 * it, with the text centred vertically in that space.
 *
 * `clickable` (from Compose foundation) makes the area react to taps and
 * shows a light press highlight while it is held.
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
) {
    Box(
        modifier = modifier
            .heightIn(min = look.cell.rows(minRows))
            .clickable(onClick = onClick),
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
