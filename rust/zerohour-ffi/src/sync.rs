//! The sync settings screen: the server details, "Test connection" and
//! "Reset sync state".
//!
//! Core's `sync` module does the real work: `SyncSettings` saves the details
//! in the database (everything except the password), `validate` checks them,
//! and `test_connection` asks the server. This file adds the Android app's
//! own rules on top:
//!
//! * **https only.** Core accepts `http://` with a warning (the terminal app
//!   may run on a private network). The phone does not: any URL that is not
//!   `https://` is refused before anything is saved or sent. Android's own
//!   "no plain-text traffic" setting would not catch it, because the Rust code
//!   opens its own network connections.
//! * **The demo database never syncs.** Every call here refuses to work on it.
//! * **The password is never stored here.** Kotlin keeps it, encrypted with
//!   the Android Keystore, and passes it in for each call that needs it. It is
//!   never saved, logged or put into an error message (core's messages never
//!   contain it either).
//!
//! Secure connections use the root certificates built into the app (Mozilla's
//! list, through `rustls`), not the phone's: servers with self-signed or
//! private-CA certificates are not supported.

use zerohour_core::sync::{self, SyncError, SyncSettings};

use crate::database::{Database, parse_now};
use crate::error::FfiError;

/// The sync details the user types in (everything except the password).
/// Mirror of core's `SyncSettings`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncConfig {
    /// Sync on or off. Off by default.
    pub enabled: bool,
    /// The server address, e.g. `https://couch.example.org`.
    pub url: String,
    /// The database name on the server.
    pub database: String,
    /// The user to log in as.
    pub username: String,
}

/// Converts core's settings into the mirror.
impl From<SyncSettings> for SyncConfig {
    /// Moves the fields across.
    fn from(s: SyncSettings) -> SyncConfig {
        SyncConfig {
            enabled: s.enabled,
            url: s.url,
            database: s.database,
            username: s.username,
        }
    }
}

/// Converts the mirror into core's settings, trimming stray spaces the way
/// the terminal app's form does.
impl From<SyncConfig> for SyncSettings {
    /// Copies the fields across, trimmed.
    fn from(c: SyncConfig) -> SyncSettings {
        SyncSettings {
            enabled: c.enabled,
            url: c.url.trim().to_string(),
            database: c.database.trim().to_string(),
            username: c.username.trim().to_string(),
        }
    }
}

/// What the sync settings screen shows when it opens.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncScreen {
    /// The saved details (empty and off if never saved).
    pub config: SyncConfig,
    /// How many documents this device has synced so far.
    pub synced_docs: u64,
    /// True for the demo database, which never syncs (the screen greys out).
    pub demo: bool,
}

/// The answer to "Test connection".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConnectionResult {
    /// Documents in the server's database right now.
    pub doc_count: u64,
    /// How many seconds this phone's clock is ahead (+) or behind (-) the
    /// server's, when it is more than a minute off; `None` otherwise.
    pub clock_skew_secs: Option<i64>,
}

/// Turns core's sync error into the one Kotlin sees, keeping core's words
/// (they say what to fix, and never contain the password).
impl From<SyncError> for FfiError {
    /// Wraps the message.
    fn from(error: SyncError) -> FfiError {
        FfiError::Sync {
            detail: error.to_string(),
        }
    }
}

/// The error for anything tried on the demo database.
fn demo_refused() -> FfiError {
    FfiError::Sync {
        detail: "the demo database never syncs".to_string(),
    }
}

/// Refuses any server address that is not `https://`. An empty address is
/// left for core's own check, which says "enter the server URL".
fn require_https(settings: &SyncSettings) -> Result<(), FfiError> {
    let url = settings.normalized_url().to_ascii_lowercase();
    if !url.is_empty() && !url.starts_with("https://") {
        return Err(FfiError::Sync {
            detail: "the server URL must start with https://".to_string(),
        });
    }
    Ok(())
}

/// The full check before a connection is made: https only, then core's
/// checks (a host name, a valid database name, a username...).
fn check_for_connection(settings: &SyncSettings) -> Result<(), FfiError> {
    require_https(settings)?;
    settings.validate()?;
    Ok(())
}

/// The name a password is stored under: "user @ server/database", core's
/// rule. Kotlin saves the encrypted password together with this name and only
/// uses it while the name still matches, so a password saved for one server
/// is never sent to another after the address is changed.
#[uniffi::export]
pub fn sync_account(config: SyncConfig) -> String {
    SyncSettings::from(config).account()
}

// Another `#[uniffi::export] impl` block for `Database`.
#[uniffi::export]
impl Database {
    /// The saved sync details and how many documents have been synced.
    pub fn sync_screen(&self) -> Result<SyncScreen, FfiError> {
        let db = self.lock()?;
        Ok(SyncScreen {
            config: SyncSettings::load(&db)?.into(),
            synced_docs: db.synced_doc_count()?,
            demo: self.is_demo(),
        })
    }

    /// Saves the sync details (not the password; Kotlin keeps that).
    ///
    /// A non-https address is always refused. With sync switched on, the
    /// details must also pass core's checks; switched off, half-filled
    /// details may be kept for later (the terminal app's rule).
    pub fn save_sync_settings(&self, config: SyncConfig) -> Result<(), FfiError> {
        if self.is_demo() {
            return Err(demo_refused());
        }
        let settings = SyncSettings::from(config);
        require_https(&settings)?;
        if settings.enabled {
            settings.validate()?;
        }
        // `&*`: borrow the `Db` inside the lock guard (core wants a `&Db`).
        settings.save(&*self.lock()?)?;
        Ok(())
    }

    /// "Test connection": checks that the server answers, the login works
    /// and the database exists, using these (possibly unsaved) details and
    /// `password`. `now` is the phone's time (see `NOW_FORMAT`), compared
    /// with the server's clock.
    ///
    /// This waits for the network (up to core's timeout), so Kotlin calls it
    /// on a background thread. It does not touch the local database, so it
    /// does not hold the database lock while it waits.
    pub fn test_connection(
        &self,
        config: SyncConfig,
        password: String,
        now: String,
    ) -> Result<ConnectionResult, FfiError> {
        if self.is_demo() {
            return Err(demo_refused());
        }
        let now = parse_now(&now)?.utc;
        let settings = SyncSettings::from(config);
        check_for_connection(&settings)?;
        if password.is_empty() {
            return Err(FfiError::Sync {
                detail: "enter the password".to_string(),
            });
        }
        let info = sync::test_connection(&settings, &password, now)?;
        Ok(ConnectionResult {
            doc_count: info.doc_count,
            clock_skew_secs: info.clock_skew_secs,
        })
    }

    /// "Reset sync state": forgets which versions the server has. The tasks
    /// and events are not touched; the next sync re-checks everything (safe,
    /// because applying the same version twice changes nothing).
    pub fn reset_sync_state(&self) -> Result<(), FfiError> {
        if self.is_demo() {
            return Err(demo_refused());
        }
        self.lock()?.clear_sync_state()?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// A fixed "now" for the tests.
    const NOW: &str = "2026-10-22T09:15:00+02:00";

    /// A fresh, empty database in a throwaway file.
    fn empty(name: &str) -> (Arc<Database>, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-sync-{name}.db",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let db = Database::open(path.to_string_lossy().into_owned()).unwrap();
        (db, path)
    }

    /// Complete, valid details for a made-up server (the `.invalid` top-level
    /// name is reserved and never resolves, so nothing here reaches a network).
    fn config(url: &str) -> SyncConfig {
        SyncConfig {
            enabled: true,
            url: url.to_string(),
            database: "notes".to_string(),
            username: "someone".to_string(),
        }
    }

    /// The `detail` of a `Sync` error, or a test failure.
    fn sync_detail(result: Result<impl std::fmt::Debug, FfiError>) -> String {
        match result {
            Err(FfiError::Sync { detail }) => detail,
            other => panic!("expected a sync error, got {other:?}"),
        }
    }

    #[test]
    fn details_are_saved_and_read_back_trimmed() {
        let (db, path) = empty("save");
        let mut c = config("  https://couch.example.invalid/  ");
        c.username = " someone ".to_string();
        db.save_sync_settings(c).unwrap();
        let screen = db.sync_screen().unwrap();
        assert_eq!(screen.config.url, "https://couch.example.invalid");
        assert_eq!(screen.config.username, "someone");
        assert!(screen.config.enabled);
        assert_eq!(screen.synced_docs, 0);
        assert!(!screen.demo);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn plain_http_is_refused_before_saving_even_when_off() {
        let (db, path) = empty("http");
        for url in [
            "http://couch.example.invalid",
            "HTTP://couch.example.invalid",
        ] {
            let mut c = config(url);
            c.enabled = false;
            let detail = sync_detail(db.save_sync_settings(c));
            assert!(detail.contains("https://"), "{detail}");
        }
        // Nothing was saved.
        assert_eq!(db.sync_screen().unwrap().config.url, "");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn plain_http_is_refused_before_testing() {
        let (db, path) = empty("http-test");
        let detail = sync_detail(db.test_connection(
            config("http://couch.example.invalid"),
            "secret".to_string(),
            NOW.to_string(),
        ));
        assert!(detail.contains("https://"), "{detail}");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn switched_off_half_filled_details_may_be_kept() {
        let (db, path) = empty("half");
        let half = SyncConfig {
            enabled: false,
            url: String::new(),
            database: String::new(),
            username: "someone".to_string(),
        };
        db.save_sync_settings(half).unwrap();
        // Switched on, the same details are refused with core's message.
        let mut on = db.sync_screen().unwrap().config;
        on.enabled = true;
        assert_eq!(
            sync_detail(db.save_sync_settings(on)),
            "enter the server URL"
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_connection_checks_the_details_and_password_first() {
        let (db, path) = empty("checks");
        let mut bad_name = config("https://couch.example.invalid");
        bad_name.database = "Notes".to_string();
        let detail = sync_detail(db.test_connection(bad_name, "x".into(), NOW.into()));
        assert!(detail.contains("database names"), "{detail}");

        let detail = sync_detail(db.test_connection(
            config("https://couch.example.invalid"),
            String::new(),
            NOW.into(),
        ));
        assert_eq!(detail, "enter the password");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn error_messages_never_contain_the_password() {
        let (db, path) = empty("no-leak");
        let password = "correct-horse-battery";
        // A refused URL and a refused database name: neither message may
        // repeat the password.
        let mut c = config("http://couch.example.invalid");
        let first = sync_detail(db.test_connection(c.clone(), password.into(), NOW.into()));
        c.url = "https://couch.example.invalid".to_string();
        c.database = "Bad Name".to_string();
        let second = sync_detail(db.test_connection(c, password.into(), NOW.into()));
        assert!(!first.contains(password) && !second.contains(password));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_demo_database_refuses_every_sync_call() {
        let db = Database::open_demo(NOW.to_string()).unwrap();
        assert!(db.sync_screen().unwrap().demo);
        let c = config("https://couch.example.invalid");
        assert_eq!(
            sync_detail(db.save_sync_settings(c.clone())),
            "the demo database never syncs"
        );
        assert_eq!(
            sync_detail(db.test_connection(c, "x".into(), NOW.into())),
            "the demo database never syncs"
        );
        assert_eq!(
            sync_detail(db.reset_sync_state()),
            "the demo database never syncs"
        );
    }

    #[test]
    fn reset_keeps_the_data_and_the_details() {
        let (db, path) = empty("reset");
        db.save_sync_settings(config("https://couch.example.invalid"))
            .unwrap();
        db.reset_sync_state().unwrap();
        assert_eq!(
            db.sync_screen().unwrap().config.url,
            "https://couch.example.invalid"
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_password_account_names_user_server_and_database() {
        let account = sync_account(config(" https://couch.example.invalid/ "));
        assert_eq!(account, "someone @ https://couch.example.invalid/notes");
    }
}
