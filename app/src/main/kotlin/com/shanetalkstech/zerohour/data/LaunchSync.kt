package com.shanetalkstech.zerohour.data

/**
 * The rule for "sync once when the app opens", kept apart from Android so a
 * plain JVM test can check every case (see `LaunchSyncTest`).
 *
 * A launch sync is a convenience, never a surprise: it runs only when the user
 * has set everything up and asked for it, and only when it needs no question.
 * The call that gathers these facts is [SyncRunner.startAtLaunch].
 *
 * @param syncOn sync is switched on in the saved settings.
 * @param onLaunch "On launch" is switched on (off by default).
 * @param demo the sample data is open; it never syncs.
 * @param firstSyncWarning this phone has never synced but already has items.
 *   The first sync always asks first (on the header or the Sync page), so it
 *   is never started at launch behind the user's back.
 * @param passwordSaved a password is saved, and can be decrypted, for the
 *   saved details. Without one the sync would only fail, so it is skipped.
 * @return true when the app should start a background sync now.
 */
fun shouldSyncAtLaunch(
    syncOn: Boolean,
    onLaunch: Boolean,
    demo: Boolean,
    firstSyncWarning: Boolean,
    passwordSaved: Boolean,
): Boolean = syncOn && onLaunch && !demo && !firstSyncWarning && passwordSaved
