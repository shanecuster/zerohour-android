//! Changing items: opening the add/edit form, saving it, the settings the
//! form changes, completing tasks and deleting items.
//!
//! These are `Database` methods because they read or write the database, so
//! Kotlin calls them on a background thread. Each one does what the terminal
//! app does for the same action (see `tui/src/app.rs` in the zerohour repo),
//! using core for the work: `FormInput::from_item` to fill an edit form,
//! `FormInput::validate` to check it, `FormData::apply_to` to copy it onto an
//! item, `Db::insert_item` / `Db::update_item` to store it, and
//! `Db::complete_task` / `Db::delete_item` for the swipes.

use std::sync::Arc;

use zerohour_core::calendar::WeekStart as CoreWeekStart;
use zerohour_core::clock::format_time;
use zerohour_core::db::{Db, settings_keys};
use zerohour_core::form::FormInput;
use zerohour_core::model::{Item, Kind};

use crate::database::{Database, StoredSettings, parse_now};
use crate::error::FfiError;
use crate::form::{FormSession, ItemForm, WeekStart};

/// What happened when the user tapped Save.
///
/// `#[derive(uniffi::Enum)]` on an enum whose variants carry fields becomes a
/// Kotlin `sealed class` with one subclass per variant.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SaveOutcome {
    /// Something in the form is not right yet. Nothing was saved and the form
    /// stays open; `message` is core's explanation (the same words as the
    /// terminal app's, e.g. "Please enter a title.").
    Invalid {
        /// What to fix.
        message: String,
    },
    /// A new item was added.
    Added {
        /// Its title, for the status line.
        title: String,
    },
    /// An existing item was changed.
    Saved {
        /// Its title, for the status line.
        title: String,
    },
}

/// What happened when the user swiped a row right to complete it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CompleteOutcome {
    /// The row is an event. Events count down on their own, so there is
    /// nothing to complete (the terminal app says so too); nothing changed.
    NotATask,
    /// The task is done (or already was).
    Done {
        /// Its title, for the status line.
        title: String,
        /// For a repeating task, when the new instance core created is due,
        /// e.g. "Fri Oct 23" or "Fri Oct 23 2:00 PM" (in the terminal app's
        /// words). `None` for a one-off task.
        next_due: Option<String>,
    },
}

/// Reads the saved first day of the week (Sunday if nothing is saved).
pub(crate) fn load_week_start(db: &Db) -> Result<CoreWeekStart, FfiError> {
    Ok(CoreWeekStart::parse(
        db.get_setting(settings_keys::WEEK_START)?.as_deref(),
    ))
}

#[uniffi::export]
impl Database {
    /// A blank form for adding a task or an event (a Medium task until the
    /// user picks otherwise; core's defaults).
    pub fn new_form(&self) -> Result<Arc<ItemForm>, FfiError> {
        let db = self.lock()?;
        Ok(Arc::new(ItemForm::new(FormSession {
            input: FormInput::default(),
            time_format: StoredSettings::load(&db)?.time_format,
            week_start: load_week_start(&db)?,
            calendar_month: None,
        })))
    }

    /// A form filled in from the item with this `id`, for editing. Fails if
    /// there is no such item, or it was deleted (for example by a sync since
    /// the screen was drawn).
    pub fn edit_form(&self, id: String) -> Result<Arc<ItemForm>, FfiError> {
        let db = self.lock()?;
        let time_format = StoredSettings::load(&db)?.time_format;
        // `filter` turns a deleted item into `None`, so both cases share the
        // "not found" error below.
        let item = db
            .get_item(&id)?
            .filter(|item| !item.deleted)
            .ok_or_else(|| FfiError::Database {
                detail: "that item no longer exists".to_string(),
            })?;
        Ok(Arc::new(ItemForm::new(FormSession {
            input: FormInput::from_item(&item, time_format),
            time_format,
            week_start: load_week_start(&db)?,
            calendar_month: None,
        })))
    }

    /// Checks the form and, if everything is right, adds the new item or
    /// saves the changes to the existing one.
    pub fn save_form(&self, form: Arc<ItemForm>, now: String) -> Result<SaveOutcome, FfiError> {
        let now = parse_now(&now)?;
        // Take a copy of the form's values and let its lock go straight away;
        // the database lock is taken next, and holding one lock while taking
        // another is how deadlocks start.
        let input = form.lock()?.input.clone();
        let data = match input.validate(now.local.date()) {
            Ok(data) => data,
            Err(message) => return Ok(SaveOutcome::Invalid { message }),
        };

        let db = self.lock()?;
        match &data.editing {
            Some(id) => {
                // Load the stored item, overwrite the editable fields, save.
                let mut item = db
                    .get_item(id)?
                    .filter(|item| !item.deleted)
                    .ok_or_else(|| FfiError::Database {
                        detail: "that item no longer exists".to_string(),
                    })?;
                data.apply_to(&mut item);
                db.update_item(&mut item, now.utc)?;
                Ok(SaveOutcome::Saved { title: item.title })
            }
            None => {
                let mut item = Item::new(data.kind, data.title.clone(), now.utc);
                data.apply_to(&mut item);
                db.insert_item(&item)?;
                Ok(SaveOutcome::Added { title: item.title })
            }
        }
    }

    /// Saves the calendar's first day of the week (the setting the terminal
    /// app's `w` key changes), so the next form opens with it too.
    pub fn set_week_start(&self, start: WeekStart) -> Result<(), FfiError> {
        let start: CoreWeekStart = start.into();
        self.lock()?
            .set_setting(settings_keys::WEEK_START, start.as_str())?;
        Ok(())
    }

    /// Completes the task with this `id`, as the terminal app's `space` key
    /// does: core marks it done, logs the completion for the stats, and, if
    /// it repeats, creates the next instance, all in one transaction.
    pub fn complete_task(&self, id: String, now: String) -> Result<CompleteOutcome, FfiError> {
        let now = parse_now(&now)?;
        // `mut`: core's `complete_task` needs `&mut Db` for its transaction.
        let mut db = self.lock()?;
        let time_format = StoredSettings::load(&db)?.time_format;
        let item = db
            .get_item(&id)?
            .filter(|item| !item.deleted)
            .ok_or_else(|| FfiError::Database {
                detail: "that item no longer exists".to_string(),
            })?;
        if item.kind == Kind::Event {
            return Ok(CompleteOutcome::NotATask);
        }
        let next = db.complete_task(&id, now.local, now.utc)?;
        // "Fri Oct 23", plus " 2:00 PM" when the next one has a due time.
        let next_due = next.map(|next| {
            let mut due = next
                .date
                .map(|d| d.format("%a %b %d").to_string())
                .unwrap_or_default();
            if let Some(time) = next.due_time {
                due.push(' ');
                due.push_str(&format_time(time, time_format));
            }
            due
        });
        Ok(CompleteOutcome::Done {
            title: item.title,
            next_due,
        })
    }

    /// Deletes the item with this `id` (after the user confirmed). Core only
    /// marks it deleted and keeps the row, so a later sync can pass the
    /// deletion on to other devices.
    pub fn delete_item(&self, id: String, now: String) -> Result<(), FfiError> {
        let now = parse_now(&now)?;
        self.lock()?.delete_item(&id, now.utc)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::form::{FormField, ItemKind};

    /// Thu Oct 22 2026, 09:15, two hours ahead of UTC.
    const NOW: &str = "2026-10-22T09:15:00+02:00";

    /// An empty in-memory database, wrapped as Kotlin would get it.
    fn empty() -> Arc<Database> {
        Database::wrap(Db::open_in_memory().unwrap(), false)
    }

    #[test]
    fn adding_a_task_validates_then_saves() {
        let db = empty();
        let form = db.new_form().unwrap();
        // Core's own message for an empty title, and nothing saved.
        assert_eq!(
            db.save_form(form.clone(), NOW.into()).unwrap(),
            SaveOutcome::Invalid {
                message: "Please enter a title.".into()
            }
        );
        form.set_text(FormField::Title, "  Renew passport ".into())
            .unwrap();
        form.set_text(FormField::Date, "+3d".into()).unwrap();
        assert_eq!(
            db.save_form(form, NOW.into()).unwrap(),
            SaveOutcome::Added {
                title: "Renew passport".into()
            }
        );
        let items = db.lock().unwrap().list_items().unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].date.unwrap().to_string(), "2026-10-25");
        // Stored in UTC: 09:15 at +02:00 is 07:15 UTC.
        assert_eq!(
            items[0].created_at.to_rfc3339(),
            "2026-10-22T07:15:00+00:00"
        );
    }

    #[test]
    fn an_event_needs_a_date() {
        let db = empty();
        let form = db.new_form().unwrap();
        form.set_kind(ItemKind::Event).unwrap();
        form.set_text(FormField::Title, "Lake trip".into()).unwrap();
        assert_eq!(
            db.save_form(form, NOW.into()).unwrap(),
            SaveOutcome::Invalid {
                message: "Events need a date to count down to.".into()
            }
        );
    }

    #[test]
    fn editing_changes_the_stored_item_and_keeps_its_kind() {
        let db = empty();
        let form = db.new_form().unwrap();
        form.set_kind(ItemKind::Event).unwrap();
        form.set_text(FormField::Title, "Lake trip".into()).unwrap();
        form.set_text(FormField::Date, "2026-11-01".into()).unwrap();
        db.save_form(form, NOW.into()).unwrap();
        let id = db.lock().unwrap().list_items().unwrap()[0].id.clone();

        let edit = db.edit_form(id.clone()).unwrap();
        let view = edit.view(NOW.into()).unwrap();
        assert!(view.editing);
        assert_eq!(view.kind, ItemKind::Event);
        // The kind is fixed when editing.
        edit.set_kind(ItemKind::Task).unwrap();
        edit.set_text(FormField::Title, "Lake weekend".into())
            .unwrap();
        assert_eq!(
            db.save_form(edit, NOW.into()).unwrap(),
            SaveOutcome::Saved {
                title: "Lake weekend".into()
            }
        );
        let item = db.lock().unwrap().get_item(&id).unwrap().unwrap();
        assert_eq!(item.title, "Lake weekend");
        assert_eq!(item.kind, Kind::Event);
        assert!(db.edit_form("no such id".into()).is_err());
    }

    #[test]
    fn week_start_is_saved_and_used_by_the_next_form() {
        let db = empty();
        db.set_week_start(WeekStart::Monday).unwrap();
        let form = db.new_form().unwrap();
        form.open_calendar(NOW.into()).unwrap();
        let cal = form.view(NOW.into()).unwrap().calendar.unwrap();
        assert_eq!(cal.week_start, WeekStart::Monday);
    }

    /// Adds an item through the form and returns its id.
    fn add(db: &Database, kind: ItemKind, title: &str, date: &str, repeat_steps: usize) -> String {
        let form = db.new_form().unwrap();
        form.set_kind(kind).unwrap();
        form.set_text(FormField::Title, title.into()).unwrap();
        form.set_text(FormField::Date, date.into()).unwrap();
        for _ in 0..repeat_steps {
            form.step_choice(FormField::Repeat, true).unwrap();
        }
        db.save_form(form, NOW.into()).unwrap();
        let items = db.lock().unwrap().list_items().unwrap();
        items.into_iter().find(|i| i.title == title).unwrap().id
    }

    #[test]
    fn completing_a_one_off_task() {
        let db = empty();
        let id = add(&db, ItemKind::Task, "Call mum", "today", 0);
        assert_eq!(
            db.complete_task(id.clone(), NOW.into()).unwrap(),
            CompleteOutcome::Done {
                title: "Call mum".into(),
                next_due: None
            }
        );
        let core = db.lock().unwrap();
        assert!(core.get_item(&id).unwrap().unwrap().is_completed());
        assert_eq!(core.list_completions().unwrap().len(), 1);
    }

    #[test]
    fn completing_a_daily_task_makes_the_next_one() {
        let db = empty();
        // One step from "none" is "daily".
        let id = add(&db, ItemKind::Task, "Stretch", "today", 1);
        assert_eq!(
            db.complete_task(id, NOW.into()).unwrap(),
            CompleteOutcome::Done {
                title: "Stretch".into(),
                next_due: Some("Fri Oct 23".into())
            }
        );
    }

    #[test]
    fn events_are_not_completed() {
        let db = empty();
        let id = add(&db, ItemKind::Event, "Lake trip", "2026-11-01", 0);
        assert_eq!(
            db.complete_task(id, NOW.into()).unwrap(),
            CompleteOutcome::NotATask
        );
        assert!(db.lock().unwrap().list_completions().unwrap().is_empty());
    }

    #[test]
    fn deleting_hides_the_item_and_twice_is_an_error() {
        let db = empty();
        let id = add(&db, ItemKind::Task, "Old idea", "", 0);
        db.delete_item(id.clone(), NOW.into()).unwrap();
        assert!(db.lock().unwrap().list_items().unwrap().is_empty());
        assert!(db.delete_item(id.clone(), NOW.into()).is_err());
        assert!(db.complete_task(id, NOW.into()).is_err());
    }
}
