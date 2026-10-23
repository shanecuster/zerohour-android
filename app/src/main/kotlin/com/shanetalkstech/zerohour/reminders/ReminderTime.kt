package com.shanetalkstech.zerohour.reminders

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * The two small rules the alarm code depends on, kept free of Android types
 * so they can be checked by plain JVM unit tests (app/src/test), with no
 * phone or emulator:
 *
 * - when a reminder's floating wall-clock time really happens, in the phone's
 *   current time zone (the daylight-saving policy), and
 * - which number names its alarm (the request code).
 */

/**
 * The moment, in milliseconds since 1970 UTC (what `AlarmManager` wants), at
 * which a reminder planned for the wall-clock time [fireAt] fires in [zone]
 * (the phone's current time zone, `ZoneId.systemDefault()`).
 *
 * Core plans reminders as *floating* times, "2 PM on whatever clock the
 * phone shows", so the time zone is applied only here, when the alarm is set.
 * Twice a year the wall clock skips or repeats an hour, and the policy is:
 *
 * - **Spring forward** (a gap, e.g. 02:00 to 03:00 doesn't exist): the time
 *   moves later by the length of the gap, so 02:30 fires at 03:30.
 * - **Fall back** (an overlap, e.g. 01:00 to 02:00 happens twice): the
 *   earlier of the two offsets wins, so 01:30 fires the first time the clock
 *   shows 01:30.
 *
 * `ZonedDateTime.ofLocal` with no preferred offset (`null`) does exactly
 * that; this function exists so the rule has a name and tests.
 */
fun fireAtMillis(fireAt: LocalDateTime, zone: ZoneId): Long =
    ZonedDateTime.ofLocal(fireAt, zone, null).toInstant().toEpochMilli()

/**
 * True when an alarm at [fireAtMillis] is still ahead of [nowMillis], and so
 * worth setting.
 *
 * Core only plans times after "now", but it compares wall-clock times. In
 * the repeated hour after the clocks fall back, "now" may be 01:10 (the
 * second time round) while a 01:30 reminder already fired at the first
 * 01:30, an hour ago in real time. Its moment is then in the past, and an
 * alarm set in the past fires at once: this check stops that duplicate.
 */
fun isStillAhead(fireAtMillis: Long, nowMillis: Long): Boolean = fireAtMillis > nowMillis

/**
 * Reads a reminder's time as the FFI sends it, "2026-10-26T14:00" (local
 * date and time, no offset). `LocalDateTime.parse` reads ISO 8601, and the
 * seconds are optional there.
 */
fun parseFireAt(text: String): LocalDateTime = LocalDateTime.parse(text)

/**
 * The request code for the alarm of the reminder with core's [key] (e.g.
 * "<item id>|Due|2026-10-26T14:00"), and the ID of its notification.
 *
 * Android tells pending alarms apart by their intent and this number. The
 * same key always gives the same number, so planning again *replaces* an
 * alarm instead of adding a second one. `String.hashCode` is defined exactly
 * by the Java language (the characters combined with powers of 31), so it is
 * the same on every phone, in every run and after every app update.
 *
 * Two different keys could, very rarely, share a number. That is harmless:
 * each alarm's intent also carries the key itself as its data URI (see
 * `ReminderScheduler`), and Android treats intents with different data as
 * different alarms.
 */
fun requestCodeFor(key: String): Int = key.hashCode()
