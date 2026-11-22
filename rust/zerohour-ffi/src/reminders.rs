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

use chrono::{NaiveDateTime, NaiveTime, TimeDelta};
use zerohour_core::clock::{TimeFormat as CoreTimeFormat, format_time};
use zerohour_core::model::Kind;
use zerohour_core::reminders::{
    MAX_LEAD_MINUTES, Reminder, ReminderActions as CoreReminderActions,
    ReminderKind as CoreReminderKind, ReminderSettings, ReminderSettingsError, plan,
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
    /// At the day time on each day of an event's range (an event with an end
    /// date, such as Dec 1 to Dec 24).
    Daily,
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
            CoreReminderKind::Daily => ReminderKind::Daily,
        }
    }
}

/// Which buttons a reminder's notification offers. Mirror of core's
/// `reminders::ReminderActions`: core decides, so the app never works it out
/// from the item and can't disagree with the plan.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReminderActions {
    /// No buttons: the overdue digest, which is about several tasks.
    None,
    /// "Done": every task reminder.
    Done,
    /// "Stop reminders": a one-off event's daily reminder.
    Stop,
    /// "Stop for this year": a repeating event's daily reminder before its
    /// last day.
    StopForYear,
    /// "Stop for this year", "Keep" and "Remove": a repeating event's daily
    /// reminder on the last day of its range.
    StopKeepRemove,
}

/// Converts core's buttons into the mirror.
impl From<CoreReminderActions> for ReminderActions {
    /// One arm per value; the compiler checks none is missed.
    fn from(actions: CoreReminderActions) -> ReminderActions {
        match actions {
            CoreReminderActions::None => ReminderActions::None,
            CoreReminderActions::Done => ReminderActions::Done,
            CoreReminderActions::Stop => ReminderActions::Stop,
            CoreReminderActions::StopForYear => ReminderActions::StopForYear,
            CoreReminderActions::StopKeepRemove => ReminderActions::StopKeepRemove,
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
    /// The task or event it is about, or `None` for the digest.
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
    /// The notification's text, e.g. "Due 2:00 PM" or "Day 5 of 24 · 19 days
    /// left", in core's words.
    pub body: String,
    /// True only for an event's daily reminder on the last day of its range.
    pub last_day: bool,
    /// The buttons its notification offers (see [`ReminderActions`]).
    pub actions: ReminderActions,
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
            last_day: reminder.last_day,
            actions: reminder.actions.into(),
        }
    }
}

/// The time part of a reminder key, "<item id>|<kind>|2026-10-26T14:00", or
/// `None` if the key doesn't end that way. The id is a UUID (or "digest"), so
/// it never contains '|'; `rsplit` still reads from the end to be safe.
fn key_fire_at(key: &str) -> Option<NaiveDateTime> {
    let time = key.rsplit('|').next()?;
    NaiveDateTime::parse_from_str(time, FIRE_AT_FORMAT).ok()
}

/// True for the kinds whose text names a day ("Due today", "Day 5 of 24"):
/// once that day is over, such a reminder would say something untrue.
fn names_its_day(kind: CoreReminderKind) -> bool {
    matches!(kind, CoreReminderKind::DayOf | CoreReminderKind::Daily)
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

    /// The reminder named by `key` as core would plan it now, or `None` if it
    /// no longer applies. The app asks this when an alarm set earlier goes
    /// off (and for each notification still showing, after a re-plan), and
    /// shows what comes back, with its current text and buttons.
    ///
    /// The key carries the item, the kind and the time
    /// ("<id>|Daily|2026-12-05T09:00"), so this simply plans the one minute
    /// ending at that time and looks for the same key. That covers every
    /// way a reminder can stop applying, here or through a sync since the
    /// alarm was set: the task was completed or deleted, a date or time or an
    /// event's range moved, the event's reminders were stopped, the reminders
    /// (or the digest) were switched off.
    ///
    /// `now` matters only for reminders whose text names a day ("Due today",
    /// "Day 5 of 24"): once that day is over by `now`, they are `None`.
    /// Always `None` on the demo database, and for a key that can't be read.
    pub fn reminder_now(
        &self,
        key: String,
        now: String,
    ) -> Result<Option<PlannedReminder>, FfiError> {
        let today = parse_now(&now)?.local.date();
        if self.is_demo() {
            return Ok(None);
        }
        let Some(fire_at) = key_fire_at(&key) else {
            return Ok(None);
        };
        let db = self.lock()?;
        let settings = ReminderSettings::load(&db)?;
        let time_format = StoredSettings::load(&db)?.time_format;
        let items = db.list_items()?;
        // The planner returns reminders strictly after its "now" and up to
        // its "until", so this window holds exactly the ones at `fire_at`.
        let found = plan(
            &items,
            fire_at - TimeDelta::minutes(1),
            fire_at,
            &settings,
            time_format,
        )
        .into_iter()
        .find(|reminder| reminder.key() == key);
        // `filter` keeps it only if the test passes: here, not a day-named
        // reminder whose day is over.
        Ok(found
            .filter(|reminder| !(names_its_day(reminder.kind) && fire_at.date() < today))
            .map(|reminder| PlannedReminder::new(reminder, time_format)))
    }

    /// Stops an event's daily reminders for its current range (the running
    /// one, or the next one: "skip this year"), as the "Stop" button and the
    /// edit screen do. Core's `Db::stop_event_reminders`; refused for a task,
    /// an event without an end date, or a deleted event. (On the demo it only
    /// changes the in-memory sample, like every other edit there.)
    pub fn stop_event_reminders(&self, id: String, now: String) -> Result<(), FfiError> {
        let now = parse_now(&now)?;
        self.lock()?
            .stop_event_reminders(&id, now.local.date(), now.utc)?;
        Ok(())
    }

    /// Undoes [`Database::stop_event_reminders`] (the edit screen's
    /// "resume"). Core's `Db::resume_event_reminders`.
    pub fn resume_event_reminders(&self, id: String, now: String) -> Result<(), FfiError> {
        let now = parse_now(&now)?;
        self.lock()?.resume_event_reminders(&id, now.utc)?;
        Ok(())
    }

    /// True when the item with this `id` is a task that is still open: not
    /// completed, not deleted, and not an event. The "Done" button checks
    /// this when completing fails, to tell a real failure from a task that
    /// was already completed or deleted.
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

    /// Stores a yearly Dec 1–24 "Advent of Cyber" event and returns its id.
    fn add_advent(db: &Database) -> String {
        let mut item = Item::new(Kind::Event, "Advent of Cyber", Utc::now());
        item.date = NaiveDate::from_ymd_opt(2026, 12, 1);
        item.end_date = NaiveDate::from_ymd_opt(2026, 12, 24);
        item.repeat = RepeatRule::Yearly;
        db.lock().unwrap().insert_item(&item).unwrap();
        item.id
    }

    /// Dec 23 2026, 08:00 Central (standard time, six hours behind UTC).
    const DEC_23_8AM: &str = "2026-12-23T08:00:00-06:00";

    #[test]
    fn daily_reminders_carry_their_buttons_and_last_day() {
        let (db, path) = file_db("daily");
        db.set_reminders_enabled(true).unwrap();
        let id = add_advent(&db);
        let plan = db.plan_reminders(DEC_23_8AM.into(), 48).unwrap();
        assert_eq!(plan.len(), 2, "Dec 23 and Dec 24 at 9:00");
        assert_eq!(plan[0].kind, ReminderKind::Daily);
        assert_eq!(plan[0].item_id.as_deref(), Some(id.as_str()));
        assert_eq!(plan[0].body, "Day 23 of 24 · 1 day left");
        assert_eq!(plan[0].actions, ReminderActions::StopForYear);
        assert!(!plan[0].last_day);
        assert_eq!(plan[1].body, "Last day");
        assert_eq!(plan[1].actions, ReminderActions::StopKeepRemove);
        assert!(plan[1].last_day);
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn reminder_now_gives_the_reminder_while_it_still_applies() {
        let (db, path) = file_db("now");
        db.set_reminders_enabled(true).unwrap();
        let id = add_advent(&db);
        let key = format!("{id}|Daily|2026-12-23T09:00");
        let at_nine = "2026-12-23T09:00:00-06:00".to_string();
        let shown = db
            .reminder_now(key.clone(), at_nine.clone())
            .unwrap()
            .unwrap();
        assert_eq!(shown.key, key);
        assert_eq!(shown.body, "Day 23 of 24 · 1 day left");
        // A day-named reminder the next day: its text would be wrong.
        assert_eq!(
            db.reminder_now(key.clone(), DEC_24_NOON.into()).unwrap(),
            None
        );
        // Stopped: gone. Resumed: back.
        db.stop_event_reminders(id.clone(), at_nine.clone())
            .unwrap();
        assert_eq!(db.reminder_now(key.clone(), at_nine.clone()).unwrap(), None);
        db.resume_event_reminders(id.clone(), at_nine.clone())
            .unwrap();
        assert!(
            db.reminder_now(key.clone(), at_nine.clone())
                .unwrap()
                .is_some()
        );
        // The range moved (as a sync could do): the old alarm no longer applies.
        {
            let guard = db.lock().unwrap();
            let mut item = guard.get_item(&id).unwrap().unwrap();
            item.end_date = NaiveDate::from_ymd_opt(2026, 12, 20);
            guard.update_item(&mut item, Utc::now()).unwrap();
        }
        assert_eq!(db.reminder_now(key, at_nine.clone()).unwrap(), None);
        // Nonsense keys are simply not planned.
        assert_eq!(db.reminder_now("no key".into(), at_nine).unwrap(), None);
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn reminder_now_drops_completed_tasks_and_switched_off_reminders() {
        let (db, path) = file_db("now-task");
        db.set_reminders_enabled(true).unwrap();
        let id = add_timesheet(&db, monday());
        let key = format!("{id}|Due|2026-10-26T14:00");
        // An alarm that went off late (3 PM) still shows its 2 PM reminder.
        let late = "2026-10-26T15:00:00-05:00".to_string();
        assert!(
            db.reminder_now(key.clone(), late.clone())
                .unwrap()
                .is_some()
        );
        db.set_reminders_enabled(false).unwrap();
        assert_eq!(db.reminder_now(key.clone(), late.clone()).unwrap(), None);
        db.set_reminders_enabled(true).unwrap();
        db.complete_task(id, MONDAY_ONE_PM.into()).unwrap();
        assert_eq!(db.reminder_now(key, late).unwrap(), None);
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_demo_never_gives_a_reminder_and_tasks_cannot_be_stopped() {
        let db = empty();
        assert_eq!(
            db.reminder_now("x|Due|2026-10-26T14:00".into(), MONDAY_ONE_PM.into())
                .unwrap(),
            None
        );
        let (db, path) = file_db("stop-task");
        let id = add_timesheet(&db, monday());
        assert!(db.stop_event_reminders(id, MONDAY_ONE_PM.into()).is_err());
        drop(db);
        let _ = std::fs::remove_file(&path);
    }

    /// Dec 24 2026, noon Central.
    const DEC_24_NOON: &str = "2026-12-24T12:00:00-06:00";

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
