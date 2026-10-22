package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import uniffi.zerohour_ffi.Banner
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.RgbColor

/**
 * The big "zerohour" title and the tagline under it ("7 days to Christmas"),
 * centred, as at the top of the terminal app's main screen.
 *
 * Everything comes from the Rust side: the letters (half-block characters, or
 * plain ASCII in ASCII icon mode), one gradient colour per column, and the
 * tagline's words. This function only draws them.
 *
 * Its height is the number of letter rows plus one for the tagline (see
 * [bannerRows]).
 */
@Composable
fun BannerView(look: TuiLook, banner: Banner, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Every row has the same width, so centring each one keeps the
        // letters lined up.
        for (line in banner.lines) {
            BasicText(
                text = gradientLine(line, banner.columnColors, fallback = look.color(ColorSlot.ACCENT)),
                style = look.text,
                maxLines = 1,
                softWrap = false,
            )
        }
        BasicText(
            text = banner.tagline,
            style = look.text.copy(color = look.color(ColorSlot.MUTED)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How many rows [BannerView] takes: the letters plus one for the tagline. */
fun bannerRows(banner: Banner): Int = banner.lines.size + 1

/** How many columns the banner's letters take. */
fun bannerColumns(banner: Banner): Int = banner.lines.firstOrNull()?.let(::cellCount) ?: 0

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
