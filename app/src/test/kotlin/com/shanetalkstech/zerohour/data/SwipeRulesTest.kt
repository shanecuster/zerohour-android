package com.shanetalkstech.zerohour.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [swipeOutcome] (where a released line goes) and [swipeArmed] (when
 * the phone vibrates) on a 1000-pixel line, with a flick speed of 3000
 * pixels per second (1000 dp/s on a screen with 3 pixels per dp).
 */
class SwipeRulesTest {

    private val width = 1000f
    private val flick = 3000f

    /** The outcome on the test line, with no speed unless a test sets one. */
    private fun released(offset: Float, velocity: Float = 0f) =
        swipeOutcome(offset, width, velocity, flick)

    /** 60% of the width acts, either way, however slowly. */
    @Test
    fun sixtyPercentActs() {
        assertEquals(SwipeOutcome.RIGHT, released(600f))
        assertEquals(SwipeOutcome.LEFT, released(-600f))
        assertEquals(SwipeOutcome.RIGHT, released(900f))
    }

    /** Less than 60% without a flick springs back (40% used to act). */
    @Test
    fun lessThanSixtyPercentSpringsBack() {
        assertEquals(SwipeOutcome.REST, released(599f))
        assertEquals(SwipeOutcome.REST, released(-400f))
        assertEquals(SwipeOutcome.REST, released(0f))
    }

    /** A fast flick outwards counts from 30%. */
    @Test
    fun aFastFlickPastThirtyPercentActs() {
        assertEquals(SwipeOutcome.RIGHT, released(300f, velocity = 3000f))
        assertEquals(SwipeOutcome.LEFT, released(-450f, velocity = -5000f))
    }

    /** A flick before 30% does nothing, however fast. */
    @Test
    fun aFlickBeforeThirtyPercentSpringsBack() {
        assertEquals(SwipeOutcome.REST, released(299f, velocity = 10000f))
        assertEquals(SwipeOutcome.REST, released(-100f, velocity = -10000f))
    }

    /** A slow flick is no flick (Compose's default counted 125 dp/s). */
    @Test
    fun aSlowFlickSpringsBack() {
        assertEquals(SwipeOutcome.REST, released(400f, velocity = 2999f))
    }

    /** Flicking the line back towards the middle never acts. */
    @Test
    fun aFlickBackSpringsBack() {
        assertEquals(SwipeOutcome.REST, released(400f, velocity = -5000f))
        assertEquals(SwipeOutcome.REST, released(-400f, velocity = 5000f))
        // Past 60% the distance alone decides, as the vibration promised.
        assertEquals(SwipeOutcome.RIGHT, released(700f, velocity = -5000f))
    }

    /** A line that has not been laid out yet never acts. */
    @Test
    fun noWidthNeverActs() {
        assertEquals(SwipeOutcome.REST, swipeOutcome(50f, 0f, 9000f, flick))
        assertFalse(swipeArmed(50f, 0f))
    }

    /** The vibration point is the 60% line, inclusive, on both sides. */
    @Test
    fun armedAtSixtyPercent() {
        assertTrue(swipeArmed(600f, width))
        assertTrue(swipeArmed(-600f, width))
        assertFalse(swipeArmed(599.9f, width))
        assertFalse(swipeArmed(-300f, width))
    }
}
