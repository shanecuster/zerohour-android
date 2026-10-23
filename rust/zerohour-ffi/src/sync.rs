//! Sync: the settings page (server details, "Test connection", "Reset sync
//! state"), the status the header and the page show, and "Sync now".
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
//! * **One sync at a time.** A second "Sync now" while one runs is refused,
//!   never queued.
//!
//! Secure connections use the root certificates built into the app (Mozilla's
//! list, through `rustls`), not the phone's: servers with self-signed or
//! private-CA certificates are not supported.
//!
//! Syncing is only ever started by the user ("Sync now"): there is no
//! automatic or background sync.

use std::sync::atomic::Ordering;

use chrono::{DateTime, Local, NaiveDate, TimeZone, Utc};
use zerohour_core::clock::{TimeFormat as CoreTimeFormat, format_time, format_time_short};
use zerohour_core::icons::Icon;
use zerohour_core::sync::{self, SyncError, SyncSettings};

use crate::database::{Database, StoredSettings, parse_now};
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

/// What the header's indicator and the Sync page's status line show.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncStatus {
    /// Sync is switched on in the saved settings. When off (or on the demo),
    /// the header shows no indicator, as in the terminal app.
    pub enabled: bool,
    /// True for the demo database, which never syncs.
    pub demo: bool,
    /// When the last sync on this phone finished, short, for the header:
    /// the time today ("2:30PM" or "14:30", following the 12/24-hour
    /// setting), or the date before today ("Oct 21"). `None` if never.
    pub last_synced_short: Option<String>,
    /// The same moment in full, for the Sync page: "Oct 22 2:30 PM".
    pub last_synced_long: Option<String>,
    /// How many documents this phone has synced.
    pub synced_docs: u64,
    /// Tasks and events (not deleted) on this phone.
    pub local_items: u64,
    /// True when this phone has never synced but already has items: the
    /// first sync adds them to the server's, and anything typed on both
    /// devices will appear twice, so the app asks first.
    pub first_sync_warning: bool,
    /// The header indicator's icons in the chosen icon mode, from core's
    /// icon table: never synced or syncing ("⇅" in Unicode mode), synced,
    /// offline ("○") and error ("✗").
    pub icons: SyncIcons,
}

/// The four sync icons, in the chosen icon mode.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncIcons {
    /// Never synced, and while syncing.
    pub sync: String,
    /// The last sync worked.
    pub synced: String,
    /// The server could not be reached.
    pub offline: String,
    /// The last sync failed.
    pub error: String,
}

/// What one "Sync now" did, for the messages after it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncOutcome {
    /// Documents from the server that changed something here.
    pub pulled: u64,
    /// Documents sent to the server.
    pub pushed: u64,
    /// Documents written by a newer zerohour that this app cannot read yet
    /// ("N items need a newer zerohour").
    pub skipped_newer: u64,
    /// Documents that looked like zerohour's but were damaged.
    pub skipped_invalid: u64,
    /// Changes still refused as conflicts; they go in the next sync.
    pub conflicts_left: u64,
    /// How many seconds this phone's clock is ahead (+) or behind (-) the
    /// server's, when more than a minute off.
    pub clock_skew_secs: Option<i64>,
}

/// Turns core's sync error into the one Kotlin sees, keeping core's words
/// (they say what to fix, and never contain the password). "Can't reach the
/// server" becomes `Offline`, which the app shows as a warning, not an error.
impl From<SyncError> for FfiError {
    /// Picks the kind and wraps the message.
    fn from(error: SyncError) -> FfiError {
        if error.is_offline() {
            FfiError::Offline {
                detail: error.to_string(),
            }
        } else {
            FfiError::Sync {
                detail: error.to_string(),
            }
        }
    }
}

/// Converts a count to `u64` for Kotlin. A `usize` is never bigger than 64
/// bits on the phones and computers this runs on, so this never saturates
/// in practice; `unwrap_or` keeps it honest without a panic.
fn count(n: usize) -> u64 {
    u64::try_from(n).unwrap_or(u64::MAX)
}

/// The short and long "last synced" texts for the moment `last`, seen in
/// time zone `tz` on the day `today` (see [`SyncStatus`]). Generic over the
/// time zone so tests can use UTC; the app passes `chrono::Local`.
fn last_synced_texts<Tz: TimeZone>(
    last: DateTime<Utc>,
    tz: &Tz,
    today: NaiveDate,
    format: CoreTimeFormat,
) -> (String, String)
where
    Tz::Offset: std::fmt::Display,
{
    let local = last.with_timezone(tz);
    let date = local.format("%b %d").to_string();
    // Today: just the time, so a fresh sync reads "14:30". Earlier: the
    // date, so a stale one is obvious (the terminal app's rule).
    let short = if local.date_naive() == today {
        format_time_short(local.time(), format)
    } else {
        date.clone()
    };
    let long = format!("{date} {}", format_time(local.time(), format));
    (short, long)
}

/// Clears the "a sync is running" flag when dropped: at the end of
/// [`Database::sync_now`] whichever way it ends (success, error or panic).
struct SyncingGuard<'a>(&'a Database);

impl Drop for SyncingGuard<'_> {
    /// Lets the next sync start.
    fn drop(&mut self) {
        self.0.syncing.store(false, Ordering::SeqCst);
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

    /// The sync state for the header and the Sync page, as of `now` (see
    /// `NOW_FORMAT`; its date decides whether "last synced" shows a time or
    /// a date).
    pub fn sync_status(&self, now: String) -> Result<SyncStatus, FfiError> {
        let today = parse_now(&now)?.local.date();
        let db = self.lock()?;
        let settings = SyncSettings::load(&db)?;
        let stored = StoredSettings::load(&db)?;
        let format = stored.time_format;
        let mode = stored.icon_mode;
        let local_items = count(db.list_items()?.len());
        let last = sync::last_synced(&db)?;
        let (short, long) = match last {
            Some(last) => {
                let (short, long) = last_synced_texts(last, &Local, today, format);
                (Some(short), Some(long))
            }
            None => (None, None),
        };
        Ok(SyncStatus {
            enabled: settings.enabled && !self.is_demo(),
            demo: self.is_demo(),
            last_synced_short: short,
            last_synced_long: long,
            synced_docs: db.synced_doc_count()?,
            local_items,
            first_sync_warning: last.is_none() && local_items > 0,
            icons: SyncIcons {
                sync: Icon::Sync.get(mode).to_string(),
                synced: Icon::Synced.get(mode).to_string(),
                offline: Icon::Offline.get(mode).to_string(),
                error: Icon::SyncError.get(mode).to_string(),
            },
        })
    }

    /// "Sync now": one full sync with the saved details and `password`
    /// (Kotlin reads it from the Keystore-protected store just before). `now`
    /// is the phone's time (see `NOW_FORMAT`).
    ///
    /// Core opens its own connection to the database file for the sync, so
    /// this does not hold the app's database lock while it waits for the
    /// network: the lists stay usable, and a change made meanwhile simply
    /// waits a moment for SQLite. It can take as long as core's network
    /// timeouts, so Kotlin calls it on a background thread.
    ///
    /// Refused on the demo database, when sync is switched off, when the
    /// address is not https, without a password, and while another sync is
    /// running (never queued).
    pub fn sync_now(&self, password: String, now: String) -> Result<SyncOutcome, FfiError> {
        let Some(file) = &self.file else {
            return Err(demo_refused());
        };
        let now = parse_now(&now)?.utc;
        // `compare_exchange`: "if the flag is false, set it to true", as one
        // step no other thread can split. If it was already true, a sync is
        // running.
        if self
            .syncing
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return Err(FfiError::Sync {
                detail: "already syncing".to_string(),
            });
        }
        let _guard = SyncingGuard(self);

        // The saved details, read under the lock, which is let go at once.
        let settings = SyncSettings::load(&*self.lock()?)?;
        if !settings.enabled {
            return Err(FfiError::Sync {
                detail: "sync is switched off".to_string(),
            });
        }
        check_for_connection(&settings)?;
        if password.is_empty() {
            return Err(FfiError::Sync {
                detail: "no password saved; enter it on the Sync page".to_string(),
            });
        }
        let report = sync::sync_database(file, &settings, &password, now)?;
        Ok(SyncOutcome {
            pulled: count(report.pulled),
            pushed: count(report.pushed),
            skipped_newer: count(report.skipped_newer),
            skipped_invalid: count(report.skipped_invalid),
            conflicts_left: count(report.conflicts_left),
            clock_skew_secs: report.clock_skew_secs,
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
    fn a_fresh_database_has_no_sync_status_yet() {
        let (db, path) = empty("status");
        let status = db.sync_status(NOW.into()).unwrap();
        assert!(!status.enabled && !status.demo);
        assert_eq!(status.last_synced_short, None);
        assert_eq!(status.last_synced_long, None);
        assert_eq!(status.local_items, 0);
        assert!(!status.first_sync_warning);
        // A fresh database draws Nerd Font icons; each one is different.
        let i = &status.icons;
        assert!(i.sync != i.synced && i.offline != i.error && i.sync != i.error);
        // Switched on, the indicator shows.
        db.save_sync_settings(config("https://couch.example.invalid"))
            .unwrap();
        assert!(db.sync_status(NOW.into()).unwrap().enabled);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn items_on_a_never_synced_phone_ask_before_the_first_sync() {
        let (db, path) = empty("first-sync");
        let form = db.new_form().unwrap();
        form.set_kind(crate::form::ItemKind::Task).unwrap();
        form.set_text(crate::form::FormField::Title, "Water plants".into())
            .unwrap();
        db.save_form(form, NOW.into()).unwrap();
        let status = db.sync_status(NOW.into()).unwrap();
        assert_eq!(status.local_items, 1);
        assert!(status.first_sync_warning);
        // Once a sync has finished here, it no longer asks.
        db.lock()
            .unwrap()
            .set_setting(sync::keys::LAST_OK, "2026-10-22T07:00:00.000Z")
            .unwrap();
        assert!(!db.sync_status(NOW.into()).unwrap().first_sync_warning);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn last_synced_shows_a_time_today_and_a_date_before() {
        let last = DateTime::parse_from_rfc3339("2026-10-22T14:30:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let today = NaiveDate::from_ymd_opt(2026, 10, 22).unwrap();
        let tomorrow = NaiveDate::from_ymd_opt(2026, 10, 23).unwrap();
        assert_eq!(
            last_synced_texts(last, &Utc, today, CoreTimeFormat::Hour24),
            ("14:30".to_string(), "Oct 22 14:30".to_string())
        );
        assert_eq!(
            last_synced_texts(last, &Utc, today, CoreTimeFormat::Hour12),
            ("2:30PM".to_string(), "Oct 22 2:30 PM".to_string())
        );
        assert_eq!(
            last_synced_texts(last, &Utc, tomorrow, CoreTimeFormat::Hour24).0,
            "Oct 22"
        );
    }

    #[test]
    fn sync_now_is_refused_before_any_network_call() {
        let demo = Database::open_demo(NOW.to_string()).unwrap();
        assert_eq!(
            sync_detail(demo.sync_now("x".into(), NOW.into())),
            "the demo database never syncs"
        );

        let (db, path) = empty("sync-refused");
        // Switched off.
        let mut off = config("https://couch.example.invalid");
        off.enabled = false;
        db.save_sync_settings(off).unwrap();
        assert_eq!(
            sync_detail(db.sync_now("x".into(), NOW.into())),
            "sync is switched off"
        );
        // Switched on, but no password.
        db.save_sync_settings(config("https://couch.example.invalid"))
            .unwrap();
        let detail = sync_detail(db.sync_now(String::new(), NOW.into()));
        assert!(detail.starts_with("no password saved"), "{detail}");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_second_sync_while_one_runs_is_refused_not_queued() {
        let (db, path) = empty("busy");
        db.save_sync_settings(config("https://couch.example.invalid"))
            .unwrap();
        // Pretend a sync is running.
        db.syncing.store(true, Ordering::SeqCst);
        assert_eq!(
            sync_detail(db.sync_now("x".into(), NOW.into())),
            "already syncing"
        );
        // The refused call must not clear the running sync's flag.
        assert!(db.syncing.load(Ordering::SeqCst));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_flag_is_cleared_when_a_sync_ends_in_an_error() {
        let (db, path) = empty("flag");
        db.save_sync_settings(config("https://couch.example.invalid"))
            .unwrap();
        let _ = db.sync_now(String::new(), NOW.into());
        assert!(!db.syncing.load(Ordering::SeqCst));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn unreachable_servers_are_offline_not_errors() {
        let offline = FfiError::from(SyncError::Offline("timed out".into()));
        assert!(matches!(offline, FfiError::Offline { .. }));
        let wrong = FfiError::from(SyncError::Unauthorized);
        match wrong {
            FfiError::Sync { detail } => assert_eq!(detail, "wrong username or password"),
            other => panic!("expected a sync error, got {other:?}"),
        }
    }

    #[test]
    fn the_password_account_names_user_server_and_database() {
        let account = sync_account(config(" https://couch.example.invalid/ "));
        assert_eq!(account, "someone @ https://couch.example.invalid/notes");
    }
}
