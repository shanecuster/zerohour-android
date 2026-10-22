//! Everything the main screen shows, worked out in one call.
//!
//! The terminal app and the Android app must list the same things in the same
//! order with the same words and colours. Core's `view` module decides all of
//! that (which tasks and events, their order, "due in 3d" / "78 days", which
//! colour slot each text uses, the tagline), `icons` supplies the glyphs and
//! `banner` the big title letters. This file only calls those functions and
//! copies the results into records UniFFI can send to Kotlin, so Kotlin never
//! re-implements any of it: it just draws.
//!
//! Colours cross the bridge as a [`ColorSlot`] (the *name* of a theme colour),
//! never as a fixed colour, so the screen follows whichever theme is chosen.

use zerohour_core::banner;
use zerohour_core::icons::{Icon, priority_icon};
use zerohour_core::theme::{Slot, Theme};
use zerohour_core::view;

use crate::RgbColor;
use crate::database::{Database, DisplaySettings, StoredSettings, parse_now};
use crate::error::FfiError;

/// The name of a theme colour. Mirror of core's `Slot`; Kotlin looks the
/// colour up in the current theme's palette.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ColorSlot {
    /// The app background.
    Background,
    /// Normal text.
    Foreground,
    /// De-emphasized text.
    Muted,
    /// Box borders.
    Border,
    /// Highlights.
    Accent,
    /// The selected row's background.
    Selection,
    /// The Tasks colour.
    Task,
    /// The Events colour.
    Event,
    /// Good news (done, today!).
    Ok,
    /// Attention (due soon, medium priority).
    Warn,
    /// Problems (overdue, high priority).
    Danger,
}

/// Converts core's slot into the mirror. The names differ only where core
/// abbreviates (`Bg`, `Fg`).
impl From<Slot> for ColorSlot {
    /// One arm per slot; the compiler checks none is missed.
    fn from(slot: Slot) -> ColorSlot {
        match slot {
            Slot::Bg => ColorSlot::Background,
            Slot::Fg => ColorSlot::Foreground,
            Slot::Muted => ColorSlot::Muted,
            Slot::Border => ColorSlot::Border,
            Slot::Accent => ColorSlot::Accent,
            Slot::Selection => ColorSlot::Selection,
            Slot::Task => ColorSlot::Task,
            Slot::Event => ColorSlot::Event,
            Slot::Ok => ColorSlot::Ok,
            Slot::Warn => ColorSlot::Warn,
            Slot::Danger => ColorSlot::Danger,
        }
    }
}

/// A piece of text plus how to draw it. Mirror of core's `view::Label`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TextLabel {
    /// The text itself, e.g. "due in 3d". May be empty (a task with no date).
    pub text: String,
    /// Which theme colour to draw it in.
    pub slot: ColorSlot,
    /// Drawn bold (core uses this only for an event's "today!").
    pub bold: bool,
}

/// Converts core's label into the mirror.
impl From<view::Label> for TextLabel {
    /// Moves the text across (no copy) and converts the slot.
    fn from(label: view::Label) -> TextLabel {
        TextLabel {
            text: label.text,
            slot: label.slot.into(),
            bold: label.bold,
        }
    }
}

/// One line of the Tasks box.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TaskRow {
    /// The item's ID, for the edit and complete steps later.
    pub id: String,
    /// The title as listed (repeating tasks end with the repeat icon).
    pub title: String,
    /// The title exactly as typed, without the icon: for the delete box,
    /// which asks `Delete "title"?` as the terminal app does.
    pub plain_title: String,
    /// The title's colour: `Danger` when overdue, otherwise `Foreground`.
    pub title_slot: ColorSlot,
    /// The priority icon (up arrow, circle or down arrow in Nerd mode).
    pub priority_icon: String,
    /// The priority's colour (used for its icon and its name).
    pub priority_slot: ColorSlot,
    /// "High", "Medium" or "Low" (shown only on wide screens).
    pub priority_name: String,
    /// The due date, e.g. "Fri Dec 25" (empty when there is none; shown only
    /// on very wide screens).
    pub date_text: String,
    /// The "due in 3d" text and its colour.
    pub due: TextLabel,
    /// The task's notes, if it has any.
    pub notes: Option<String>,
}

/// One line of the Events box.
///
/// Not to be confused with core's `view::EventRow` (the event plus its
/// countdown in days), which this is built from.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EventRow {
    /// The item's ID.
    pub id: String,
    /// The event's title.
    pub title: String,
    /// The date counted down to, e.g. "Fri Dec 25" (very wide screens only).
    pub date_text: String,
    /// The "78 days" countdown and its colour.
    pub countdown: TextLabel,
    /// "↻ yearly", or empty for one-off events (wide screens only).
    pub repeat_text: String,
    /// The event's notes, if it has any.
    pub notes: Option<String>,
}

/// The big "zerohour" title and the line under it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Banner {
    /// The rows of the letters (3 half-block rows, or 4 ASCII rows in ASCII
    /// mode). Every row has the same number of characters.
    pub lines: Vec<String>,
    /// One colour per column of the letters: a gradient from the theme's
    /// accent colour at the left edge to its event colour at the right edge,
    /// the same as the terminal app.
    pub column_colors: Vec<RgbColor>,
    /// "7 days to Christmas", or today's date when nothing is coming up.
    pub tagline: String,
}

/// Everything the main screen draws.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MainScreen {
    /// The display settings (theme, icons, banner, layout).
    pub settings: DisplaySettings,
    /// True for the in-memory demo database (the header says "demo").
    pub demo: bool,
    /// Today's date for the header, e.g. "Thu Oct 22 2026".
    pub today: String,
    /// The banner letters and the tagline.
    pub banner: Banner,
    /// The marker icon for tasks (Tasks box title and each task line).
    pub task_icon: String,
    /// The marker icon for events (Events box title and each event line).
    pub event_icon: String,
    /// The stats icon (the tab bar's Stats tab).
    pub stats_icon: String,
    /// The open tasks, already sorted for the screen.
    pub tasks: Vec<TaskRow>,
    /// The visible events, already sorted soonest first.
    pub events: Vec<EventRow>,
}

/// The banner gradient colour for one column, as in the terminal app: the
/// accent colour at column 0, the event colour at the last column, and evenly
/// mixed in between (core's `Rgb::lerp` does the mixing).
fn column_color(theme: &Theme, column: usize, width: usize) -> RgbColor {
    // How far across the banner this column is: 0.0 at the left, 1.0 at the
    // right. `width.max(2) - 1` is never 0, so this never divides by zero.
    let t = column as f32 / (width.max(2) - 1) as f32;
    theme.accent.lerp(theme.event, t).into()
}

// A second `#[uniffi::export] impl` block for the same object is fine: UniFFI
// gathers the methods from every block into the one Kotlin class.
#[uniffi::export]
impl Database {
    /// Builds the main screen as of `now` (see `NOW_FORMAT`).
    ///
    /// `narrow` asks for the short texts ("3d" instead of "due in 3d"); Kotlin
    /// sets it when the screen is under 60 columns wide, the same limit as the
    /// terminal app, which in practice means a phone held upright.
    pub fn main_screen(&self, now: String, narrow: bool) -> Result<MainScreen, FfiError> {
        let now = parse_now(&now)?.local;
        let today = now.date();

        // Read everything needed while holding the lock, then let it go (the
        // inner block ends, the guard drops) before the text building below.
        let (settings, items) = {
            let db = self.lock()?;
            (StoredSettings::load(&db)?, db.list_items()?)
        };
        let mode = settings.icon_mode;
        let theme = settings.theme;

        // Tasks: core picks the open ones and sorts them; each line's texts
        // and colour slots also come from core.
        let tasks = view::open_tasks(&items)
            .into_iter()
            .map(|task| TaskRow {
                title: view::task_title(&task, mode),
                title_slot: view::task_title_slot(&task, now).into(),
                priority_icon: priority_icon(task.priority).get(mode).to_string(),
                priority_slot: view::priority_slot(task.priority).into(),
                priority_name: task.priority.label().to_string(),
                date_text: view::date_text(task.date, today),
                due: view::due_label(&task, now, narrow, mode, settings.time_format).into(),
                plain_title: task.title.clone(),
                // The fields used above only borrowed `task`; these two move
                // its strings out, so they come last.
                id: task.id,
                notes: task.notes,
            })
            .collect();

        // Events: `false` = hide passed one-off events (the terminal app's `p`
        // key shows them; the phone has no such switch yet).
        let event_rows = view::event_rows(&items, today, false);
        // The tagline needs the sorted rows, so work it out before they are
        // consumed below.
        let tagline = view::tagline_text(view::next_upcoming(&event_rows), today);
        let events = event_rows
            .into_iter()
            .map(|row| EventRow {
                date_text: view::date_text(row.target, today),
                countdown: view::countdown_label(row.days, narrow).into(),
                repeat_text: view::repeat_text(&row.item, mode),
                id: row.item.id,
                title: row.item.title,
                notes: row.item.notes,
            })
            .collect();

        let width = banner::width(mode);
        let banner = Banner {
            lines: banner::lines(mode).iter().map(|l| l.to_string()).collect(),
            column_colors: (0..width).map(|c| column_color(theme, c, width)).collect(),
            tagline,
        };

        Ok(MainScreen {
            settings: settings.to_display(),
            demo: self.is_demo(),
            today: view::long_date(today),
            banner,
            task_icon: Icon::Task.get(mode).to_string(),
            event_icon: Icon::Event.get(mode).to_string(),
            stats_icon: Icon::Stats.get(mode).to_string(),
            tasks,
            events,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use zerohour_core::theme::THEMES;

    /// A fixed "now" for the tests: Thu Oct 22 2026, 09:15.
    const NOW: &str = "2026-10-22T09:15:00+02:00";

    /// The demo database, seeded as of [`NOW`].
    fn demo() -> Arc<Database> {
        Database::open_demo(NOW.to_string()).unwrap()
    }

    #[test]
    fn demo_screen_lists_core_sample_data() {
        let screen = demo().main_screen(NOW.to_string(), true).unwrap();
        // Core's demo: eight open tasks and five upcoming events.
        assert_eq!(screen.tasks.len(), 8);
        assert_eq!(screen.events.len(), 5);
        assert!(screen.demo);
        assert_eq!(screen.today, "Thu Oct 22 2026");
        // Sorted by core: a High task comes first.
        assert_eq!(screen.tasks[0].priority_name, "High");
        // The overdue sample task is drawn in the danger colour.
        let bill = screen
            .tasks
            .iter()
            .find(|t| t.title == "Pay electricity bill")
            .unwrap();
        assert_eq!(bill.title_slot, ColorSlot::Danger);
        assert_eq!(bill.due.slot, ColorSlot::Danger);
        // Every event is upcoming, so the tagline counts down to one.
        assert!(
            screen.banner.tagline.contains(&screen.events[0].title),
            "{}",
            screen.banner.tagline
        );
    }

    #[test]
    fn narrow_mode_asks_core_for_the_short_texts() {
        let db = demo();
        let wide = db.main_screen(NOW.to_string(), false).unwrap();
        let narrow = db.main_screen(NOW.to_string(), true).unwrap();
        // Same rows in the same order either way...
        assert_eq!(wide.tasks.len(), narrow.tasks.len());
        // ...but every non-empty narrow due text is no longer than its wide one,
        // and at least one is strictly shorter.
        let pairs: Vec<(&str, &str)> = wide
            .tasks
            .iter()
            .zip(&narrow.tasks)
            .map(|(w, n)| (w.due.text.as_str(), n.due.text.as_str()))
            .collect();
        assert!(
            pairs
                .iter()
                .all(|(w, n)| n.chars().count() <= w.chars().count())
        );
        assert!(
            pairs
                .iter()
                .any(|(w, n)| n.chars().count() < w.chars().count())
        );
    }

    #[test]
    fn banner_gradient_runs_from_accent_to_event() {
        let screen = demo().main_screen(NOW.to_string(), true).unwrap();
        let banner = &screen.banner;
        let width = banner.lines[0].chars().count();
        assert_eq!(banner.column_colors.len(), width);
        // The demo database uses the default theme, which is first in the table.
        let theme = &THEMES[0];
        assert_eq!(banner.column_colors[0], RgbColor::from(theme.accent));
        assert_eq!(banner.column_colors[width - 1], RgbColor::from(theme.event));
    }

    #[test]
    fn icons_follow_the_saved_icon_mode() {
        let path = std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-screen-ascii.db",
            std::process::id()
        ));
        {
            let core = zerohour_core::db::Db::open(&path).unwrap();
            core.set_setting(zerohour_core::db::settings_keys::ICON_MODE, "ascii")
                .unwrap();
        }
        let screen = Database::open(path.to_string_lossy().into_owned())
            .unwrap()
            .main_screen(NOW.to_string(), true)
            .unwrap();
        assert_eq!(screen.task_icon, "[ ]");
        assert_eq!(screen.event_icon, "(o)");
        assert_eq!(screen.stats_icon, "#");
        // ASCII mode uses the 4-row ASCII letters.
        assert_eq!(screen.banner.lines.len(), 4);
        // A fresh database has nothing in it, and the tagline is the date.
        assert!(screen.tasks.is_empty() && screen.events.is_empty());
        assert_eq!(screen.banner.tagline, "Thu Oct 22 2026");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn every_core_slot_has_its_own_mirror() {
        // `Slot::ALL` lists each slot once; their mirrors must all differ too.
        let mirrors: Vec<ColorSlot> = Slot::ALL.iter().map(|&s| s.into()).collect();
        for (i, a) in mirrors.iter().enumerate() {
            for b in &mirrors[i + 1..] {
                assert_ne!(a, b);
            }
        }
    }
}
