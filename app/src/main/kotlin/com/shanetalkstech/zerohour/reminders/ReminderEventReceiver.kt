package com.shanetalkstech.zerohour.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DataChanges
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import uniffi.zerohour_ffi.FfiException

/**
 * Handles the buttons on an event's daily reminder (core chooses which ones
 * show; see [ReminderNotifications.show]):
 *
 * - **Stop** ("Stop reminders" / "Stop for this year"): stops the event's
 *   daily reminders for its current range, the same as the edit screen. Next
 *   year's range is reminded again by itself.
 * - **Keep** (last day only): nothing to change, since a repeating event's next
 *   range comes by itself; the notification just goes away.
 * - **Remove** (last day only): the first of two steps. The notification turns
 *   into "Remove "Advent of Cyber" for good?" with **Yes, remove** (deletes
 *   the event, like a swipe; it can't be undone, and with sync on it is
 *   removed on every device) and **Cancel** (shows the reminder again).
 *
 * `exported="false"` in the manifest: only the app's own notifications can
 * send to it. On the demo database nothing is changed (the demo never
 * reminds, so this would be an old notification): it is just taken away.
 */
class ReminderEventReceiver : BroadcastReceiver() {

    /** Called by Android on the main thread when one of the buttons is tapped. */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val key = intent.getStringExtra(ReminderScheduler.EXTRA_KEY) ?: return
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: ""
        val app = context.applicationContext
        finishInBackground {
            if (DatabaseHolder.usesDemo(app)) {
                ReminderNotifications.cancel(app, tag)
                return@finishInBackground
            }
            when (action) {
                ACTION_STOP -> change(app, tag, title, R.string.reminder_stop_failed) {
                    DatabaseHolder.get(app).stopEventReminders(itemId, nowText())
                }
                ACTION_KEEP -> ReminderNotifications.cancel(app, tag)
                ACTION_REMOVE -> ReminderNotifications.showRemoveConfirm(app, key, tag, itemId, title)
                ACTION_REMOVE_CONFIRM -> change(app, tag, title, R.string.reminder_remove_failed) {
                    DatabaseHolder.get(app).deleteItem(itemId, nowText())
                }
                ACTION_REMOVE_CANCEL -> showAgain(app, key, tag)
            }
        }
    }

    /**
     * Runs [work] (a change to the database), then takes the notification
     * away, re-plans the alarms and reloads an open main screen. If [work]
     * fails, the notification says why instead ([failed] is its text, with
     * the reason in core's words).
     */
    private suspend fun change(
        context: Context,
        tag: String,
        title: String,
        failed: Int,
        work: () -> Unit,
    ) {
        try {
            work()
        } catch (e: FfiException) {
            ReminderNotifications.showProblem(context, tag, title, context.getString(failed, e.explanation()))
            return
        }
        ReminderNotifications.cancel(context, tag)
        ReminderScheduler.replan(context)
        DataChanges.changed()
    }

    /** "Cancel" on the Remove question: the reminder as core plans it now, or nothing. */
    private fun showAgain(context: Context, key: String, tag: String) {
        val db = DatabaseHolder.get(context)
        // Only when core still plans it (the event may have changed meanwhile).
        val reminder = try {
            db.reminderNow(key, nowText())
        } catch (e: FfiException) {
            null
        }
        if (reminder != null) {
            ReminderNotifications.show(context, reminder, ReminderNotifications.accentOf(db))
        } else {
            ReminderNotifications.cancel(context, tag)
        }
    }

    companion object {
        /** "Stop reminders" / "Stop for this year". */
        const val ACTION_STOP = "com.shanetalkstech.zerohour.action.REMINDER_STOP"

        /** "Keep", on a repeating event's last day. */
        const val ACTION_KEEP = "com.shanetalkstech.zerohour.action.REMINDER_KEEP"

        /** "Remove", step one: ask. */
        const val ACTION_REMOVE = "com.shanetalkstech.zerohour.action.REMINDER_REMOVE"

        /** "Yes, remove", step two: delete the event. */
        const val ACTION_REMOVE_CONFIRM = "com.shanetalkstech.zerohour.action.REMINDER_REMOVE_CONFIRM"

        /** "Cancel" on the question: show the reminder again. */
        const val ACTION_REMOVE_CANCEL = "com.shanetalkstech.zerohour.action.REMINDER_REMOVE_CANCEL"

        /** Intent extra: the tag of the notification to change or take away. */
        const val EXTRA_TAG = "tag"

        /**
         * The pending intent behind one button: [action] for the reminder
         * [key] about the event [itemId]. The action and the key (as the
         * intent's data URI) make each button's intent its own, so Android
         * never mixes up two notifications' buttons.
         */
        fun intent(
            context: Context,
            action: String,
            key: String,
            tag: String,
            itemId: String,
            title: String,
        ): PendingIntent {
            val intent = Intent(context, ReminderEventReceiver::class.java)
                .setAction(action)
                .setData(Uri.fromParts("zerohour-event", key, null))
                .putExtra(ReminderScheduler.EXTRA_KEY, key)
                .putExtra(ReminderScheduler.EXTRA_ITEM_ID, itemId)
                .putExtra(ReminderScheduler.EXTRA_TITLE, title)
                .putExtra(EXTRA_TAG, tag)
            return PendingIntent.getBroadcast(
                context,
                requestCodeFor(key),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
