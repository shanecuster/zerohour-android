//! `zerohour-ffi`: the bridge between the Android app (Kotlin) and
//! `zerohour-core` (Rust).
//!
//! ## How the bridge works
//!
//! Everything marked `#[uniffi::export]`, `#[derive(uniffi::Record)]` and so on
//! below becomes callable from Kotlin. At build time a tool called
//! `uniffi-bindgen` reads the compiled library and writes a Kotlin file
//! (`zerohour_ffi.kt`) containing matching classes and functions, so from the
//! Kotlin side it looks like ordinary Kotlin code:
//!
//! ```kotlin
//! val themes = listThemes()          // calls the Rust function below
//! val version = coreVersion()        // calls the Rust function below
//! ```
//!
//! ## What is exported (so far)
//!
//! This is the first slice of the bridge (plan step 2): just the pure data the
//! skeleton app needs to prove the Rust library loads and runs.
//!
//! * **Theme palettes** (`list_themes`, `default_theme_name`) -- the same named
//!   colours as the terminal app, read straight from `zerohour_core::theme`.
//! * **The build version** (`core_version`) -- shown on screen so it is obvious
//!   which Rust build is running.
//!
//! Later steps add the database, the view model and sync. All the real logic
//! lives in `zerohour-core`; the functions here are thin.
//!
//! ## Why mirror types instead of exporting core's types directly?
//!
//! `zerohour-core` knows nothing about UniFFI (it must stay usable by the
//! terminal app too), so it cannot carry UniFFI's `derive` attributes. Instead
//! this crate defines small "mirror" types (`RgbColor`, `ThemePalette`, ...)
//! and converts core's types into them with `From` implementations. The glue
//! stays in this crate and core stays clean.

// This one line generates the low-level plumbing UniFFI needs (the C-compatible
// entry points the Kotlin side calls through). It must appear exactly once, at
// the crate root, before the other exports are used.
uniffi::setup_scaffolding!();

use zerohour_core::theme::{DEFAULT_THEME, Rgb, THEMES, Theme};

// ---------------------------------------------------------------------------
// Theme palettes
// ---------------------------------------------------------------------------

/// One colour as three bytes (0-255). Mirror of core's `Rgb`.
///
/// `#[derive(uniffi::Record)]` makes this a plain data class on the Kotlin side
/// (a `data class RgbColor(val r: UByte, ...)`), copied across the bridge by
/// value.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RgbColor {
    /// Red channel, 0-255.
    pub r: u8,
    /// Green channel, 0-255.
    pub g: u8,
    /// Blue channel, 0-255.
    pub b: u8,
}

/// Converts core's colour type into the mirror type.
///
/// Core's `Rgb` is a tuple struct, so its channels are `.0`, `.1` and `.2`.
impl From<Rgb> for RgbColor {
    /// Copies the three channels across.
    fn from(c: Rgb) -> RgbColor {
        RgbColor {
            r: c.0,
            g: c.1,
            b: c.2,
        }
    }
}

/// The eleven colour slots every zerohour theme fills in. Mirror of core's
/// `Theme` colours (see `zerohour_core::theme::Theme` for what each slot is
/// used for). The names here match core's fields one-to-one.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemePalette {
    /// Background of the whole app.
    pub background: RgbColor,
    /// Normal text.
    pub foreground: RgbColor,
    /// De-emphasized text: hints, secondary columns, empty-state messages.
    pub muted: RgbColor,
    /// Box borders around panels.
    pub border: RgbColor,
    /// Highlights: the header, key hints, focused form field.
    pub accent: RgbColor,
    /// Background of the selected row.
    pub selection: RgbColor,
    /// The Tasks section colour.
    pub task: RgbColor,
    /// The Events section colour.
    pub event: RgbColor,
    /// Good things: done, on time, streaks.
    pub ok: RgbColor,
    /// Attention: due soon, medium priority.
    pub warn: RgbColor,
    /// Problems: overdue, high priority, delete confirmations.
    pub danger: RgbColor,
}

/// Converts one of core's `Theme`s into the mirror palette, slot by slot.
///
/// Takes a reference (`&Theme`) because the themes live in core's `THEMES`
/// constant, which this crate only ever reads.
impl From<&Theme> for ThemePalette {
    /// Converts every slot with `RgbColor::from` (written `.into()` here: the
    /// compiler picks the `From<Rgb> for RgbColor` impl above from context).
    fn from(t: &Theme) -> ThemePalette {
        ThemePalette {
            background: t.bg.into(),
            foreground: t.fg.into(),
            muted: t.muted.into(),
            border: t.border.into(),
            accent: t.accent.into(),
            selection: t.selection.into(),
            task: t.task.into(),
            event: t.event.into(),
            ok: t.ok.into(),
            warn: t.warn.into(),
            danger: t.danger.into(),
        }
    }
}

/// A named theme and its colours, as shown in the theme picker.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeInfo {
    /// Display name, e.g. "Everforest". Also the key the app saves to remember
    /// the user's choice.
    pub name: String,
    /// The theme's colours.
    pub colors: ThemePalette,
}

/// Every built-in theme, in the same order as the terminal app's picker (the
/// order of core's `THEMES` table, with the default theme first) -- the same
/// palettes in the same order, because both apps read that one table in
/// `zerohour-core`.
#[uniffi::export]
pub fn list_themes() -> Vec<ThemeInfo> {
    // `iter()` walks the themes by reference; `map` turns each into a
    // `ThemeInfo`; `collect` gathers them into the `Vec` Kotlin receives.
    THEMES
        .iter()
        .map(|theme| ThemeInfo {
            name: theme.name.to_string(),
            colors: ThemePalette::from(theme),
        })
        .collect()
}

/// The name of the theme used when the user has not chosen one yet.
#[uniffi::export]
pub fn default_theme_name() -> String {
    DEFAULT_THEME.to_string()
}

/// The version of this glue library, shown on the app's first screen so it is
/// obvious which Rust build is running.
///
/// This is the version of `zerohour-ffi` (from its Cargo.toml), not of
/// `zerohour-core`: core does not publish a version string, and this crate's
/// version moves with each change to the bridge, which is what the skeleton
/// screen is there to confirm. `env!` is read while compiling, baking the
/// number into the library.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_every_theme_in_core_order() {
        let themes = list_themes();
        // One entry per theme in core's table, in the same order.
        assert_eq!(themes.len(), THEMES.len());
        for (info, theme) in themes.iter().zip(THEMES.iter()) {
            assert_eq!(info.name, theme.name);
        }
    }

    #[test]
    fn default_theme_is_the_first_one_and_is_listed() {
        // Core guarantees the default is the first table entry.
        assert_eq!(default_theme_name(), THEMES[0].name);
        let names: Vec<String> = list_themes().into_iter().map(|t| t.name).collect();
        assert!(names.contains(&default_theme_name()));
    }

    #[test]
    fn palette_copies_core_channels_exactly() {
        // The first theme's background, converted, must equal core's bytes.
        let info = &list_themes()[0];
        let bg = &info.colors.background;
        let core_bg = THEMES[0].bg;
        assert_eq!((bg.r, bg.g, bg.b), (core_bg.0, core_bg.1, core_bg.2));
    }

    #[test]
    fn core_version_is_not_empty() {
        assert!(!core_version().is_empty());
    }
}
