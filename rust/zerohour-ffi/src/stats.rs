//! Everything the stats screen shows, worked out in one call.
//!
//! The numbers come from core's `stats::compute`, the same function the
//! terminal app's stats screen uses, so both apps always show the same totals,
//! streaks and weekly counts for the same database. This file reads the
//! database, calls `compute`, and copies the results into records UniFFI can
//! send to Kotlin.
//!
//! A few small decisions the terminal app makes while drawing are made here
//! too, so Kotlin does not have to repeat them: the on-time percentage text
//! ("93%", or "–" before anything is done), the Overdue card's colour (danger
//! when something is overdue, ok when nothing is), the two forms of each bar's
//! label ("10/05" and "05") and the order and colours of the priorities. The
//! words around the numbers ("Completed", "best 9") are the Android app's own
//! texts, kept in `strings.xml` like the rest of its words.

use zerohour_core::icons::priority_icon;
use zerohour_core::model::Priority;
use zerohour_core::stats;
use zerohour_core::view;

use crate::database::{Database, StoredSettings, parse_now};
use crate::error::FfiError;
use crate::screen::ColorSlot;

/// One bar of the "Completions per week" chart.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WeekBar {
    /// The week's Monday as month/day, "10/05": the label under a bar that is
    /// at least 5 columns wide (the terminal app's rule).
    pub long_label: String,
    /// Just the day of the month, "05": the label under a narrower bar.
    pub short_label: String,
    /// Completions during that Monday to Sunday.
    pub count: u32,
    /// True for the last bar, this week, which is drawn in the accent colour.
    pub current: bool,
}

/// How many completions had one priority, with how to draw it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PriorityCount {
    /// The priority icon (up arrow, circle or down arrow in Nerd mode).
    pub icon: String,
    /// "High", "Medium" or "Low".
    pub label: String,
    /// The priority's colour, the same one the Tasks box uses for it.
    pub slot: ColorSlot,
    /// Completions of tasks that had this priority when they were completed.
    pub count: u32,
}

/// Everything the stats screen draws, apart from the header and the tab bar,
/// which come from the main screen (Kotlin reads both together).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StatsScreen {
    /// Completions ever.
    pub total_all: u32,
    /// Completions this week (Monday to today).
    pub total_week: u32,
    /// Completions this calendar month.
    pub total_month: u32,
    /// Days in a row with at least one completion, up to today (or up to
    /// yesterday when nothing is done yet today: the day is not over).
    pub current_streak: u32,
    /// The longest such run ever.
    pub longest_streak: u32,
    /// Open tasks that are past due.
    pub overdue_count: u32,
    /// The Overdue card's number colour: danger when anything is overdue,
    /// ok when nothing is.
    pub overdue_slot: ColorSlot,
    /// Completions done by their due date (or that had none).
    pub on_time: u32,
    /// Completions done after their due date.
    pub late: u32,
    /// The share done on time as text, "93%", or "–" when nothing is done
    /// yet (so the screen never shows a meaningless "0%").
    pub on_time_text: String,
    /// The chart's bars, oldest week first; the last one is this week.
    pub weeks: Vec<WeekBar>,
    /// Completions by priority, in the terminal app's order: High, Medium, Low.
    pub by_priority: Vec<PriorityCount>,
}

// A third `#[uniffi::export] impl` block for `Database`; UniFFI gathers the
// methods from every block into the one Kotlin class.
#[uniffi::export]
impl Database {
    /// Builds the stats screen as of `now` (see `NOW_FORMAT`).
    ///
    /// Completions are stored in UTC, and core needs a time zone to decide
    /// which *local* day each one happened on (a task finished at 11pm counts
    /// for that evening, not for the next day in UTC). `chrono::Local` is the
    /// phone's time zone, the same one the terminal app passes. The offset in
    /// `now` would not do: it is right for today, but an hour off for
    /// completions on the other side of a daylight-saving change.
    pub fn stats_screen(&self, now: String) -> Result<StatsScreen, FfiError> {
        let now = parse_now(&now)?.local;

        // Read everything while holding the lock, then let it go (the inner
        // block ends, the guard drops) before the computing below.
        let (settings, items, completions) = {
            let db = self.lock()?;
            (
                StoredSettings::load(&db)?,
                db.list_items()?,
                db.list_completions()?,
            )
        };
        let mode = settings.icon_mode;
        let s = stats::compute(&completions, &items, now, &chrono::Local);

        // The same text the terminal app shows: `{:.0}` rounds to a whole
        // number. "–" is an en dash, as in the terminal app.
        let on_time_text = match s.on_time_rate() {
            Some(rate) => format!("{:.0}%", rate * 100.0),
            None => "–".to_string(),
        };
        let overdue_slot = if s.overdue_count > 0 {
            ColorSlot::Danger
        } else {
            ColorSlot::Ok
        };

        // `enumerate` numbers the weeks from 0, so the last one (this week)
        // is the one whose number is one less than the count.
        let week_count = s.per_week.len();
        let weeks = s
            .per_week
            .iter()
            .enumerate()
            .map(|(i, week)| WeekBar {
                long_label: week.week_start.format("%m/%d").to_string(),
                short_label: week.week_start.format("%d").to_string(),
                count: week.count,
                current: i + 1 == week_count,
            })
            .collect();

        let p = s.by_priority;
        let by_priority = [
            (Priority::High, p.high),
            (Priority::Medium, p.medium),
            (Priority::Low, p.low),
        ]
        .into_iter()
        .map(|(priority, count)| PriorityCount {
            icon: priority_icon(priority).get(mode).to_string(),
            label: priority.label().to_string(),
            slot: view::priority_slot(priority).into(),
            count,
        })
        .collect();

        Ok(StatsScreen {
            total_all: s.total_all,
            total_week: s.total_week,
            total_month: s.total_month,
            current_streak: s.current_streak,
            longest_streak: s.longest_streak,
            overdue_count: s.overdue_count,
            overdue_slot,
            on_time: s.on_time,
            late: s.late,
            on_time_text,
            weeks,
            by_priority,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use zerohour_core::demo::STREAK_DAYS;
    use zerohour_core::stats::WEEKS_SHOWN;

    /// A fixed "now" for the tests: Thu Oct 22 2026, 09:15.
    const NOW: &str = "2026-10-22T09:15:00+02:00";

    /// The demo database, seeded as of [`NOW`].
    fn demo() -> Arc<Database> {
        Database::open_demo(NOW.to_string()).unwrap()
    }

    #[test]
    fn demo_stats_match_core_demo_design() {
        let screen = demo().stats_screen(NOW.to_string()).unwrap();
        // Core's demo: a live streak of exactly STREAK_DAYS days, which is
        // also the best, and exactly one overdue task.
        assert_eq!(i64::from(screen.current_streak), STREAK_DAYS);
        assert_eq!(screen.longest_streak, screen.current_streak);
        assert_eq!(screen.overdue_count, 1);
        assert_eq!(screen.overdue_slot, ColorSlot::Danger);
        assert!(screen.total_all > 0);
        assert_eq!(screen.on_time + screen.late, screen.total_all);
        assert!(
            screen.on_time_text.ends_with('%'),
            "{}",
            screen.on_time_text
        );
    }

    #[test]
    fn numbers_are_core_stats_for_the_same_data() {
        let db = demo();
        let screen = db.stats_screen(NOW.to_string()).unwrap();
        // Compute directly from the same database with core, the way the
        // terminal app does, and compare.
        let (items, completions) = {
            let core = db.lock().unwrap();
            (core.list_items().unwrap(), core.list_completions().unwrap())
        };
        let now = parse_now(NOW).unwrap().local;
        let expected = stats::compute(&completions, &items, now, &chrono::Local);
        assert_eq!(screen.total_all, expected.total_all);
        assert_eq!(screen.total_week, expected.total_week);
        assert_eq!(screen.total_month, expected.total_month);
        let counts: Vec<u32> = screen.weeks.iter().map(|w| w.count).collect();
        let expected_counts: Vec<u32> = expected.per_week.iter().map(|w| w.count).collect();
        assert_eq!(counts, expected_counts);
        let p = expected.by_priority;
        let priorities: Vec<u32> = screen.by_priority.iter().map(|p| p.count).collect();
        assert_eq!(priorities, vec![p.high, p.medium, p.low]);
    }

    #[test]
    fn weeks_are_labelled_oldest_first_with_this_week_last() {
        let screen = demo().stats_screen(NOW.to_string()).unwrap();
        assert_eq!(screen.weeks.len(), WEEKS_SHOWN);
        // Thu Oct 22 2026 is in the week that starts on Monday Oct 19.
        let last = screen.weeks.last().unwrap();
        assert_eq!(last.long_label, "10/19");
        assert_eq!(last.short_label, "19");
        assert!(last.current);
        // Only the last bar is "this week". Eleven weeks before Oct 19 is
        // Monday Aug 3.
        assert_eq!(screen.weeks.iter().filter(|w| w.current).count(), 1);
        assert_eq!(screen.weeks[0].long_label, "08/03");
        assert_eq!(screen.weeks[0].short_label, "03");
    }

    #[test]
    fn priorities_come_high_medium_low_with_their_colours() {
        let screen = demo().stats_screen(NOW.to_string()).unwrap();
        let labels: Vec<&str> = screen
            .by_priority
            .iter()
            .map(|p| p.label.as_str())
            .collect();
        assert_eq!(labels, vec!["High", "Medium", "Low"]);
        let slots: Vec<ColorSlot> = screen.by_priority.iter().map(|p| p.slot).collect();
        assert_eq!(
            slots,
            vec![ColorSlot::Danger, ColorSlot::Warn, ColorSlot::Muted]
        );
    }

    #[test]
    fn an_empty_database_shows_zeroes_and_a_dash() {
        let path = std::env::temp_dir().join(format!(
            "zerohour-ffi-test-{}-stats-empty.db",
            std::process::id()
        ));
        let screen = Database::open(path.to_string_lossy().into_owned())
            .unwrap()
            .stats_screen(NOW.to_string())
            .unwrap();
        assert_eq!(screen.total_all, 0);
        assert_eq!(screen.current_streak, 0);
        assert_eq!(screen.overdue_count, 0);
        assert_eq!(screen.overdue_slot, ColorSlot::Ok);
        assert_eq!(screen.on_time_text, "–");
        assert_eq!(screen.weeks.len(), WEEKS_SHOWN);
        assert!(screen.weeks.iter().all(|w| w.count == 0));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_bad_now_is_explained_not_a_crash() {
        let result = demo().stats_screen("yesterday".to_string());
        assert!(matches!(result, Err(FfiError::InvalidInput { .. })));
    }
}
