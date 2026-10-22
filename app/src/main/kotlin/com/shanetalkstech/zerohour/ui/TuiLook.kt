package com.shanetalkstech.zerohour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.RgbColor
import uniffi.zerohour_ffi.ThemeInfo
import uniffi.zerohour_ffi.ThemePalette
import uniffi.zerohour_ffi.defaultThemeName
import uniffi.zerohour_ffi.listThemes

// The building blocks of the terminal look: one monospace font, a grid of
// equal character cells, and colours that only ever come from the theme.

/**
 * The bundled JetBrainsMono Nerd Font Mono, regular and bold (see
 * `third_party/fonts/README.md`). Every piece of text in the app uses it, so
 * icons and box-drawing characters look the same on every phone.
 */
val ZerohourFont: FontFamily = FontFamily(
    Font(R.font.jetbrainsmono_nerd_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_nerd_mono_bold, FontWeight.Bold),
)

/**
 * The text size of the whole app. At 15sp the font's characters are 9dp wide,
 * which gives about 40 to 45 columns on a phone held upright: the target in
 * the plan. It is in `sp`, so it grows with the phone's font size setting.
 */
private val TEXT_SIZE = 15.sp

/**
 * The text style everything starts from (colours are added where it is used).
 *
 * `fontFeatureSettings` switches off ligatures (`liga`, `calt`): JetBrains Mono
 * would otherwise draw pairs like `->` or `!=` as one joined symbol, and a
 * terminal shows every character in its own cell.
 */
val BaseTextStyle: TextStyle = TextStyle(
    fontFamily = ZerohourFont,
    fontSize = TEXT_SIZE,
    fontFeatureSettings = "liga 0, calt 0",
)

/**
 * The size of one character cell, like one cell of a terminal.
 *
 * Every column width and row height on screen is a whole number of cells, so
 * text, icons and box borders line up the way they do in the terminal app.
 * `@Immutable` promises Compose the values never change after creation, which
 * lets it skip redrawing parts whose inputs are equal.
 */
@Immutable
data class CellMetrics(
    /** Width of one character. */
    val width: Dp,
    /** Height of one line of text. */
    val height: Dp,
) {
    /** The width of [count] cells side by side. */
    fun columns(count: Int): Dp = width * count

    /** The height of [count] lines stacked. */
    fun rows(count: Int): Dp = height * count
}

/**
 * Measures the character cell of [style].
 *
 * A `TextMeasurer` lays text out without drawing it. Measuring 100 characters
 * and dividing by 100 gives the exact width of one: measuring a single
 * character would be rounded to whole pixels, and that small error would add
 * up across a row. `remember` keeps the result until the style or the screen
 * density changes.
 */
@Composable
fun rememberCellMetrics(style: TextStyle): CellMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) {
        val sample = measurer.measure("M".repeat(100), style, softWrap = false)
        // `with(density)` gives access to the pixel <-> dp conversions.
        with(density) {
            CellMetrics(
                width = (sample.size.width / 100f).toDp(),
                height = sample.size.height.toDp(),
            )
        }
    }
}

/**
 * The number of cells [text] takes: one per character.
 *
 * `codePointCount` counts characters the way a person would. Plain `length`
 * counts UTF-16 units, where some characters take two. Every glyph zerohour
 * shows is one cell wide (the font is a *Mono* Nerd Font), so characters equal
 * cells.
 */
fun cellCount(text: String): Int = text.codePointCount(0, text.length)

/**
 * Turns a colour from the Rust side into a Compose [Color].
 *
 * Rust sends each channel as an unsigned byte (`UByte`, 0..255); Compose's
 * `Color(red, green, blue)` wants `Int`s in the same range.
 */
fun RgbColor.toColor(): Color = Color(r.toInt(), g.toInt(), b.toInt())

/**
 * The colour this palette gives [slot]. This lookup is the only place the app
 * turns a slot name into a colour, so the UI never hard-codes one. The `when`
 * lists every slot, so a new slot added in Rust won't compile until it is
 * handled here.
 */
fun ThemePalette.color(slot: ColorSlot): Color = when (slot) {
    ColorSlot.BACKGROUND -> background
    ColorSlot.FOREGROUND -> foreground
    ColorSlot.MUTED -> muted
    ColorSlot.BORDER -> border
    ColorSlot.ACCENT -> accent
    ColorSlot.SELECTION -> selection
    ColorSlot.TASK -> task
    ColorSlot.EVENT -> event
    ColorSlot.OK -> ok
    ColorSlot.WARN -> warn
    ColorSlot.DANGER -> danger
}.toColor()

/**
 * The theme used before the database has been read (the loading screen and
 * error messages): core's default theme, found by name in core's list, or the
 * first theme in the list as a fallback.
 */
fun defaultTheme(): ThemeInfo {
    val themes = listThemes()
    val defaultName = defaultThemeName()
    return themes.firstOrNull { it.name == defaultName } ?: themes.first()
}

/**
 * Everything a component needs to draw in the terminal style: the colours,
 * the base text style, and the cell size. It is passed down as one parameter so
 * each drawing function's inputs are spelled out in its signature.
 */
@Immutable
data class TuiLook(
    /** The current theme's colours. */
    val palette: ThemePalette,
    /** The base text style (font, size, no ligatures). */
    val text: TextStyle,
    /** The size of one character cell. */
    val cell: CellMetrics,
) {
    /** Shortcut: the colour for [slot] in the current theme. */
    fun color(slot: ColorSlot): Color = palette.color(slot)
}
