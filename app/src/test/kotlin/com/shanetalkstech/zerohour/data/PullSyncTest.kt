package com.shanetalkstech.zerohour.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [pullSyncAction] (what a released pull does, one changed fact at a
 * time) and the pull's maths ([pulledDistance], [pastThreshold]).
 */
class PullSyncTest {

    /** The decision with sync set up and idle unless a test changes one fact. */
    private fun decide(
        syncOn: Boolean = true,
        demo: Boolean = false,
        firstSyncWarning: Boolean = false,
        running: Boolean = false,
    ) = pullSyncAction(syncOn, demo, firstSyncWarning, running)

    /** Everything set up and nothing running: sync now. */
    @Test
    fun startsWhenSetUpAndIdle() {
        assertEquals(PullSyncAction.START, decide())
    }

    /** Sync off (the default, so also "never set up"): say where to turn it on. */
    @Test
    fun saysOffWhenSyncIsOff() {
        assertEquals(PullSyncAction.OFF, decide(syncOn = false))
    }

    /** The demo gets its own message, even though it also reports sync as off. */
    @Test
    fun demoWinsOverOff() {
        assertEquals(PullSyncAction.DEMO, decide(demo = true))
        assertEquals(PullSyncAction.DEMO, decide(demo = true, syncOn = false))
    }

    /** A running sync (such as the one at launch) is shown, never started twice. */
    @Test
    fun showsARunningSync() {
        assertEquals(PullSyncAction.SHOW_RUNNING, decide(running = true))
        // While one runs, the first-sync question waits, as for a header tap.
        assertEquals(PullSyncAction.SHOW_RUNNING, decide(running = true, firstSyncWarning = true))
    }

    /** The first sync on a phone with items asks first. */
    @Test
    fun asksBeforeTheFirstSync() {
        assertEquals(PullSyncAction.ASK_FIRST, decide(firstSyncWarning = true))
    }

    /** Off wins over a running sync and the first-sync question. */
    @Test
    fun offWinsOverEverythingButTheDemo() {
        assertEquals(PullSyncAction.OFF, decide(syncOn = false, running = true, firstSyncWarning = true))
    }

    /** Pulling down follows half the finger's movement. */
    @Test
    fun pullingDownIsHalved() {
        assertEquals(50f, pulledDistance(0f, 100f, 1000f), 0f)
        assertEquals(70f, pulledDistance(50f, 40f, 1000f), 0f)
    }

    /** Moving back up takes the pull away one-for-one. */
    @Test
    fun movingBackUpIsNotHalved() {
        assertEquals(30f, pulledDistance(50f, -20f, 1000f), 0f)
    }

    /** The pull never goes below nothing, nor past its limit. */
    @Test
    fun pullStaysWithinItsLimits() {
        assertEquals(0f, pulledDistance(10f, -50f, 1000f), 0f)
        assertEquals(120f, pulledDistance(100f, 400f, 120f), 0f)
    }

    /** The threshold itself counts as reached; just short of it does not. */
    @Test
    fun thresholdIsInclusive() {
        assertTrue(pastThreshold(90f, 90f))
        assertTrue(pastThreshold(91f, 90f))
        assertFalse(pastThreshold(89.9f, 90f))
    }
}
