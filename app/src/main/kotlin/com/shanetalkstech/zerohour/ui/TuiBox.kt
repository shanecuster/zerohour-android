package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import uniffi.zerohour_ffi.ColorSlot

/**
 * A box with a rounded border and optional titles set into the top edge, like
 * the terminal app's boxes:
 *
 * ```
 * ╭ ☐ Tasks (8) ─────────────╮
 * │ ...content...            │
 * ╰──────────────────────────╯
 * ```
 *
 * The border is drawn as lines on a Compose Canvas rather than with the
 * `╭─╮│╰╯` characters. That way it fits whatever size the box ends up, and it
 * is still placed on the character grid: each line runs through the middle of
 * the outer ring of cells, exactly where the terminal draws its line glyphs.
 *
 * - [borderColor]: the colour of the lines.
 * - [title] and [rightTitle]: text set into the top edge at the left and the
 *   right, already coloured by the caller. Each is drawn on the background
 *   colour, so it "cuts" the line. Like the terminal app's titles, they start
 *   and end with a space so there is a gap on both sides.
 * - [bottomTitle] and [bottomRightTitle]: the same on the bottom edge (the
 *   terminal app shows its status message there). [onBottomRightClick] makes
 *   the bottom-right title tappable (the main screen's "+ add"). A bottom
 *   title too long for the space left of the right one ends in "…".
 * - [paddingCells]: blank columns between the border and the content (the
 *   terminal app uses 1 for the Tasks and Events boxes, 0 for the outer frame).
 * - [content]: what goes inside. `BoxScope.()` means it is placed inside a
 *   `Box`, so it can use `Modifier.align`.
 */
@Composable
fun TuiBox(
    look: TuiLook,
    borderColor: Color,
    modifier: Modifier = Modifier,
    title: AnnotatedString? = null,
    rightTitle: AnnotatedString? = null,
    bottomTitle: AnnotatedString? = null,
    bottomRightTitle: AnnotatedString? = null,
    onBottomRightClick: (() -> Unit)? = null,
    paddingCells: Int = 1,
    content: @Composable BoxScope.() -> Unit,
) {
    val cell = look.cell
    val background = look.color(ColorSlot.BACKGROUND)

    // `drawBehind` paints under everything else in this Box. The lambda runs
    // in a `DrawScope`, which knows the Box's size in pixels.
    Box(
        modifier = modifier.drawBehind {
            drawTuiBorder(borderColor, cell.width.toPx(), cell.height.toPx())
        },
    ) {
        // The content: one cell in from every edge (where the border sits),
        // plus the requested blank columns on the left and right.
        Box(
            modifier = Modifier.padding(
                horizontal = cell.columns(1 + paddingCells),
                vertical = cell.rows(1),
            ),
            content = content,
        )

        // The titles go in the top row, one cell in from the corners. The
        // background colour behind them hides the border line there.
        if (title != null) {
            BasicText(
                text = title,
                style = look.text,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = cell.columns(1))
                    .background(background),
            )
        }
        if (rightTitle != null) {
            BasicText(
                text = rightTitle,
                style = look.text,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = cell.columns(1))
                    .background(background),
            )
        }

        // The bottom edge, the same way. The left title may only use the
        // width the right one leaves free (plus a cell of border between).
        val rightCells = bottomRightTitle?.let { cellCount(it.text) + 1 } ?: 0
        if (bottomTitle != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = cell.columns(1), end = cell.columns(1 + rightCells)),
            ) {
                BasicText(
                    text = bottomTitle,
                    style = look.text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(background),
                )
            }
        }
        if (bottomRightTitle != null) {
            val titleModifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = cell.columns(1))
            // The title on its border background, drawn the same way whether
            // or not it can be tapped.
            val titleText: @Composable () -> Unit = {
                BasicText(
                    text = bottomRightTitle,
                    style = look.text,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.background(background),
                )
            }
            if (onBottomRightClick != null) {
                // One text row is too short for a finger: the tap area is
                // made at least 48dp each way, the text stays where it is.
                LargeTapArea(onClick = onBottomRightClick, modifier = titleModifier, content = titleText)
            } else {
                Box(modifier = titleModifier) { titleText() }
            }
        }
    }
}

/**
 * Draws the rounded border of a [TuiBox], in pixels.
 *
 * The lines run through the centre of the outermost cells: half a cell in from
 * each side and from the top and bottom. The corners are quarter circles half
 * a cell wide, like the `╭` and `╮` glyphs. The line is about as thick as the
 * strokes of the font's own box-drawing characters (15% of a cell's width,
 * and never thinner than one pixel).
 *
 * This is an *extension function* on `DrawScope`: inside it, `size` and the
 * `drawRoundRect` call refer to the canvas being drawn on.
 */
private fun DrawScope.drawTuiBorder(color: Color, cellWidth: Float, cellHeight: Float) {
    val thickness = (cellWidth * 0.15f).coerceAtLeast(1f)
    val left = cellWidth / 2
    val top = cellHeight / 2
    val right = size.width - cellWidth / 2
    val bottom = size.height - cellHeight / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(cellWidth / 2),
        style = Stroke(width = thickness),
    )
}
