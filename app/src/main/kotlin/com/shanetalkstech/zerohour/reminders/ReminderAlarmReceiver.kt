package com.shanetalkstech.zerohour.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.nowText

/**
 * Receives the app's own reminder alarms (set by [ReminderScheduler]).
 *
 * It is declared in the manifest with `exported="false"`, so only Android's
 * alarm service, on the app's behalf, can send to it; no other app can.
 *
 * - [ReminderScheduler.ACTION_FIRE]: a reminder is due, so show it.
 * - [ReminderScheduler.ACTION_REFILL]: plans the next window again.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    /** Called by Android on the main thread when one of the alarms goes off. */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> finishInBackground { show(context, intent) }
            ReminderScheduler.ACTION_REFILL -> finishInBackground {
                ReminderScheduler.replan(context)
            }
        }
    }

    /**
     * Shows the reminder the alarm carries, if it still applies.
     *
     * Alarms are cancelled when their reminder is no longer planned, so this
     * is a second guard: never on the demo database, and only if core would
     * still plan the same reminder now (`reminderNow`). That drops a reminder
     * whose task was completed or deleted, whose date or event range moved,
     * whose event's reminders were stopped, or that was switched off,
     * including by a sync that came in after the alarm was set. What is shown
     * is core's current text and buttons, not the copy saved in the alarm.
     */
    private fun show(context: Context, intent: Intent) {
        val app = context.applicationContext
        val key = intent.getStringExtra(ReminderScheduler.EXTRA_KEY) ?: return
        if (DatabaseHolder.usesDemo(app)) return
        val db = DatabaseHolder.get(app)
        // `?: return`: nothing to show when core no longer plans it.
        val reminder = db.reminderNow(key, nowText()) ?: return
        ReminderNotifications.show(app, reminder, ReminderNotifications.accentOf(db))
    }
}
