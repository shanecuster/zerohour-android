package com.shanetalkstech.zerohour.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shanetalkstech.zerohour.data.DatabaseHolder

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
     * Shows the reminder the alarm carries, after checking it still applies.
     *
     * Alarms are cancelled when their reminder is no longer planned, so the
     * checks here are a second guard: never on the demo database, never with
     * reminders switched off, and never for a task that has been completed
     * or deleted meanwhile (for example by a sync that came in after the
     * alarm was set, or an alarm whose cancel was missed).
     */
    private fun show(context: Context, intent: Intent) {
        val app = context.applicationContext
        val key = intent.getStringExtra(ReminderScheduler.EXTRA_KEY) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY) ?: ""
        // Null for the digest, which is about several tasks.
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID)
        if (DatabaseHolder.usesDemo(app)) return
        val db = DatabaseHolder.get(app)
        if (!db.reminderSettings().enabled) return
        if (itemId != null && !db.isOpenTask(itemId)) return
        ReminderNotifications.show(app, key, itemId, title, body, ReminderNotifications.accentOf(db))
    }
}
