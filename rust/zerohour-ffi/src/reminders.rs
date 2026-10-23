//! Reminders: the settings that control them.
//!
//! Core decides everything about reminders (`zerohour_core::reminders`): which
//! settings exist, their defaults, and how they are checked. This module only
//! hands them to Kotlin and back.
//!
//! The settings live in the database's `settings` table, which is per device
//! and never synced, so the phone and the terminal app each decide for
//! themselves whether and when to remind. Each setter saves at once, like the
//! other settings rows.

use chrono::NaiveTime;
use zerohour_core::clock::format_time;
use zerohour_core::reminders::{MAX_LEAD_MINUTES, ReminderSettings, ReminderSettingsError};

use crate::database::{Database, StoredSettings};
use crate::error::FfiError;

/// The shape of a day time across the bridge: 24-hour "HH:MM", e.g. "09:00",
/// the same form core stores. (`%H` is the hour 00-23, `%M` the minutes.)
const DAY_TIME_FORMAT: &str = "%H:%M";

/// The reminder settings, as the Reminders page shows them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReminderConfig {
    /// The master switch. Off until the user turns it on.
    pub enabled: bool,
    /// Minutes before a timed task's due time for an extra, earlier reminder;
    /// 0 means none. Always 0 to 1440.
    pub lead_minutes: u32,
    /// When untimed tasks are reminded on their day, and when the overdue
    /// digest comes, as "HH:MM" (24-hour). Kotlin steps it and sends it back
    /// in the same form.
    pub day_time: String,
    /// The same time for the screen, in the 12/24-hour setting ("9:00 AM" or
    /// "09:00"), in core's words.
    pub day_time_text: String,
    /// The once-a-day list of overdue tasks.
    pub overdue_digest: bool,
}

/// Turns core's "could not save the reminder settings" into an `FfiError`.
impl From<ReminderSettingsError> for FfiError {
    /// A lead time that is too long is a bug on the Kotlin side (the page only
    /// offers short ones); a database failure keeps core's wording.
    fn from(error: ReminderSettingsError) -> FfiError {
        match error {
            ReminderSettingsError::LeadOutOfRange => FfiError::InvalidInput {
                detail: error.to_string(),
            },
            ReminderSettingsError::Db(db) => db.into(),
        }
    }
}

/// Helpers used only inside this crate (not exported to Kotlin).
impl Database {
    /// Loads the reminder settings, lets `change` alter them, and saves them,
    /// all under one database lock. Nothing is written if `change` refuses
    /// the new value.
    ///
    /// `impl FnOnce(&mut ReminderSettings) -> ...` means "any function (or
    /// closure) that is called once with the settings to change".
    fn change_reminder_settings(
        &self,
        change: impl FnOnce(&mut ReminderSettings) -> Result<(), FfiError>,
    ) -> Result<(), FfiError> {
        let db = self.lock()?;
        let mut settings = ReminderSettings::load(&db)?;
        change(&mut settings)?;
        settings.save(&db)?;
        Ok(())
    }
}

// Another `#[uniffi::export] impl` block: UniFFI gathers the methods from
// every block into the one Kotlin `Database` class.
#[uniffi::export]
impl Database {
    /// The reminder settings, with core's defaults for anything not saved
    /// (off, no lead time, 09:00, no digest).
    pub fn reminder_settings(&self) -> Result<ReminderConfig, FfiError> {
        let db = self.lock()?;
        let settings = ReminderSettings::load(&db)?;
        let time_format = StoredSettings::load(&db)?.time_format;
        Ok(ReminderConfig {
            enabled: settings.enabled,
            lead_minutes: settings.lead_minutes,
            day_time: settings.day_time.format(DAY_TIME_FORMAT).to_string(),
            day_time_text: format_time(settings.day_time, time_format),
            overdue_digest: settings.overdue_digest,
        })
    }

    /// Switches reminders on or off.
    pub fn set_reminders_enabled(&self, enabled: bool) -> Result<(), FfiError> {
        self.change_reminder_settings(|s| {
            s.enabled = enabled;
            Ok(())
        })
    }

    /// Saves the lead time in minutes (0 = none). More than a day is refused
    /// and nothing changes.
    pub fn set_reminder_lead(&self, minutes: u32) -> Result<(), FfiError> {
        // Core's `save` checks this too; checking first gives the same
        // refusal without touching the other settings.
        if minutes > MAX_LEAD_MINUTES {
            return Err(ReminderSettingsError::LeadOutOfRange.into());
        }
        self.change_reminder_settings(|s| {
            s.lead_minutes = minutes;
            Ok(())
        })
    }

    /// Saves the day time, given as 24-hour "HH:MM" (e.g. "07:45"). Anything
    /// else is refused and nothing changes.
    pub fn set_reminder_day_time(&self, time: String) -> Result<(), FfiError> {
        let parsed = NaiveTime::parse_from_str(time.trim(), DAY_TIME_FORMAT).map_err(|e| {
            FfiError::InvalidInput {
                detail: format!("\"{time}\" is not a time like 09:00 ({e})"),
            }
        })?;
        self.change_reminder_settings(|s| {
            s.day_time = parsed;
            Ok(())
        })
    }

    /// Switches the once-a-day overdue digest on or off.
    pub fn set_overdue_digest(&self, on: bool) -> Result<(), FfiError> {
        self.change_reminder_settings(|s| {
            s.overdue_digest = on;
            Ok(())
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::TimeFormat;
    use std::sync::Arc;
    use zerohour_core::db::Db;

    /// An empty in-memory database, wrapped as Kotlin would get it. It has no
    /// file, so it could never sync.
    fn empty() -> Arc<Database> {
        Database::wrap(Db::open_in_memory().unwrap(), None)
    }

    #[test]
    fn a_fresh_database_has_reminders_off_with_the_defaults() {
        let config = empty().reminder_settings().unwrap();
        assert_eq!(
            config,
            ReminderConfig {
                enabled: false,
                lead_minutes: 0,
                day_time: "09:00".into(),
                day_time_text: "9:00 AM".into(),
                overdue_digest: false,
            }
        );
    }

    #[test]
    fn each_setter_saves_and_reads_back() {
        let db = empty();
        db.set_reminders_enabled(true).unwrap();
        db.set_reminder_lead(15).unwrap();
        db.set_reminder_day_time("07:45".into()).unwrap();
        db.set_overdue_digest(true).unwrap();
        let config = db.reminder_settings().unwrap();
        assert!(config.enabled);
        assert_eq!(config.lead_minutes, 15);
        assert_eq!(config.day_time, "07:45");
        assert_eq!(config.day_time_text, "7:45 AM");
        assert!(config.overdue_digest);
    }

    #[test]
    fn the_day_time_text_follows_the_time_setting() {
        let db = empty();
        db.set_reminder_day_time("13:15".into()).unwrap();
        db.set_time_format(TimeFormat::Hour24).unwrap();
        assert_eq!(db.reminder_settings().unwrap().day_time_text, "13:15");
    }

    #[test]
    fn bad_values_are_refused_and_nothing_changes() {
        let db = empty();
        db.set_reminder_lead(30).unwrap();
        assert!(matches!(
            db.set_reminder_lead(MAX_LEAD_MINUTES + 1),
            Err(FfiError::InvalidInput { .. })
        ));
        db.set_reminder_lead(MAX_LEAD_MINUTES).unwrap();
        for bad in ["", "9am", "25:00", "09:60", "9.00"] {
            assert!(
                matches!(
                    db.set_reminder_day_time(bad.into()),
                    Err(FfiError::InvalidInput { .. })
                ),
                "{bad:?} was accepted"
            );
        }
        let config = db.reminder_settings().unwrap();
        assert_eq!(config.lead_minutes, MAX_LEAD_MINUTES);
        assert_eq!(config.day_time, "09:00");
    }
}
