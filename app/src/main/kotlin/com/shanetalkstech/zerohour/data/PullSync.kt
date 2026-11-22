package com.shanetalkstech.zerohour.data

/**
 * The rules for "pull down on a list to sync", kept apart from Android and
 * Compose so a plain JVM test can check every case (see `PullSyncTest`).
 *
 * The gesture itself lives in `ui/PullToSync.kt`; the main screen asks
 * [pullSyncAction] what a finished pull should do, and uses [pulledDistance]
 * and [pastThreshold] while the finger moves.
 */

/**
 * What a pull that went past the threshold does. An `enum class` is a fixed
 * list of named values, so a `when` over it must handle each one.
 */
enum class PullSyncAction {
    /** Start "Sync now", exactly as tapping the header indicator does. */
    START,

    /** Open the one-time "First sync" box first (a phone with items that never synced). */
    ASK_FIRST,

    /** A sync is already running: keep showing "syncing…" until it ends. */
    SHOW_RUNNING,

    /** Sync is switched off (or was never set up): say where to turn it on. */
    OFF,

    /** The demo database is open: it never syncs, so say so. */
    DEMO,
}

/**
 * Decides what a released pull does. Checked in this order:
 *
 * 1. the demo database never syncs (it also reports sync as off, so it comes
 *    first to get its own message);
 * 2. sync switched off;
 * 3. a sync already running (including the automatic one at launch);
 * 4. the first sync on a phone that already has items asks first, as the
 *    header tap does;
 * 5. otherwise, sync now. A missing password is not checked here:
 *    [SyncRunner] reports it, as it does for a tap.
 *
 * @param syncOn sync is switched on in the saved settings.
 * @param demo the sample data is open.
 * @param firstSyncWarning this phone has never synced but already has items.
 * @param running a sync is running right now.
 */
fun pullSyncAction(
    syncOn: Boolean,
    demo: Boolean,
    firstSyncWarning: Boolean,
    running: Boolean,
): PullSyncAction = when {
    demo -> PullSyncAction.DEMO
    !syncOn -> PullSyncAction.OFF
    running -> PullSyncAction.SHOW_RUNNING
    firstSyncWarning -> PullSyncAction.ASK_FIRST
    else -> PullSyncAction.START
}

/**
 * How much of the finger's movement the pull follows: half, so the list
 * feels as if it resists being pulled (as in other apps' pull-to-refresh).
 */
const val PULL_RESISTANCE = 0.5f

/**
 * The new pull distance, in pixels, after the finger moved [fingerDelta]
 * pixels (positive is down) from a pull of [current] pixels.
 *
 * Moving down adds half the movement ([PULL_RESISTANCE]); moving back up
 * takes it away again one-for-one, so the list follows the finger back
 * without lagging. `coerceIn` keeps the result between 0 (no pull) and
 * [maxDistance], so the pull never goes negative or runs off the screen.
 */
fun pulledDistance(current: Float, fingerDelta: Float, maxDistance: Float): Float {
    val change = if (fingerDelta > 0f) fingerDelta * PULL_RESISTANCE else fingerDelta
    return (current + change).coerceIn(0f, maxDistance)
}

/** True when a pull of [distance] pixels is far enough that letting go syncs. */
fun pastThreshold(distance: Float, threshold: Float): Boolean = distance >= threshold
