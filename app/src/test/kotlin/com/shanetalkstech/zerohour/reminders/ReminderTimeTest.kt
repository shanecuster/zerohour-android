package com.shanetalkstech.zerohour.reminders

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the daylight-saving policy ([fireAtMillis], [isStillAhead])
 * and the alarm request codes ([requestCodeFor]). They run on the computer's
 * own Java with `./gradlew :app:testDebugUnitTest`, no phone needed.
 *
 * The dates are the real 2026 clock changes: the US springs forward on
 * Sunday March 8 and falls back on Sunday November 1; the UK on March 29 and
 * October 25; Lord Howe Island moves by only half an hour, on October 4.
 */
class ReminderTimeTest {

    /** The UTC moment a floating time fires at in [zone], as ISO text. */
    private fun firesAt(fireAt: String, zone: String): String =
        Instant.ofEpochMilli(fireAtMillis(parseFireAt(fireAt), ZoneId.of(zone))).toString()

    @Test
    fun anOrdinaryDayUsesTheZonesOffset() {
        // Central daylight time is UTC-5.
        assertEquals("2026-10-26T19:00:00Z", firesAt("2026-10-26T14:00", "America/Chicago"))
    }

    @Test
    fun aTimeInTheSpringForwardGapMovesLaterByTheGap() {
        // 02:30 doesn't exist in New York that night: it fires at 03:30 EDT (UTC-4).
        assertEquals("2026-03-08T07:30:00Z", firesAt("2026-03-08T02:30", "America/New_York"))
        // The UK: 01:30 doesn't exist; 02:30 BST (UTC+1).
        assertEquals("2026-03-29T01:30:00Z", firesAt("2026-03-29T01:30", "Europe/London"))
    }

    @Test
    fun aTimeInTheFallBackOverlapTakesTheEarlierOffset() {
        // 01:30 happens twice in New York: the first is still EDT (UTC-4).
        assertEquals("2026-11-01T05:30:00Z", firesAt("2026-11-01T01:30", "America/New_York"))
        // The UK: the first 01:30 is still BST (UTC+1).
        assertEquals("2026-10-25T00:30:00Z", firesAt("2026-10-25T01:30", "Europe/London"))
    }

    @Test
    fun aHalfHourShiftMovesByHalfAnHour() {
        // Lord Howe goes from UTC+10:30 to UTC+11 at 02:00, skipping to 02:30:
        // 02:15 fires at 02:45 (+11), the previous day in UTC.
        assertEquals("2026-10-03T15:45:00Z", firesAt("2026-10-04T02:15", "Australia/Lord_Howe"))
    }

    @Test
    fun afterATimeZoneChangeTwoPmIsTwoPmInTheNewZone() {
        val chicago = firesAt("2026-10-26T14:00", "America/Chicago")
        val berlin = firesAt("2026-10-26T14:00", "Europe/Berlin")
        assertEquals("2026-10-26T13:00:00Z", berlin)
        assertNotEquals(chicago, berlin)
    }

    @Test
    fun theSecondPassThroughTheOverlapDoesNotFireAgain() {
        val zone = ZoneId.of("America/New_York")
        val reminder = fireAtMillis(parseFireAt("2026-11-01T01:30"), zone)
        // "Now" is 01:10 the second time round (EST, UTC-5): 06:10 UTC. The
        // 01:30 reminder already fired at 05:30 UTC, so it is not set again.
        val secondPass = Instant.parse("2026-11-01T06:10:00Z").toEpochMilli()
        assertFalse(isStillAhead(reminder, secondPass))
        // Before the first 01:30 (01:10 EDT, 05:10 UTC) it is still ahead.
        val firstPass = Instant.parse("2026-11-01T05:10:00Z").toEpochMilli()
        assertTrue(isStillAhead(reminder, firstPass))
        // Exactly now counts as passed, like core's own rule.
        assertFalse(isStillAhead(reminder, reminder))
    }

    @Test
    fun theFfiTimeTextIsReadWithoutSeconds() {
        assertEquals("2026-10-26T14:00", parseFireAt("2026-10-26T14:00").toString())
    }

    @Test
    fun requestCodesAreStableAndFollowTheKey() {
        val monday = "0f8e2a3c-0000-4000-8000-000000000001|Due|2026-10-26T14:00"
        val tuesday = "0f8e2a3c-0000-4000-8000-000000000001|Due|2026-10-27T14:00"
        // Pinned values: String.hashCode is fixed by the Java language, so
        // these never change between phones, runs or app updates. If this
        // test fails, alarms set by an older version could not be found.
        assertEquals(-2060512153, requestCodeFor(monday))
        assertEquals(-1173008472, requestCodeFor(tuesday))
        assertEquals(-1512894395, requestCodeFor("digest|Digest|2026-10-27T09:00"))
        // The same key always gives the same code (a re-plan replaces).
        assertEquals(requestCodeFor(monday), requestCodeFor(String(monday.toCharArray())))
        assertNotEquals(requestCodeFor(monday), requestCodeFor(tuesday))
    }
}
