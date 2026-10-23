package com.shanetalkstech.zerohour.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-plans the reminder alarms after system events that make the set alarms
 * wrong or wipe them out:
 *
 * - `BOOT_COMPLETED`: Android forgets every alarm when the phone restarts.
 *   It arrives only after the phone is first unlocked (the database is in
 *   storage that stays locked until then), so nothing fires before that.
 * - `TIME_SET`: the clock was changed by hand.
 * - `TIMEZONE_CHANGED`: reminders are wall-clock times, so "2 PM" is a
 *   different moment in the new zone.
 * - `MY_PACKAGE_REPLACED`: the app was updated (sent to this app only).
 * - `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`: the user allowed
 *   exact alarms, so the inexact ones can be replaced. (When the permission
 *   is taken away, Android stops the app and cancels its exact alarms
 *   instead, with no broadcast; the next app start re-plans.)
 *
 * It must be `exported="true"` so that Android itself can deliver these. All
 * of them are *protected* broadcasts that only the system may send, and the
 * action is checked below anyway, so no other app can make it do more than
 * re-plan. They are also on Android 8's list of broadcasts a receiver in the
 * manifest may still get.
 */
class ReminderEventsReceiver : BroadcastReceiver() {

    /** Called by Android on the main thread with one of the events above. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in EVENTS) return
        finishInBackground { ReminderScheduler.replan(context) }
    }

    companion object {
        /** The actions this receiver acts on (the manifest lists the same). */
        private val EVENTS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, // its text is "android.intent.action.TIME_SET"
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
