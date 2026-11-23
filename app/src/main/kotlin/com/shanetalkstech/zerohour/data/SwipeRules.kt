package com.shanetalkstech.zerohour.data

import kotlin.math.abs

/**
 * The rules for swiping a list line (right for done, left for delete), kept
 * apart from Android and Compose so a plain JVM test can check every case
 * (see `SwipeRulesTest`).
 *
 * Why these rules exist: with Compose's own defaults, a line swiped at 40%
 * of its width, or flicked at any distance faster than a slow 125 dp per
 * second, acted. That made it easy to complete or delete something by
 * accident while scrolling. Now a swipe has to be a deliberate move:
 *
 * - dragged at least [SWIPE_DISTANCE] (60%) of the line's width, or
 * - flicked fast (at least [FLICK_SPEED_DP] dp per second, in the same
 *   direction) after being dragged at least [FLICK_MIN_DISTANCE] (30%).
 *
 * Anything less springs back. The gesture itself lives in `SwipeLine` in
 * `ui/Sections.kt`; it asks [swipeOutcome] where a released line goes, and
 * [swipeArmed] when to vibrate while the finger is still down.
 */

/** How much of the line's width a drag must cover for letting go to act. */
const val SWIPE_DISTANCE = 0.6f

/** How much of the line's width a flick must have covered before it counts. */
const val FLICK_MIN_DISTANCE = 0.3f

/**
 * How fast, in dp per second, a flick must be to count. Android's own
 * scrolling treats about 50 dp/s as the slowest fling; a deliberate flick of
 * the thumb is well over 1000.
 */
const val FLICK_SPEED_DP = 1000f

/** Where a released line goes. */
enum class SwipeOutcome {
    /** Back into place: nothing happens. */
    REST,

    /** Off to the right: the line's "done" action. */
    RIGHT,

    /** Off to the left: the line's "delete" action (which asks first). */
    LEFT,
}

/**
 * True when a line dragged [offset] pixels (positive is right) on a line
 * [width] pixels wide is far enough that letting go acts by distance alone.
 * The threshold itself counts. A line not laid out yet ([width] 0) never is.
 */
fun swipeArmed(offset: Float, width: Float): Boolean =
    width > 0f && abs(offset) >= width * SWIPE_DISTANCE

/**
 * Decides where a line goes when the finger lets go.
 *
 * @param offset how far the line is dragged, in pixels (positive is right).
 * @param width the line's width in pixels (0 before it is laid out).
 * @param velocity the finger's speed when it let go, in pixels per second
 *   (positive is right).
 * @param flickSpeed [FLICK_SPEED_DP] in pixels per second, for this screen.
 */
fun swipeOutcome(offset: Float, width: Float, velocity: Float, flickSpeed: Float): SwipeOutcome {
    if (width <= 0f || offset == 0f) return SwipeOutcome.REST
    // The side the line is on, should it act.
    val side = if (offset > 0f) SwipeOutcome.RIGHT else SwipeOutcome.LEFT
    // Far enough by distance alone (the speed does not matter, so a slow,
    // careful drag works, and so does a drag that stops before letting go).
    if (swipeArmed(offset, width)) return side
    // A flick: far enough to show intent, fast, and moving outwards (the
    // same sign as the offset), not a finger flicking the line back.
    val flicked = abs(offset) >= width * FLICK_MIN_DISTANCE &&
        abs(velocity) >= flickSpeed &&
        (velocity > 0f) == (offset > 0f)
    return if (flicked) side else SwipeOutcome.REST
}
