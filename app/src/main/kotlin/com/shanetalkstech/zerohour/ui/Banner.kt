package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import uniffi.zerohour_ffi.Banner
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.RgbColor
import kotlin.math.roundToInt

/**
 * The half-block characters the banner's letters are made of (Nerd and
 * Unicode icon modes), plus the blank between them.
 */
private const val FULL_BLOCK = '█'
private const val UPPER_HALF = '▀'
private const val LOWER_HALF = '▄'
private const val BLANK = ' '

/**
 * The big "zerohour" title and the tagline under it ("7 days to Christmas"),
 * centred, as at the top of the terminal app's main screen, with one blank
 * row above it so the letters do not touch the header row.
 *
 * Everything comes from the Rust side: the letters (half-block characters, or
 * plain ASCII in ASCII icon mode), one gradient colour per column, and the
 * tagline's words. This function only draws them.
 *
 * Its height is the gap row, the letter rows, and one for the tagline (see
 * [bannerRows]).
 */
@Composable
fun BannerView(look: TuiLook, banner: Banner, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(look.cell.rows(1)))
        val fallback = look.color(ColorSlot.ACCENT)
        if (isBlockArt(banner.lines)) {
            BlockLetters(look, banner, fallback)
        } else {
            // ASCII mode: plain characters, drawn as text. Every row has the
            // same width, so centring each one keeps the letters lined up.
            for (line in banner.lines) {
                BasicText(
                    text = gradientLine(line, banner.columnColors, fallback),
                    style = look.text,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        BasicText(
            text = banner.tagline,
            style = look.text.copy(color = look.color(ColorSlot.MUTED)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How many rows [BannerView] takes: the gap, the letters, and the tagline. */
fun bannerRows(banner: Banner): Int = 1 + banner.lines.size + 1

/** How many columns the banner's letters take. */
fun bannerColumns(banner: Banner): Int = banner.lines.firstOrNull()?.let(::cellCount) ?: 0

/**
 * True when every character of [lines] is a half-block piece or a blank, so
 * the letters can be drawn as rectangles by [BlockLetters].
 */
private fun isBlockArt(lines: List<String>): Boolean =
    lines.all { line -> line.all { it == FULL_BLOCK || it == UPPER_HALF || it == LOWER_HALF || it == BLANK } }

/**
 * The half-block letters, painted as filled rectangles on a Canvas instead of
 * as text.
 *
 * As text, each `█` or `▀` glyph is a little shorter than the line it sits on,
 * so thin seams of background showed between the rows of a letter. Here each
 * character becomes the rectangle a terminal would fill: `█` the whole cell,
 * `▀` its top half, `▄` its bottom half, in that column's gradient colour.
 *
 * Every edge is rounded to a whole pixel, and a cell's right (or bottom) edge
 * is computed the same way as its neighbour's left (or top) edge, so the
 * rectangles meet exactly: no gaps and no overlaps.
 */
@Composable
private fun BlockLetters(look: TuiLook, banner: Banner, fallback: Color) {
    val lines = banner.lines
    Canvas(
        modifier = Modifier.size(
            width = look.cell.columns(bannerColumns(banner)),
            height = look.cell.rows(lines.size),
        ),
    ) {
        // Inside the Canvas, sizes are in pixels.
        val cellWidth = look.cell.width.toPx()
        val cellHeight = look.cell.height.toPx()
        // The pixel where column `n` (or row `n`, or half-row) starts.
        fun x(column: Int): Float = (column * cellWidth).roundToInt().toFloat()
        fun y(halfRows: Int): Float = (halfRows * cellHeight / 2).roundToInt().toFloat()

        lines.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                // Which half-rows this character fills, counted from the top
                // of the banner: a cell is half-rows 2*row and 2*row + 1.
                val (fromHalf, toHalf) = when (char) {
                    FULL_BLOCK -> 2 * row to 2 * row + 2
                    UPPER_HALF -> 2 * row to 2 * row + 1
                    LOWER_HALF -> 2 * row + 1 to 2 * row + 2
                    else -> return@forEachIndexed // a blank: nothing to paint
                }
                val left = x(column)
                val top = y(fromHalf)
                drawRect(
                    color = banner.columnColors.getOrNull(column)?.toColor() ?: fallback,
                    topLeft = Offset(left, top),
                    size = Size(x(column + 1) - left, y(toHalf) - top),
                )
            }
        }
    }
}

/**
 * One row of the letters, with each character in its own column's colour.
 *
 * An `AnnotatedString` is text with styles attached to parts of it. The loop
 * walks the row one character (code point) at a time: `codePointAt` reads the
 * character at index `i`, and `Character.charCount` says how many UTF-16 units
 * it took, so `i` moves to the next one. Column `n` gets `colors[n]`; if the
 * list were ever shorter than the row, [fallback] is used.
 */
private fun gradientLine(line: String, colors: List<RgbColor>, fallback: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        var column = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val color = colors.getOrNull(column)?.toColor() ?: fallback
            withStyle(SpanStyle(color = color)) {
                // `Character.toChars` turns the code point back into text.
                append(String(Character.toChars(codePoint)))
            }
            i += Character.charCount(codePoint)
            column++
        }
    }
