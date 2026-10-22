//! The database handle Kotlin holds, and the display settings stored in it.
//!
//! Two ways to get one:
//!
//! * [`Database::open`] -- the real database, a SQLite file in the app's
//!   private storage. Kotlin chooses the path; this crate never decides where
//!   files live.
//! * [`Database::open_demo`] -- a database that lives only in memory, filled
//!   with core's sample data (`zerohour_core::demo`). Only debug builds of the
//!   app call it. It is never written to disk and never synced, so demo data
//!   can never reach the real database or a server.
//!
//! ## Dates across the bridge
//!
//! Kotlin passes "now" as text in one fixed shape, `2026-10-22T14:30:00` (the
//! local wall-clock time, no time zone, see [`NOW_FORMAT`]), and this crate
//! parses it. Core never reads the clock itself (so its tests can pretend it is
//! any moment), and following the same rule here keeps the Rust side testable
//! too.

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use chrono::NaiveDateTime;
use zerohour_core::db::{Db, settings_keys};
use zerohour_core::icons::IconMode as CoreIconMode;
use zerohour_core::theme::{DEFAULT_THEME, Theme, theme_by_name};

use crate::ThemeInfo;
use crate::error::FfiError;

/// The shape of the "now" text Kotlin sends: ISO 8601 local date and time,
/// to the second, e.g. `2026-10-22T14:30:00`. In chrono's format language,
/// `%Y-%m-%d` is the date, `T` is a literal letter, and `%H:%M:%S` is the
/// 24-hour time. Kotlin formats with the matching pattern
/// `yyyy-MM-dd'T'HH:mm:ss`.
pub const NOW_FORMAT: &str = "%Y-%m-%dT%H:%M:%S";

/// Turns the "now" text from Kotlin into a date-time, or explains what is wrong
/// with it. Shared by every export that takes a `now`.
pub(crate) fn parse_now(now: &str) -> Result<NaiveDateTime, FfiError> {
    NaiveDateTime::parse_from_str(now, NOW_FORMAT).map_err(|e| FfiError::InvalidInput {
        detail: format!("\"{now}\" is not a local date-time like 2026-10-22T14:30:00 ({e})"),
    })
}

// ---------------------------------------------------------------------------
// Display settings
// ---------------------------------------------------------------------------

/// Which set of icon glyphs to draw. Mirror of core's `IconMode`.
///
/// `#[derive(uniffi::Enum)]` makes this a Kotlin `enum class IconMode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum IconMode {
    /// Nerd Font icons. The app bundles a Nerd Font, so these always render.
    Nerd,
    /// Plain Unicode symbols such as `☐` and `◷`.
    Unicode,
    /// Plain ASCII such as `[ ]` and `(o)`.
    Ascii,
}

/// Converts core's icon mode into the mirror.
impl From<CoreIconMode> for IconMode {
    /// One arm per mode; the compiler checks none is missed.
    fn from(mode: CoreIconMode) -> IconMode {
        match mode {
            CoreIconMode::Nerd => IconMode::Nerd,
            CoreIconMode::Unicode => IconMode::Unicode,
            CoreIconMode::Ascii => IconMode::Ascii,
        }
    }
}

/// How the main screen shares its height between the Tasks and Events boxes.
/// The same two choices as the terminal app's `v` key.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LayoutMode {
    /// Tasks and Events get equal halves (the default).
    Split,
    /// The Events box shrinks to fit its rows; Tasks get the rest.
    Fit,
}

/// The settings that change how the main screen looks, as Kotlin sees them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DisplaySettings {
    /// The chosen theme with its colours (the default theme if none is saved,
    /// or if the saved name is unknown).
    pub theme: ThemeInfo,
    /// Which icons to draw.
    pub icon_mode: IconMode,
    /// Whether the big "zerohour" banner and its tagline are shown.
    pub show_banner: bool,
    /// How Tasks and Events share the height.
    pub layout: LayoutMode,
}

/// The same settings in core's own types, for use inside this crate (the view
/// functions in core want a `CoreIconMode`, not the mirror).
pub(crate) struct StoredSettings {
    /// The theme, borrowed from core's built-in table.
    pub theme: &'static Theme,
    /// Which icons to draw.
    pub icon_mode: CoreIconMode,
    /// Whether the banner is shown.
    pub show_banner: bool,
    /// How Tasks and Events share the height.
    pub layout: LayoutMode,
}

impl StoredSettings {
    /// Reads every display setting from the database's `settings` table,
    /// filling in a default for anything missing or unrecognised.
    ///
    /// The defaults match the terminal app, except the icon mode: the terminal
    /// app asks on first run because it cannot know the terminal's font, but
    /// the Android app bundles a Nerd Font, so Nerd is always safe.
    pub(crate) fn load(db: &Db) -> Result<StoredSettings, FfiError> {
        // Each `get_setting` returns `Option<String>`: `None` when never saved.
        let theme_name = db.get_setting(settings_keys::THEME)?;
        let icon_mode = db.get_setting(settings_keys::ICON_MODE)?;
        let banner = db.get_setting(settings_keys::BANNER)?;
        let layout = db.get_setting(settings_keys::LAYOUT)?;

        Ok(StoredSettings {
            // `theme_by_name` falls back to the default theme for unknown names.
            // `as_deref()` turns `Option<String>` into `Option<&str>`.
            theme: theme_by_name(theme_name.as_deref().unwrap_or(DEFAULT_THEME)),
            // `and_then` runs the parser only when a value was saved.
            icon_mode: icon_mode
                .as_deref()
                .and_then(CoreIconMode::parse)
                .unwrap_or(CoreIconMode::Nerd),
            // The terminal app stores "on" or "off"; only "off" hides it.
            show_banner: banner.as_deref() != Some("off"),
            // "fit" picks Fit; anything else (including nothing) is Split.
            layout: if layout.as_deref() == Some("fit") {
                LayoutMode::Fit
            } else {
                LayoutMode::Split
            },
        })
    }

    /// The Kotlin-facing copy of these settings.
    pub(crate) fn to_display(&self) -> DisplaySettings {
        DisplaySettings {
            theme: ThemeInfo::from(self.theme),
            icon_mode: self.icon_mode.into(),
            show_banner: self.show_banner,
            layout: self.layout,
        }
    }
}

// ---------------------------------------------------------------------------
// The database object
// ---------------------------------------------------------------------------

/// A handle to a zerohour database, usable from Kotlin.
///
/// `#[derive(uniffi::Object)]` marks this as an *object*: Kotlin holds a
/// reference to it (not a copy) and calls methods on it. It lives as long as
/// Kotlin keeps a reference.
///
/// ## Why the `Mutex`
///
/// Kotlin may call methods from several threads at once, so UniFFI requires
/// every object to be safe to share between threads. Core's `Db` wraps a
/// SQLite connection, which can be *moved* to another thread but must not be
/// *used* by two threads at the same instant. A `Mutex` ("mutual exclusion")
/// fixes that: a thread must `lock()` it before touching what is inside, and
/// other threads wait their turn.
#[derive(uniffi::Object)]
pub struct Database {
    /// Core's database handle, behind the lock.
    db: Mutex<Db>,
    /// True for the in-memory demo database. Later steps check it so demo data
    /// is never synced.
    demo: bool,
}

/// Helpers used only inside this crate (not exported to Kotlin).
impl Database {
    /// Takes the lock and returns a guard giving access to the `Db`.
    ///
    /// The guard unlocks automatically when it goes out of scope. `lock()`
    /// returns a `Result` because a lock is "poisoned" if a thread panicked
    /// while holding it; that is reported as an error instead of crashing.
    pub(crate) fn lock(&self) -> Result<MutexGuard<'_, Db>, FfiError> {
        self.db.lock().map_err(|_| FfiError::Database {
            detail: "the database lock was poisoned by an earlier crash".to_string(),
        })
    }

    /// Wraps an open `Db` in the lock and the shared pointer Kotlin receives.
    fn wrap(db: Db, demo: bool) -> Arc<Database> {
        Arc::new(Database {
            db: Mutex::new(db),
            demo,
        })
    }
}

#[uniffi::export]
impl Database {
    /// Opens (or creates) the database file at `path` and brings its schema up
    /// to date. On Android the app passes a file in its private storage.
    ///
    /// Returns an `Arc<Database>`: UniFFI objects are handed to Kotlin through
    /// `Arc`, Rust's thread-safe reference-counted pointer. The object is freed
    /// when the last reference (Kotlin's or Rust's) goes away.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Database>, FfiError> {
        // `Path::new(&path)` views the text as a file path without copying it.
        // `?` converts a `DbError` with `From<DbError> for FfiError`.
        let db = Db::open(Path::new(&path))?;
        Ok(Database::wrap(db, false))
    }

    /// Opens a fresh in-memory database filled with core's demo data, dated
    /// relative to `now` (see [`NOW_FORMAT`]). For debug builds only.
    ///
    /// `chrono::Local` is the phone's time zone. Core's seeder uses it to turn
    /// the sample local times into the UTC timestamps the database stores, so
    /// each sample completion lands on the intended local day.
    #[uniffi::constructor]
    pub fn open_demo(now: String) -> Result<Arc<Database>, FfiError> {
        let now = parse_now(&now)?;
        let db = Db::open_in_memory()?;
        zerohour_core::demo::seed(&db, now, &chrono::Local)?;
        Ok(Database::wrap(db, true))
    }

    /// True for the in-memory demo database.
    pub fn is_demo(&self) -> bool {
        self.demo
    }

    /// The display settings saved in this database, with defaults filled in.
    pub fn display_settings(&self) -> Result<DisplaySettings, FfiError> {
        let db = self.lock()?;
        Ok(StoredSettings::load(&db)?.to_display())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use zerohour_core::theme::THEMES;

    /// A path for a throwaway database file, unique to this test run so tests
    /// running at the same time never share a file.
    fn temp_db_path(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-{name}.db",
            std::process::id()
        ))
    }

    #[test]
    fn now_must_be_a_full_iso_local_date_time() {
        assert!(parse_now("2026-10-22T14:30:00").is_ok());
        for bad in ["", "2026-10-22", "2026-10-22T14:30", "22/10/2026 14:30:00"] {
            let error = parse_now(bad).unwrap_err();
            assert!(
                matches!(error, FfiError::InvalidInput { .. }),
                "{bad:?} gave {error}"
            );
        }
    }

    #[test]
    fn demo_database_is_flagged_and_uses_android_defaults() {
        let db = Database::open_demo("2026-10-22T09:15:00".to_string()).unwrap();
        assert!(db.is_demo());
        let settings = db.display_settings().unwrap();
        assert_eq!(settings.theme.name, DEFAULT_THEME);
        assert_eq!(settings.icon_mode, IconMode::Nerd);
        assert!(settings.show_banner);
        assert_eq!(settings.layout, LayoutMode::Split);
    }

    #[test]
    fn demo_rejects_a_bad_now() {
        assert!(Database::open_demo("yesterday".to_string()).is_err());
    }

    #[test]
    fn file_database_reads_saved_settings() {
        let path = temp_db_path("settings");
        // Save settings the way the terminal app would, through core.
        {
            let core = Db::open(&path).unwrap();
            core.set_setting(settings_keys::THEME, "nord").unwrap();
            core.set_setting(settings_keys::ICON_MODE, "ascii").unwrap();
            core.set_setting(settings_keys::BANNER, "off").unwrap();
            core.set_setting(settings_keys::LAYOUT, "fit").unwrap();
        }
        let db = Database::open(path.to_string_lossy().into_owned()).unwrap();
        assert!(!db.is_demo());
        let settings = db.display_settings().unwrap();
        // Theme names match without regard to upper/lower case.
        assert_eq!(settings.theme.name, "Nord");
        assert_eq!(settings.icon_mode, IconMode::Ascii);
        assert!(!settings.show_banner);
        assert_eq!(settings.layout, LayoutMode::Fit);
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn unknown_saved_values_fall_back_to_defaults() {
        let path = temp_db_path("unknown");
        {
            let core = Db::open(&path).unwrap();
            core.set_setting(settings_keys::THEME, "no such theme")
                .unwrap();
            core.set_setting(settings_keys::ICON_MODE, "emoji").unwrap();
        }
        let settings = Database::open(path.to_string_lossy().into_owned())
            .unwrap()
            .display_settings()
            .unwrap();
        assert_eq!(settings.theme.name, THEMES[0].name);
        assert_eq!(settings.icon_mode, IconMode::Nerd);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_file_that_is_not_a_database_is_a_database_error() {
        let path = temp_db_path("garbage");
        std::fs::write(&path, b"this is not sqlite, just text that is long enough").unwrap();
        // `.err()` keeps only the error; `Arc<Database>` has no `Debug` to print.
        let error = Database::open(path.to_string_lossy().into_owned()).err();
        assert!(matches!(error, Some(FfiError::Database { .. })));
        let _ = std::fs::remove_file(&path);
    }
}
