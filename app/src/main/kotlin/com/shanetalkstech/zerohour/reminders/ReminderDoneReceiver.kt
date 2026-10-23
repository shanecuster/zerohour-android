package com.shanetalkstech.zerohour.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DataChanges
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import uniffi.zerohour_ffi.FfiException

/**
 * Handles a reminder notification's "Done" button: completes the task the
 * same way a swipe does (the Rust side's `completeTask`, which also creates
 * a repeating task's next instance), then re-plans and takes the
 * notification away.
 *
 * `exported="false"` in the manifest: only the app's own notification can
 * send to it.
 *
 * What if the task is no longer open?
 * - Completed elsewhere and synced here already: `completeTask` changes
 *   nothing (core never completes a task twice), and the notification goes.
 * - Deleted: there is nothing to complete; the notification goes.
 * - Completed on another device but NOT synced yet: the phone completes it
 *   too. Core names the completion and the next instance after the task's
 *   own id, so the next sync merges both devices' copies into one.
 */
class ReminderDoneReceiver : BroadcastReceiver() {

    /** Called by Android on the main thread when "Done" is tapped. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: ""
        val app = context.applicationContext
        finishInBackground {
            // Never on the demo (it has no reminders, so this would be an old
            // notification about the real database): just take it away.
            if (!DatabaseHolder.usesDemo(app)) {
                complete(app, itemId, tag, title)
                ReminderScheduler.replan(app)
                DataChanges.changed()
            } else {
                ReminderNotifications.cancel(app, tag)
            }
        }
    }

    /**
     * Completes the task and takes the notification away; if completing
     * fails while the task is still open, the notification says why instead.
     */
    private fun complete(context: Context, itemId: String, tag: String, title: String) {
        val db = DatabaseHolder.get(context)
        try {
            db.completeTask(itemId, nowText())
        } catch (e: FfiException) {
            // A deleted task is also an error ("no longer exists"): only a
            // task that is still open really failed.
            val stillOpen = try {
                db.isOpenTask(itemId)
            } catch (again: FfiException) {
                true
            }
            if (stillOpen) {
                ReminderNotifications.showProblem(
                    context,
                    tag,
                    title,
                    context.getString(R.string.reminder_done_failed, e.explanation()),
                )
                return
            }
        }
        ReminderNotifications.cancel(context, tag)
    }

    companion object {
        /** The intent action of the "Done" button. */
        const val ACTION_DONE = "com.shanetalkstech.zerohour.action.REMINDER_DONE"

        /** Intent extra: the tag of the notification to take away. */
        const val EXTRA_TAG = "tag"
    }
}
