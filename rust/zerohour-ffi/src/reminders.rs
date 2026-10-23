//! Reminders: the settings that control them, and the plan of what to remind
//! about when.
//!
//! Core decides everything about reminders (`zerohour_core::reminders`): which
//! settings exist, their defaults and checks, and which reminders fall in a
//! window of time. This module only hands them to Kotlin and back. Kotlin
//! turns the plan into Android alarms and notifications.
//!
//! ## Times across the bridge
//!
//! A planned reminder's time is a *floating* local wall-clock time with no
//! time zone, like a due time: "2026-10-26T14:00" means 2 PM on whatever
//! clock the phone shows. Kotlin turns it into a real moment with the phone's
//! current time zone when it sets the alarm (including what to do on the
//! nights the clocks change), so a time-zone change only needs a re-plan.
//!
//! The settings live in the database's `settings` table, which is per device
//! and never synced, so the phone and the terminal app each decide for
//! themselves whether and when to remind. Each setter saves at once, like the
//! other settings rows.

use chrono::{NaiveTime, TimeDelta};
use zerohour_core::clock::{TimeFormat as CoreTimeFormat, format_time};
use zerohour_core::model::Kind;
use zerohour_core::reminders::{
    MAX_LEAD_MINUTES, Reminder, ReminderKind as CoreReminderKind, ReminderSettings,
    ReminderSettingsError, plan,
};

use crate::database::{Database, StoredSettings, parse_now};
use crate::error::FfiError;

/// The shape of a day time across the bridge: 24-hour "HH:MM", e.g. "09:00",
/// the same form core stores. (`%H` is the hour 00-23, `%M` the minutes.)
const DAY_TIME_FORMAT: &str = "%H:%M";

/// The shape of a reminder's time across the bridge: ISO 8601 local date and
/// time to the minute, with no offset, e.g. "2026-10-26T14:00". Kotlin reads
/// it with `LocalDateTime.parse`.
const FIRE_AT_FORMAT: &str = "%Y-%m-%dT%H:%M";

/// The longest window [`Database::plan_reminders`] accepts: one week. The app
/// asks for 48 hours; a longer window would only mean more alarms to keep.
pub const MAX_PLAN_HOURS: u32 = 7 * 24;

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

/// Why a reminder fires. Mirror of core's `reminders::ReminderKind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReminderKind {
    /// At a timed task's due time.
    Due,
    /// The lead time before a timed task's due time.
    Lead,
    /// At the day time on the day an untimed task is due.
    DayOf,
    /// The once-a-day list of overdue tasks (not about one task, so no Done).
    Digest,
}

/// Converts core's reminder kind into the mirror.
impl From<CoreReminderKind> for ReminderKind {
    /// One arm per kind; the compiler checks none is missed.
    fn from(kind: CoreReminderKind) -> ReminderKind {
        match kind {
            CoreReminderKind::Due => ReminderKind::Due,
            CoreReminderKind::Lead => ReminderKind::Lead,
            CoreReminderKind::DayOf => ReminderKind::DayOf,
            CoreReminderKind::Digest => ReminderKind::Digest,
        }
    }
}

/// One reminder from core's plan, as Kotlin schedules and shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PlannedReminder {
    /// Core's key, e.g. "<item id>|Due|2026-10-26T14:00". It names this
    /// reminder the same way every time it is planned, so the app schedules
    /// it once and can cancel it when it is no longer planned.
    pub key: String,
    /// The task it is about, or `None` for the digest. The notification
    /// offers "Done" only when there is one.
    pub item_id: Option<String>,
    /// Why it fires.
    pub kind: ReminderKind,
    /// When, as floating local wall-clock time (see the module notes and
    /// [`FIRE_AT_FORMAT`]).
    pub fire_at: String,
    /// When, for the screen: "Mon 2:00 PM" or "Mon 14:00", in the 12/24-hour
    /// setting. Used by the Reminders page's "Next:" line.
    pub when_text: String,
    /// The notification's title (the task's title, or "3 overdue tasks").
    pub title: String,
    /// The notification's text, e.g. "Due 2:00 PM", in core's words.
    pub body: String,
}

impl PlannedReminder {
    /// Copies one of core's reminders, adding its key and texts.
    fn new(reminder: Reminder, time_format: CoreTimeFormat) -> PlannedReminder {
        PlannedReminder {
            key: reminder.key(),
            kind: reminder.kind.into(),
            fire_at: reminder.fire_at.format(FIRE_AT_FORMAT).to_string(),
            // `%a` is the short weekday name, "Mon".
            when_text: format!(
                "{} {}",
                reminder.fire_at.format("%a"),
                format_time(reminder.fire_at.time(), time_format)
            ),
            item_id: reminder.item_id,
            title: reminder.title,
            body: reminder.body,
        }
    }
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

    /// The reminders that fire after `now` and no more than `hours` later
    /// (1 to [`MAX_PLAN_HOURS`]), earliest first: core's
    /// `reminders::plan` with the saved settings and 12/24-hour texts.
    ///
    /// `now` is the usual "now" text with its offset (see `NOW_FORMAT`);
    /// the window is counted in wall-clock time from its local part. The
    /// list is empty when reminders are switched off, and ALWAYS empty for
    /// the in-memory demo database: demo data never reminds.
    pub fn plan_reminders(
        &self,
        now: String,
        hours: u32,
    ) -> Result<Vec<PlannedReminder>, FfiError> {
        if !(1..=MAX_PLAN_HOURS).contains(&hours) {
            return Err(FfiError::InvalidInput {
                detail: format!("a reminder window of {hours} hours is not 1 to {MAX_PLAN_HOURS}"),
            });
        }
        let now = parse_now(&now)?.local;
        if self.is_demo() {
            return Ok(Vec::new());
        }
        let db = self.lock()?;
        let settings = ReminderSettings::load(&db)?;
        let time_format = StoredSettings::load(&db)?.time_format;
        // Every item that isn't deleted; core skips completed tasks and
        // events itself.
        let items = db.list_items()?;
        let until = now + TimeDelta::hours(i64::from(hours));
        Ok(plan(&items, now, until, &settings, time_format)
            .into_iter()
            .map(|reminder| PlannedReminder::new(reminder, time_format))
            .collect())
    }

    /// True when the item with this `id` is a task that is still open: not
    /// completed, not deleted, and not an event. The app checks this before
    /// showing a reminder whose alarm was set earlier, and to take away
    /// notifications about tasks that were completed since (here, or on
    /// another device and synced).
    pub fn is_open_task(&self, id: String) -> Result<bool, FfiError> {
        let item = self.lock()?.get_item(&id)?;
        // `is_some_and` is true only for `Some(item)` that passes the test.
        Ok(item
            .is_some_and(|item| item.kind == Kind::Task && !item.deleted && !item.is_completed()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::TimeFormat;
    use chrono::{NaiveDate, Utc};
    use std::path::PathBuf;
    use std::sync::Arc;
    use zerohour_core::db::Db;
    use zerohour_core::model::{DaySet, Item, RepeatRule};

    /// Mon Oct 26 2026, 13:00, five hours behind UTC (US Central daylight).
    const MONDAY_ONE_PM: &str = "2026-10-26T13:00:00-05:00";

    /// An empty in-memory database, wrapped as Kotlin would get it. It has no
    /// file, so it could never sync.
    fn empty() -> Arc<Database> {
        Database::wrap(Db::open_in_memory().unwrap(), None)
    }

    /// A fresh database in a throwaway file. The plan needs a file: the
    /// in-memory kind counts as the demo, which never reminds.
    fn file_db(name: &str) -> (Arc<Database>, PathBuf) {
        let path = std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-reminders-{name}.db",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let db = Database::open(path.to_string_lossy().into_owned()).unwrap();
        (db, path)
    }

    /// Stores the Mon-Thu 2 PM "Fill Out Your Timesheet" task, its open
    /// instance due on `date`, and returns its id.
    fn add_timesheet(db: &Database, date: NaiveDate) -> String {
        let mut item = Item::new(Kind::Task, "Fill Out Your Timesheet", Utc::now());
        item.date = Some(date);
        item.due_time = NaiveTime::from_hms_opt(14, 0, 0);
        item.repeat = RepeatRule::Weekdays(DaySet::MON_THU);
        db.lock().unwrap().insert_item(&item).unwrap();
        item.id
    }

    /// Monday Oct 26 2026.
    fn monday() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 10, 26).unwrap()
    }

    /// Just the keys of a plan, for compact assertions.
    fn keys(plan: &[PlannedReminder]) -> Vec<String> {
        plan.iter().map(|r| r.key.clone()).collect()
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

    #[test]
    fn the_timesheet_reminds_at_two_on_each_work_day() {
        let (db, path) = file_db("timesheet");
        let id = add_timesheet(&db, monday());
        // Off by default: nothing planned.
        assert!(
            db.plan_reminders(MONDAY_ONE_PM.into(), 48)
                .unwrap()
                .is_empty()
        );

        db.set_reminders_enabled(true).unwrap();
        let plan = db.plan_reminders(MONDAY_ONE_PM.into(), 48).unwrap();
        assert_eq!(
            keys(&plan),
            [
                format!("{id}|Due|2026-10-26T14:00"),
                format!("{id}|Due|2026-10-27T14:00"),
            ]
        );
        let first = &plan[0];
        assert_eq!(first.item_id.as_deref(), Some(id.as_str()));
        assert_eq!(first.kind, ReminderKind::Due);
        assert_eq!(first.fire_at, "2026-10-26T14:00");
        assert_eq!(first.when_text, "Mon 2:00 PM");
        assert_eq!(first.title, "Fill Out Your Timesheet");
        assert_eq!(first.body, "Due 2:00 PM");
        assert!(db.is_open_task(id).unwrap());
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn completing_before_two_drops_that_days_reminder() {
        // Done at 1 PM on Monday: core creates Tuesday's instance, and the
        // next plan has no Monday 2 PM at all (so no stale notification),
        // only the new instance's Tuesday (the 48 hours end on Wednesday at
        // 1 PM, before that day's 2 PM).
        let (db, path) = file_db("early");
        let id = add_timesheet(&db, monday());
        db.set_reminders_enabled(true).unwrap();
        db.complete_task(id.clone(), MONDAY_ONE_PM.into()).unwrap();
        let plan = db.plan_reminders(MONDAY_ONE_PM.into(), 48).unwrap();
        assert_eq!(
            plan.iter().map(|r| r.fire_at.as_str()).collect::<Vec<_>>(),
            ["2026-10-27T14:00"]
        );
        assert!(
            plan.iter()
                .all(|r| r.item_id.as_deref() != Some(id.as_str()))
        );
        assert!(!db.is_open_task(id).unwrap());
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn lead_times_and_24_hour_texts() {
        let (db, path) = file_db("lead");
        let id = add_timesheet(&db, monday());
        db.set_reminders_enabled(true).unwrap();
        db.set_reminder_lead(15).unwrap();
        db.set_time_format(TimeFormat::Hour24).unwrap();
        let plan = db.plan_reminders(MONDAY_ONE_PM.into(), 2).unwrap();
        assert_eq!(
            keys(&plan),
            [
                format!("{id}|Lead|2026-10-26T13:45"),
                format!("{id}|Due|2026-10-26T14:00"),
            ]
        );
        assert_eq!(plan[0].kind, ReminderKind::Lead);
        assert_eq!(plan[0].body, "Due in 15 min (14:00)");
        assert_eq!(plan[0].when_text, "Mon 13:45");
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_digest_has_no_item() {
        let (db, path) = file_db("digest");
        // Due last Thursday: overdue at Tuesday's 09:00.
        add_timesheet(&db, NaiveDate::from_ymd_opt(2026, 10, 22).unwrap());
        db.set_reminders_enabled(true).unwrap();
        db.set_overdue_digest(true).unwrap();
        let plan = db.plan_reminders(MONDAY_ONE_PM.into(), 24).unwrap();
        let digest = plan
            .iter()
            .find(|r| r.kind == ReminderKind::Digest)
            .unwrap();
        assert_eq!(digest.item_id, None);
        assert_eq!(digest.fire_at, "2026-10-27T09:00");
        assert_eq!(digest.title, "1 overdue task");
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_demo_never_reminds() {
        let db = Database::open_demo(MONDAY_ONE_PM.into()).unwrap();
        db.set_reminders_enabled(true).unwrap();
        db.set_overdue_digest(true).unwrap();
        assert!(
            db.plan_reminders(MONDAY_ONE_PM.into(), 48)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn the_window_and_now_are_checked() {
        let (db, path) = file_db("window");
        for hours in [0, MAX_PLAN_HOURS + 1] {
            assert!(matches!(
                db.plan_reminders(MONDAY_ONE_PM.into(), hours),
                Err(FfiError::InvalidInput { .. })
            ));
        }
        assert!(
            db.plan_reminders(MONDAY_ONE_PM.into(), MAX_PLAN_HOURS)
                .is_ok()
        );
        assert!(matches!(
            db.plan_reminders("Monday".into(), 48),
            Err(FfiError::InvalidInput { .. })
        ));
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn deleted_missing_and_events_are_not_open_tasks() {
        let (db, path) = file_db("open");
        let id = add_timesheet(&db, monday());
        let mut event = Item::new(Kind::Event, "Lake trip", Utc::now());
        event.date = Some(monday());
        db.lock().unwrap().insert_item(&event).unwrap();
        assert!(!db.is_open_task(event.id).unwrap());
        assert!(!db.is_open_task("no such id".into()).unwrap());
        db.delete_item(id.clone(), MONDAY_ONE_PM.into()).unwrap();
        assert!(!db.is_open_task(id).unwrap());
        drop(db);
        let _ = std::fs::remove_file(&path);
    }
}
