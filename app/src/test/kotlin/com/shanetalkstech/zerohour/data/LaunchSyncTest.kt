package com.shanetalkstech.zerohour.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [shouldSyncAtLaunch]: a launch sync runs only when everything is set
 * up and asked for, and each single missing piece stops it.
 */
class LaunchSyncTest {

    /** The decision with every fact "yes, go" unless a test changes one. */
    private fun decide(
        syncOn: Boolean = true,
        onLaunch: Boolean = true,
        demo: Boolean = false,
        firstSyncWarning: Boolean = false,
        passwordSaved: Boolean = true,
    ) = shouldSyncAtLaunch(syncOn, onLaunch, demo, firstSyncWarning, passwordSaved)

    /** Everything set up and "On launch" on: sync. */
    @Test
    fun syncsWhenEverythingIsSetUp() {
        assertTrue(decide())
    }

    /** "On launch" is off by default, and off means no launch sync. */
    @Test
    fun doesNotSyncWithOnLaunchOff() {
        assertFalse(decide(onLaunch = false))
    }

    /** Sync itself switched off wins over "On launch". */
    @Test
    fun doesNotSyncWithSyncOff() {
        assertFalse(decide(syncOn = false))
    }

    /** The demo never syncs. */
    @Test
    fun doesNotSyncTheDemo() {
        assertFalse(decide(demo = true))
    }

    /** A first sync always asks first, so it never starts at launch. */
    @Test
    fun doesNotStartAFirstSyncThatNeedsTheQuestion() {
        assertFalse(decide(firstSyncWarning = true))
    }

    /** No usable password: skipped quietly instead of failing. */
    @Test
    fun doesNotSyncWithoutAPassword() {
        assertFalse(decide(passwordSaved = false))
    }
}
