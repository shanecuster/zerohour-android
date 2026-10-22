//! The display settings screen: reading the current choices and saving a new
//! one.
//!
//! Every value is stored in the database's `settings` table under core's
//! `settings_keys`, in exactly the form the terminal app writes it ("24h",
//! "off", "unicode", ...), so a database opened by either app shows the same
//! choices. Each setter saves at once, like the terminal app's `t`, `i` and
//! `b` keys: there is no separate "save" step.
//!
//! The layout setting (split/fit) is left out: on the phone each list has its
//! own tab, so it does not apply there.

use zerohour_core::clock::TimeFormat as CoreTimeFormat;
use zerohour_core::db::settings_keys;
use zerohour_core::icons::IconMode as CoreIconMode;
use zerohour_core::theme::THEMES;

use crate::database::{Database, IconMode, StoredSettings};
use crate::error::FfiError;
use crate::form::WeekStart;

/// 12- or 24-hour times. Mirror of core's `clock::TimeFormat`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TimeFormat {
    /// "2:00 PM" (the default).
    Hour12,
    /// "14:00".
    Hour24,
}

/// Converts core's time format into the mirror.
impl From<CoreTimeFormat> for TimeFormat {
    /// One arm per format; the compiler checks none is missed.
    fn from(format: CoreTimeFormat) -> TimeFormat {
        match format {
            CoreTimeFormat::Hour12 => TimeFormat::Hour12,
            CoreTimeFormat::Hour24 => TimeFormat::Hour24,
        }
    }
}

/// Converts the mirror back into core's time format.
impl From<TimeFormat> for CoreTimeFormat {
    /// One arm per format; the compiler checks none is missed.
    fn from(format: TimeFormat) -> CoreTimeFormat {
        match format {
            TimeFormat::Hour12 => CoreTimeFormat::Hour12,
            TimeFormat::Hour24 => CoreTimeFormat::Hour24,
        }
    }
}

/// Converts the mirror icon mode back into core's.
impl From<IconMode> for CoreIconMode {
    /// One arm per mode; the compiler checks none is missed.
    fn from(mode: IconMode) -> CoreIconMode {
        match mode {
            IconMode::Nerd => CoreIconMode::Nerd,
            IconMode::Unicode => CoreIconMode::Unicode,
            IconMode::Ascii => CoreIconMode::Ascii,
        }
    }
}

/// The current choices on the settings screen. The list of themes to choose
/// from is `list_themes()`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SettingsScreen {
    /// The chosen theme's name (core's default if none is saved).
    pub theme_name: String,
    /// Which icons are drawn.
    pub icon_mode: IconMode,
    /// 12- or 24-hour times.
    pub time_format: TimeFormat,
    /// The calendar's first day of the week.
    pub week_start: WeekStart,
    /// Whether the banner is shown.
    pub show_banner: bool,
}

// Another `#[uniffi::export] impl` block for `Database`; UniFFI gathers the
// methods from every block into the one Kotlin class.
#[uniffi::export]
impl Database {
    /// The current display settings, with defaults filled in.
    pub fn settings_screen(&self) -> Result<SettingsScreen, FfiError> {
        let db = self.lock()?;
        let stored = StoredSettings::load(&db)?;
        Ok(SettingsScreen {
            theme_name: stored.theme.name.to_string(),
            icon_mode: stored.icon_mode.into(),
            time_format: stored.time_format.into(),
            week_start: crate::items::load_week_start(&db)?.into(),
            show_banner: stored.show_banner,
        })
    }

    /// Saves the theme called `name`. Only names from core's theme table are
    /// accepted: anything else is a bug on the Kotlin side, and saving it
    /// would quietly fall back to the default theme on the next read.
    pub fn set_theme(&self, name: String) -> Result<(), FfiError> {
        if !THEMES.iter().any(|theme| theme.name == name) {
            return Err(FfiError::InvalidInput {
                detail: format!("there is no theme called \"{name}\""),
            });
        }
        self.lock()?.set_setting(settings_keys::THEME, &name)?;
        Ok(())
    }

    /// Saves which icons to draw ("nerd", "unicode" or "ascii").
    pub fn set_icon_mode(&self, mode: IconMode) -> Result<(), FfiError> {
        let mode: CoreIconMode = mode.into();
        self.lock()?
            .set_setting(settings_keys::ICON_MODE, mode.as_str())?;
        Ok(())
    }

    /// Saves 12- or 24-hour times ("12h" or "24h").
    pub fn set_time_format(&self, format: TimeFormat) -> Result<(), FfiError> {
        let format: CoreTimeFormat = format.into();
        self.lock()?
            .set_setting(settings_keys::TIME_FORMAT, format.as_str())?;
        Ok(())
    }

    /// Shows or hides the banner. Stored as "on" or "off", as the terminal
    /// app does.
    pub fn set_show_banner(&self, show: bool) -> Result<(), FfiError> {
        self.lock()?
            .set_setting(settings_keys::BANNER, if show { "on" } else { "off" })?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use zerohour_core::theme::DEFAULT_THEME;

    /// A fresh, empty database in a throwaway file.
    fn empty(name: &str) -> (Arc<Database>, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-settings-{name}.db",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let db = Database::open(path.to_string_lossy().into_owned()).unwrap();
        (db, path)
    }

    #[test]
    fn a_fresh_database_shows_the_defaults() {
        let (db, path) = empty("defaults");
        let screen = db.settings_screen().unwrap();
        assert_eq!(screen.theme_name, DEFAULT_THEME);
        assert_eq!(screen.icon_mode, IconMode::Nerd);
        assert_eq!(screen.time_format, TimeFormat::Hour12);
        assert_eq!(screen.week_start, WeekStart::Sunday);
        assert!(screen.show_banner);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn each_setter_saves_in_the_terminal_apps_words() {
        let (db, path) = empty("setters");
        let other_theme = THEMES[1].name.to_string();
        db.set_theme(other_theme.clone()).unwrap();
        db.set_icon_mode(IconMode::Unicode).unwrap();
        db.set_time_format(TimeFormat::Hour24).unwrap();
        db.set_week_start(WeekStart::Monday).unwrap();
        db.set_show_banner(false).unwrap();

        let screen = db.settings_screen().unwrap();
        assert_eq!(screen.theme_name, other_theme);
        assert_eq!(screen.icon_mode, IconMode::Unicode);
        assert_eq!(screen.time_format, TimeFormat::Hour24);
        assert_eq!(screen.week_start, WeekStart::Monday);
        assert!(!screen.show_banner);

        // The stored text is what the terminal app writes and reads.
        let core = db.lock().unwrap();
        let get = |key| core.get_setting(key).unwrap();
        assert_eq!(get(settings_keys::ICON_MODE).as_deref(), Some("unicode"));
        assert_eq!(get(settings_keys::TIME_FORMAT).as_deref(), Some("24h"));
        assert_eq!(get(settings_keys::WEEK_START).as_deref(), Some("monday"));
        assert_eq!(get(settings_keys::BANNER).as_deref(), Some("off"));
        drop(core);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_unknown_theme_is_refused_and_nothing_changes() {
        let (db, path) = empty("unknown-theme");
        let result = db.set_theme("No Such Theme".to_string());
        assert!(matches!(result, Err(FfiError::InvalidInput { .. })));
        assert_eq!(db.settings_screen().unwrap().theme_name, DEFAULT_THEME);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_demo_database_takes_settings_in_memory() {
        // The demo is in memory, so changing a setting there is harmless and
        // lets the screen be tried out in debug builds.
        let db = Database::open_demo("2026-10-22T09:15:00+02:00".to_string()).unwrap();
        db.set_show_banner(false).unwrap();
        assert!(!db.settings_screen().unwrap().show_banner);
    }
}
