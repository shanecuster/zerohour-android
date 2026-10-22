//! The add/edit form and its calendar picker, as Kotlin sees them.
//!
//! The form's logic is core's (`zerohour_core::form`): which fields appear for
//! a task or an event and for each repeat choice, the order the choices cycle
//! in, the weekday presets, the "→ Fri Oct 23 2026 · tomorrow" previews, and
//! validation with the same error messages as the terminal app. The calendar's
//! month grid is core's too (`zerohour_core::calendar`).
//!
//! This file wraps that in an [`ItemForm`] object. Kotlin keeps one per open
//! form, tells it what the user did (typed in a field, tapped a `›` arrow,
//! ticked a day, picked a date), and after each change asks for a fresh
//! [`FormView`]: the rows to draw, with their labels, values, previews and
//! colour slots. Kotlin never decides any of that itself.
//!
//! None of these methods touch the database, so they are quick enough to call
//! straight from the UI thread while the user types. Opening a form (which
//! reads settings or the item) and saving it are `Database` methods, in
//! `items.rs`, and run on a background thread.

use std::sync::{Mutex, MutexGuard};

use chrono::{Datelike, NaiveDate, Weekday};
use zerohour_core::calendar::{
    WeekStart as CoreWeekStart, add_months, month_grid, month_title, same_month,
};
use zerohour_core::clock::TimeFormat;
use zerohour_core::form::{Field, FormInput, RepeatChoice};
use zerohour_core::model::{Kind, Priority, Unit};
use zerohour_core::repeat::parse_date_input;
use zerohour_core::theme::Slot;

use crate::database::parse_now;
use crate::error::FfiError;
use crate::screen::{ColorSlot, TextLabel};

// ---------------------------------------------------------------------------
// Mirror enums
// ---------------------------------------------------------------------------

/// Task or Event. Mirror of core's `Kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ItemKind {
    /// Something to do, optionally with a due date.
    Task,
    /// A date to count down to.
    Event,
}

/// Converts core's kind into the mirror.
impl From<Kind> for ItemKind {
    /// One arm per kind.
    fn from(kind: Kind) -> ItemKind {
        match kind {
            Kind::Task => ItemKind::Task,
            Kind::Event => ItemKind::Event,
        }
    }
}

/// Converts the mirror back into core's kind.
impl From<ItemKind> for Kind {
    /// One arm per kind.
    fn from(kind: ItemKind) -> Kind {
        match kind {
            ItemKind::Task => Kind::Task,
            ItemKind::Event => Kind::Event,
        }
    }
}

/// One input of the form. Mirror of core's `form::Field`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FormField {
    /// Free text: the title.
    Title,
    /// Choice (tasks only): Low, Medium or High.
    Priority,
    /// Free text: the date as typed ("fri", "12/25", "+3d").
    Date,
    /// Free text (tasks only): the due time as typed ("2pm").
    Time,
    /// Choice: none, daily, weekdays, ...
    Repeat,
    /// Seven day checkboxes, for the weekday repeats.
    Days,
    /// Free text, digits only: the N of "every N", for the custom repeat.
    EveryN,
    /// Choice: days, weeks or months, for the custom repeat.
    EveryUnit,
    /// Checkbox (one-off events only): keep showing after the date passes.
    KeepAfterPass,
    /// Free text: notes.
    Notes,
}

/// Converts core's field into the mirror.
impl From<Field> for FormField {
    /// One arm per field; the compiler checks none is missed.
    fn from(field: Field) -> FormField {
        match field {
            Field::Title => FormField::Title,
            Field::Priority => FormField::Priority,
            Field::Date => FormField::Date,
            Field::Time => FormField::Time,
            Field::Repeat => FormField::Repeat,
            Field::Days => FormField::Days,
            Field::EveryN => FormField::EveryN,
            Field::EveryUnit => FormField::EveryUnit,
            Field::KeepAfterPass => FormField::KeepAfterPass,
            Field::Notes => FormField::Notes,
        }
    }
}

/// Which day the calendar's weeks (and the day checkboxes) start on. Mirror
/// of core's `calendar::WeekStart`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WeekStart {
    /// Sunday first (the default).
    Sunday,
    /// Monday first.
    Monday,
}

/// Converts core's week start into the mirror.
impl From<CoreWeekStart> for WeekStart {
    /// One arm per choice.
    fn from(start: CoreWeekStart) -> WeekStart {
        match start {
            CoreWeekStart::Sunday => WeekStart::Sunday,
            CoreWeekStart::Monday => WeekStart::Monday,
        }
    }
}

/// Converts the mirror back into core's week start.
impl From<WeekStart> for CoreWeekStart {
    /// One arm per choice.
    fn from(start: WeekStart) -> CoreWeekStart {
        match start {
            WeekStart::Sunday => CoreWeekStart::Sunday,
            WeekStart::Monday => CoreWeekStart::Monday,
        }
    }
}

// ---------------------------------------------------------------------------
// What Kotlin draws
// ---------------------------------------------------------------------------

/// One day checkbox in the Days row.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DayBox {
    /// Two-letter name, e.g. "Mo".
    pub label: String,
    /// True when the box is ticked.
    pub on: bool,
}

/// One row of the form.
///
/// Every row has a label; what else is filled in depends on [`FormField`]:
/// text fields and choice fields use `value`, the Days row uses `days`, the
/// keep-after-pass row uses `checked`. The date and time rows also have a
/// `preview` line under them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FieldRow {
    /// Which input this is (Kotlin picks how to draw it from this).
    pub field: FormField,
    /// The label, e.g. "Due date" for a task, "Date" for an event (core's words).
    pub label: String,
    /// Text fields: the text as typed. Choice fields: the chosen option's name
    /// ("Medium", "Mon–Thu", "weeks"). Empty for the other rows.
    pub value: String,
    /// The Days row's seven checkboxes, in the calendar's weekday order.
    /// Empty for the other rows.
    pub days: Vec<DayBox>,
    /// The keep-after-pass checkbox. False for the other rows.
    pub checked: bool,
    /// The line under the date or time: how the typed text was understood, or
    /// how to type one, with its colour. `None` for the other rows.
    pub preview: Option<TextLabel>,
}

/// One cell of the calendar's month grid.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarDay {
    /// The date in ISO form ("2026-10-22"), handed back to [`ItemForm::pick_date`].
    pub iso: String,
    /// The day of the month, 1 to 31.
    pub day: u32,
    /// The colour: muted for other months and past days, `Ok` for the date
    /// already in the field, `Accent` for today, otherwise the normal text.
    pub slot: ColorSlot,
    /// Bold: the date already in the field.
    pub bold: bool,
    /// Underlined: today.
    pub underline: bool,
}

/// The calendar picker, while it is open.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarView {
    /// The month shown, e.g. "October 2026".
    pub title: String,
    /// The seven column headers, e.g. "Su", "Mo", ...
    pub day_labels: Vec<String>,
    /// Which day the weeks start on (shown as the "week: Su" switch).
    pub week_start: WeekStart,
    /// Six weeks of seven days (always 6, so the box never changes size).
    pub weeks: Vec<Vec<CalendarDay>>,
}

/// Everything the form screen draws.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FormView {
    /// Task or Event (chosen on the first step when adding; fixed when editing).
    pub kind: ItemKind,
    /// True when editing an existing item (the box says "Edit" not "New").
    pub editing: bool,
    /// The rows to show, top to bottom (core decides which).
    pub rows: Vec<FieldRow>,
    /// The calendar picker, or `None` when it is closed.
    pub calendar: Option<CalendarView>,
}

// ---------------------------------------------------------------------------
// The form object
// ---------------------------------------------------------------------------

/// Everything one open form holds.
pub(crate) struct FormSession {
    /// The values typed and chosen so far (core's type, shared with the TUI).
    pub input: FormInput,
    /// 12- or 24-hour times, for the time preview.
    pub time_format: TimeFormat,
    /// The calendar's (and the day checkboxes') first weekday.
    pub week_start: CoreWeekStart,
    /// While the calendar is open: a date in the month it shows. `None` when
    /// it is closed.
    pub calendar_month: Option<NaiveDate>,
}

/// An open add or edit form.
///
/// Created by `Database::new_form` or `Database::edit_form` and saved with
/// `Database::save_form` (see `items.rs`). Like [`crate::database::Database`],
/// it keeps its state behind a `Mutex`, because UniFFI objects may be used
/// from any thread.
#[derive(uniffi::Object)]
pub struct ItemForm {
    /// The form's state, behind the lock.
    session: Mutex<FormSession>,
}

/// Helpers used only inside this crate.
impl ItemForm {
    /// Wraps a session into a new form object.
    pub(crate) fn new(session: FormSession) -> ItemForm {
        ItemForm {
            session: Mutex::new(session),
        }
    }

    /// Takes the lock (see `Database::lock` for why it returns a `Result`).
    pub(crate) fn lock(&self) -> Result<MutexGuard<'_, FormSession>, FfiError> {
        self.session.lock().map_err(|_| FfiError::InvalidInput {
            detail: "the form's lock was poisoned by an earlier crash".to_string(),
        })
    }
}

/// Parses a date that Kotlin sends back from a [`CalendarDay`].
fn parse_iso(iso: &str) -> Result<NaiveDate, FfiError> {
    NaiveDate::parse_from_str(iso, "%Y-%m-%d").map_err(|e| FfiError::InvalidInput {
        detail: format!("\"{iso}\" is not a date like 2026-10-22 ({e})"),
    })
}

/// Moves one step through `list` from `current`, forward or back, wrapping
/// around at both ends (like the terminal app's ←/→ on a choice field).
/// `PartialEq` lets `position` compare the items; `Copy` lets us return one.
fn step_through<T: PartialEq + Copy>(list: &[T], current: T, forward: bool) -> T {
    let len = list.len();
    // Where the current value is; the first entry if it is somehow missing.
    let i = list.iter().position(|&x| x == current).unwrap_or(0);
    // Adding `len - 1` is the same as subtracting 1, without going below 0.
    let next = if forward {
        (i + 1) % len
    } else {
        (i + len - 1) % len
    };
    list[next]
}

#[uniffi::export]
impl ItemForm {
    /// Chooses Task or Event (the first step when adding). Ignored when
    /// editing, because an item's kind can't change.
    pub fn set_kind(&self, kind: ItemKind) -> Result<(), FfiError> {
        let mut s = self.lock()?;
        if s.input.editing.is_none() {
            s.input.kind = kind.into();
        }
        Ok(())
    }

    /// Replaces the text of a text field with what is now in Kotlin's text
    /// box. The "every N" box keeps only digits, as in the terminal app.
    /// Calls for non-text fields are ignored.
    pub fn set_text(&self, field: FormField, text: String) -> Result<(), FfiError> {
        let mut s = self.lock()?;
        let input = &mut s.input;
        match field {
            FormField::Title => input.title = text,
            FormField::Date => input.date_text = text,
            FormField::Time => input.time_text = text,
            FormField::EveryN => {
                input.every_n = text.chars().filter(char::is_ascii_digit).collect()
            }
            FormField::Notes => input.notes = text,
            _ => {}
        }
        Ok(())
    }

    /// One tap on a choice field's `›` (`forward = true`) or `‹` arrow: steps
    /// Priority, Repeat or Unit through core's list, wrapping round. On the
    /// keep-after-pass checkbox, either arrow (or a tap) flips it.
    pub fn step_choice(&self, field: FormField, forward: bool) -> Result<(), FfiError> {
        let mut s = self.lock()?;
        let input = &mut s.input;
        match field {
            FormField::Priority => {
                input.priority = step_through(&Priority::ALL, input.priority, forward);
            }
            FormField::Repeat => {
                // `set_repeat` also fills in a preset's days.
                let choice = step_through(&RepeatChoice::ALL, input.repeat, forward);
                input.set_repeat(choice);
            }
            FormField::EveryUnit => input.unit = step_through(&Unit::ALL, input.unit, forward),
            FormField::KeepAfterPass => input.keep_after_pass = !input.keep_after_pass,
            _ => {}
        }
        Ok(())
    }

    /// Flips one day checkbox. `index` counts from 0 in the order the boxes
    /// are shown (which follows the week start). Core then renames the repeat
    /// to the matching preset ("weekdays", "Mon–Thu") or "pick days".
    pub fn toggle_day(&self, index: u32) -> Result<(), FfiError> {
        let mut s = self.lock()?;
        let order: [Weekday; 7] = s.week_start.weekdays();
        let day = order
            .get(index as usize)
            .copied()
            .ok_or_else(|| FfiError::InvalidInput {
                detail: format!("day index {index} is not 0 to 6"),
            })?;
        s.input.toggle_day(day);
        Ok(())
    }

    /// Opens the calendar on the month of the date typed so far, or on
    /// today's month if the field is empty or not a date yet.
    pub fn open_calendar(&self, now: String) -> Result<(), FfiError> {
        let today = parse_now(&now)?.local.date();
        let mut s = self.lock()?;
        let typed = parse_date_input(&s.input.date_text, today).ok().flatten();
        s.calendar_month = Some(typed.unwrap_or(today));
        Ok(())
    }

    /// Closes the calendar without changing the date.
    pub fn close_calendar(&self) -> Result<(), FfiError> {
        self.lock()?.calendar_month = None;
        Ok(())
    }

    /// Shows the month `months` away (−1 back, +1 forward). Does nothing while
    /// the calendar is closed.
    pub fn shift_calendar(&self, months: i32) -> Result<(), FfiError> {
        let mut s = self.lock()?;
        // `if let Some(x) = &mut ...` changes the date in place, if there is one.
        if let Some(month) = &mut s.calendar_month {
            *month = add_months(*month, months);
        }
        Ok(())
    }

    /// Jumps the calendar back to today's month.
    pub fn calendar_to_today(&self, now: String) -> Result<(), FfiError> {
        let today = parse_now(&now)?.local.date();
        let mut s = self.lock()?;
        if s.calendar_month.is_some() {
            s.calendar_month = Some(today);
        }
        Ok(())
    }

    /// Switches the first day of the week (Sunday ↔ Monday) and returns the
    /// new choice, which the app then saves with `Database::set_week_start`
    /// (as the terminal app does when you press `w` in its calendar).
    pub fn toggle_week_start(&self) -> Result<WeekStart, FfiError> {
        let mut s = self.lock()?;
        s.week_start = s.week_start.toggled();
        Ok(s.week_start.into())
    }

    /// A tap on a day: puts that date in the field (in ISO form, as core
    /// writes picked dates) and closes the calendar.
    pub fn pick_date(&self, iso: String) -> Result<(), FfiError> {
        let date = parse_iso(&iso)?;
        let mut s = self.lock()?;
        s.input.set_date(date);
        s.calendar_month = None;
        Ok(())
    }

    /// Everything to draw, as of `now` (the previews say "tomorrow", "in 3
    /// days" and so on, and the calendar marks today).
    pub fn view(&self, now: String) -> Result<FormView, FfiError> {
        let today = parse_now(&now)?.local.date();
        let s = self.lock()?;
        let input = &s.input;
        let kind = input.kind;

        let rows = input
            .fields()
            .into_iter()
            .map(|field| {
                // Start with an empty row and fill in what this field uses.
                // `..` below copies the rest of `blank` into each variant.
                let blank = FieldRow {
                    field: field.into(),
                    label: field.label(kind).to_string(),
                    value: String::new(),
                    days: Vec::new(),
                    checked: false,
                    preview: None,
                };
                match field {
                    Field::Title => FieldRow {
                        value: input.title.clone(),
                        ..blank
                    },
                    Field::Priority => FieldRow {
                        value: input.priority.label().to_string(),
                        ..blank
                    },
                    Field::Date => FieldRow {
                        value: input.date_text.clone(),
                        preview: Some(preview(input.date_preview(today))),
                        ..blank
                    },
                    Field::Time => FieldRow {
                        value: input.time_text.clone(),
                        preview: Some(preview(input.time_preview(s.time_format))),
                        ..blank
                    },
                    Field::Repeat => FieldRow {
                        value: input.repeat.label().to_string(),
                        ..blank
                    },
                    Field::Days => FieldRow {
                        // Pair each weekday (in display order) with its label.
                        days: s
                            .week_start
                            .weekdays()
                            .iter()
                            .zip(s.week_start.day_labels())
                            .map(|(&day, label)| DayBox {
                                label: label.to_string(),
                                on: input.days.contains(day),
                            })
                            .collect(),
                        ..blank
                    },
                    Field::EveryN => FieldRow {
                        value: input.every_n.clone(),
                        ..blank
                    },
                    Field::EveryUnit => FieldRow {
                        value: input.unit.as_str().to_string(),
                        ..blank
                    },
                    Field::KeepAfterPass => FieldRow {
                        checked: input.keep_after_pass,
                        ..blank
                    },
                    Field::Notes => FieldRow {
                        value: input.notes.clone(),
                        ..blank
                    },
                }
            })
            .collect();

        // The calendar, if open. The date already in the field is marked.
        let calendar = s.calendar_month.map(|month| {
            let current = parse_date_input(&input.date_text, today).ok().flatten();
            calendar_view(month, s.week_start, today, current)
        });

        Ok(FormView {
            kind: kind.into(),
            editing: input.editing.is_some(),
            rows,
            calendar,
        })
    }
}

/// Turns core's (text, slot) preview into a label for Kotlin.
fn preview((text, slot): (String, Slot)) -> TextLabel {
    TextLabel {
        text,
        slot: slot.into(),
        bold: false,
    }
}

/// The month grid for the month containing `month`, with each day styled the
/// way the terminal app's calendar styles it (`tui/src/ui/calendar.rs`),
/// minus the keyboard cursor (on a phone you tap the day you want).
fn calendar_view(
    month: NaiveDate,
    week_start: CoreWeekStart,
    today: NaiveDate,
    current: Option<NaiveDate>,
) -> CalendarView {
    let weeks = month_grid(month, week_start)
        .iter()
        .map(|week| {
            week.iter()
                .map(|&date| {
                    // Layered like the terminal app: later rules win.
                    let mut slot = if !same_month(date, month) || date < today {
                        Slot::Muted
                    } else {
                        Slot::Fg
                    };
                    let is_current = current == Some(date);
                    if is_current {
                        slot = Slot::Ok;
                    }
                    if date == today {
                        slot = Slot::Accent;
                    }
                    CalendarDay {
                        iso: date.format("%Y-%m-%d").to_string(),
                        day: date.day(),
                        slot: slot.into(),
                        bold: is_current,
                        underline: date == today,
                    }
                })
                .collect()
        })
        .collect();
    CalendarView {
        title: month_title(month),
        day_labels: week_start
            .day_labels()
            .iter()
            .map(|l| l.to_string())
            .collect(),
        week_start: week_start.into(),
        weeks,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Thu Oct 22 2026, 09:15, two hours ahead of UTC.
    const NOW: &str = "2026-10-22T09:15:00+02:00";

    /// A blank add form with the app's default settings.
    fn blank() -> ItemForm {
        ItemForm::new(FormSession {
            input: FormInput::default(),
            time_format: TimeFormat::Hour12,
            week_start: CoreWeekStart::Sunday,
            calendar_month: None,
        })
    }

    /// The fields shown, in order.
    fn fields(form: &ItemForm) -> Vec<FormField> {
        form.view(NOW.into())
            .unwrap()
            .rows
            .iter()
            .map(|r| r.field)
            .collect()
    }

    /// The row for one field.
    fn row(form: &ItemForm, field: FormField) -> FieldRow {
        form.view(NOW.into())
            .unwrap()
            .rows
            .into_iter()
            .find(|r| r.field == field)
            .unwrap()
    }

    #[test]
    fn task_and_event_show_core_fields_and_labels() {
        let form = blank();
        assert_eq!(
            fields(&form),
            [
                FormField::Title,
                FormField::Priority,
                FormField::Date,
                FormField::Time,
                FormField::Repeat,
                FormField::Notes
            ]
        );
        assert_eq!(row(&form, FormField::Date).label, "Due date");
        form.set_kind(ItemKind::Event).unwrap();
        assert!(fields(&form).contains(&FormField::KeepAfterPass));
        assert!(!fields(&form).contains(&FormField::Priority));
        assert_eq!(row(&form, FormField::Date).label, "Date");
    }

    #[test]
    fn previews_come_from_core_with_their_slots() {
        let form = blank();
        let empty = row(&form, FormField::Date).preview.unwrap();
        assert_eq!(empty.slot, ColorSlot::Muted);
        form.set_text(FormField::Date, "fri".into()).unwrap();
        let fri = row(&form, FormField::Date).preview.unwrap();
        assert_eq!(fri.text, "→ Fri Oct 23 2026 · tomorrow");
        assert_eq!(fri.slot, ColorSlot::Ok);
        form.set_text(FormField::Time, "14:30".into()).unwrap();
        assert_eq!(
            row(&form, FormField::Time).preview.unwrap().text,
            "→ 2:30 PM"
        );
        form.set_text(FormField::Date, "zzz".into()).unwrap();
        let bad = row(&form, FormField::Date).preview.unwrap();
        assert_eq!(bad.slot, ColorSlot::Warn);
    }

    #[test]
    fn choices_cycle_in_core_order_and_wrap() {
        let form = blank();
        form.step_choice(FormField::Priority, true).unwrap();
        assert_eq!(row(&form, FormField::Priority).value, "High");
        form.step_choice(FormField::Priority, true).unwrap();
        assert_eq!(row(&form, FormField::Priority).value, "Low", "wraps round");
        // Repeat back from "none" wraps to the last choice, "pick days".
        form.step_choice(FormField::Repeat, false).unwrap();
        assert_eq!(row(&form, FormField::Repeat).value, "pick days");
        assert!(fields(&form).contains(&FormField::Days));
    }

    #[test]
    fn day_boxes_follow_the_week_start_and_rename_the_preset() {
        let form = blank();
        // none -> daily -> weekdays
        form.step_choice(FormField::Repeat, true).unwrap();
        form.step_choice(FormField::Repeat, true).unwrap();
        let days = row(&form, FormField::Days).days;
        assert_eq!(days[0].label, "Su");
        assert!(!days[0].on && days[1].on && days[5].on);
        // Sunday-first: index 5 is Friday; turning it off leaves Mon–Thu.
        form.toggle_day(5).unwrap();
        assert_eq!(row(&form, FormField::Repeat).value, "Mon–Thu");
        assert!(form.toggle_day(7).is_err());
        // Monday-first order puts Monday first.
        form.toggle_week_start().unwrap();
        assert_eq!(row(&form, FormField::Days).days[0].label, "Mo");
    }

    #[test]
    fn every_n_keeps_only_digits() {
        let form = blank();
        form.set_text(FormField::EveryN, "1a2".into()).unwrap();
        // Not shown unless the repeat is custom, but stored all the same.
        assert_eq!(form.lock().unwrap().input.every_n, "12");
    }

    #[test]
    fn calendar_opens_on_the_typed_month_and_picks_a_date() {
        let form = blank();
        form.set_text(FormField::Date, "12/25".into()).unwrap();
        form.open_calendar(NOW.into()).unwrap();
        let cal = form.view(NOW.into()).unwrap().calendar.unwrap();
        assert_eq!(cal.title, "December 2026");
        assert_eq!(cal.weeks.len(), 6);
        assert!(cal.weeks.iter().all(|w| w.len() == 7));
        // Dec 25 is marked as the date already in the field.
        let xmas = cal
            .weeks
            .iter()
            .flatten()
            .find(|d| d.iso == "2026-12-25")
            .unwrap();
        assert!(xmas.bold);
        assert_eq!(xmas.slot, ColorSlot::Ok);
        // Back two months is October, where today is underlined.
        form.shift_calendar(-2).unwrap();
        let cal = form.view(NOW.into()).unwrap().calendar.unwrap();
        assert_eq!(cal.title, "October 2026");
        let today = cal.weeks.iter().flatten().find(|d| d.iso == "2026-10-22");
        assert!(today.unwrap().underline);
        // A tap fills the field in ISO form and closes the calendar.
        form.pick_date("2026-10-30".into()).unwrap();
        let view = form.view(NOW.into()).unwrap();
        assert!(view.calendar.is_none());
        assert_eq!(row(&form, FormField::Date).value, "2026-10-30");
        assert!(form.pick_date("30/10/2026".into()).is_err());
    }

    #[test]
    fn calendar_today_and_week_start() {
        let form = blank();
        form.open_calendar(NOW.into()).unwrap();
        form.shift_calendar(5).unwrap();
        form.calendar_to_today(NOW.into()).unwrap();
        let cal = form.view(NOW.into()).unwrap().calendar.unwrap();
        assert_eq!(cal.title, "October 2026");
        assert_eq!(cal.day_labels[0], "Su");
        assert_eq!(form.toggle_week_start().unwrap(), WeekStart::Monday);
        let cal = form.view(NOW.into()).unwrap().calendar.unwrap();
        assert_eq!(cal.day_labels[0], "Mo");
        assert_eq!(cal.week_start, WeekStart::Monday);
        form.close_calendar().unwrap();
        assert!(form.view(NOW.into()).unwrap().calendar.is_none());
    }
}
