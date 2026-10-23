package com.shanetalkstech.zerohour.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the app's own reminder alarms (set by [ReminderScheduler]).
 *
 * It is declared in the manifest with `exported="false"`, so only Android's
 * alarm service, on the app's behalf, can send to it; no other app can.
 *
 * - [ReminderScheduler.ACTION_REFILL]: plans the next window again.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    /** Called by Android on the main thread when one of the alarms goes off. */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REFILL -> finishInBackground {
                ReminderScheduler.replan(context)
            }
        }
    }
}
