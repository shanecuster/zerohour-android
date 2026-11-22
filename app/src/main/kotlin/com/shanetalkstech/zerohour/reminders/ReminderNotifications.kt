package com.shanetalkstech.zerohour.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.nowText
import uniffi.zerohour_ffi.Database
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.PlannedReminder
import uniffi.zerohour_ffi.ReminderActions

/**
 * Shows reminder notifications, and takes them away again when their
 * reminder no longer applies.
 *
 * The buttons come from core (`PlannedReminder.actions`): "Done" on a task's
 * reminder; "Stop reminders" or "Stop for this year" on an event's daily
 * reminder, plus "Keep" and "Remove" on the last day of a repeating event's
 * range. "Done" goes to [ReminderDoneReceiver], the event buttons to
 * [ReminderEventReceiver].
 *
 * Each notification is posted with its reminder's key as the *tag* (the
 * digest uses the one tag [DIGEST_TAG], so a new digest replaces
 * yesterday's). Android tells notifications apart by tag plus ID, and the
 * key already names the reminder exactly, so the ID is the same for all.
 *
 * Titles and texts come from core ("Fill Out Your Timesheet", "Due 2:00
 * PM"); only the channel's name and description live in strings.xml.
 */
object ReminderNotifications {

    /** The notification channel all reminders use. */
    private const val CHANNEL_ID = "reminders"

    /** The ID shared by every reminder notification (the tag tells them apart). */
    const val NOTIFICATION_ID = 1

    /** The tag of the overdue digest, which has no single key of its own to keep. */
    const val DIGEST_TAG = "digest"

    /**
     * Creates the "Reminders" channel, or updates its name and description.
     * Android remembers channels, and from Android 8 every notification must
     * belong to one; the user can change its sound or switch it off in the
     * system settings. Importance HIGH lets a reminder pop up on screen.
     * Calling this again is harmless (Android keeps the user's changes).
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminders_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * True when reminder notifications can actually be seen: the app may post
     * notifications, and the user has not switched the Reminders channel off.
     */
    fun canShow(context: Context): Boolean {
        if (!ReminderPermissions.notificationsAllowed(context)) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        // No channel yet means it is created on the first reminder, with HIGH.
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Shows [reminder]: its title and text from core, a tap opens the app,
     * and the buttons core chose. [accent] is the theme's accent colour, used
     * by Android for the small icon and the app name (null leaves Android's
     * default).
     */
    fun show(context: Context, reminder: PlannedReminder, accent: Int?) {
        val key = reminder.key
        // Null for the digest, which is about several tasks.
        val itemId = reminder.itemId
        val title = reminder.title
        // Checked here as well as before scheduling: the user may have
        // blocked notifications since the alarm was set. (Android 13 and
        // later need the runtime permission; earlier ones only the switch.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tag = tagFor(key, itemId)
        val builder = builder(context, title, reminder.body, accent)
        // The buttons core chose. Android shows at most three, as words (no
        // icons: the 0 below). The digest (no item) never has any.
        if (itemId != null) {
            // A small helper so each button below is one line.
            fun event(label: Int, action: String) = builder.addAction(
                0,
                context.getString(label),
                ReminderEventReceiver.intent(context, action, key, tag, itemId, title),
            )
            when (reminder.actions) {
                ReminderActions.NONE -> Unit
                ReminderActions.DONE -> builder.addAction(
                    0,
                    context.getString(R.string.reminder_done),
                    doneIntent(context, key, tag, itemId, title),
                )
                ReminderActions.STOP -> event(R.string.reminder_stop, ReminderEventReceiver.ACTION_STOP)
                ReminderActions.STOP_FOR_YEAR ->
                    event(R.string.reminder_stop_year, ReminderEventReceiver.ACTION_STOP)
                ReminderActions.STOP_KEEP_REMOVE -> {
                    event(R.string.reminder_stop_year, ReminderEventReceiver.ACTION_STOP)
                    event(R.string.reminder_keep, ReminderEventReceiver.ACTION_KEEP)
                    event(R.string.reminder_remove, ReminderEventReceiver.ACTION_REMOVE)
                }
            }
        }
        context.getSystemService(NotificationManager::class.java).notify(tag, NOTIFICATION_ID, builder.build())
    }

    /**
     * Replaces the notification [tag] with the second step of "Remove": "Remove
     * "[title]" for good?", with "Yes, remove" and "Cancel". Removing deletes
     * the event and can't be undone (with sync on, on every device too), so
     * one tap on "Remove" never deletes anything by itself.
     */
    fun showRemoveConfirm(context: Context, key: String, tag: String, itemId: String, title: String) {
        if (!canPost(context)) return
        val builder = builder(
            context,
            context.getString(R.string.reminder_remove_title, title),
            context.getString(R.string.reminder_remove_text),
            null,
        )
        builder.addAction(
            0,
            context.getString(R.string.reminder_remove_yes),
            ReminderEventReceiver.intent(context, ReminderEventReceiver.ACTION_REMOVE_CONFIRM, key, tag, itemId, title),
        )
        builder.addAction(
            0,
            context.getString(R.string.reminder_remove_cancel),
            ReminderEventReceiver.intent(context, ReminderEventReceiver.ACTION_REMOVE_CANCEL, key, tag, itemId, title),
        )
        context.getSystemService(NotificationManager::class.java).notify(tag, NOTIFICATION_ID, builder.build())
    }

    /**
     * True when the app may post notifications. Android 13 and later need the
     * runtime permission; earlier ones only the switch, which Android checks.
     */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Replaces the notification posted with [tag] by one saying a problem
     * ([text]) under the task's [title], without a "Done" button. Used when
     * "Done" could not complete the task.
     */
    fun showProblem(context: Context, tag: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(tag, NOTIFICATION_ID, builder(context, title, text, null).build())
    }

    /** The parts every reminder notification shares (see [show]). */
    private fun builder(context: Context, title: String, body: String, accent: Int?): NotificationCompat.Builder {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // The digest's list of titles can be long: let it unfold.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Shown in full on the lock screen too (the owner's choice). To
            // hide titles there, use VISIBILITY_PRIVATE plus setPublicVersion.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(context))
            // A tap opens the app and takes the notification away.
            .setAutoCancel(true)
        if (accent != null) builder.setColor(accent)
        return builder
    }

    /**
     * What the "Done" button does: tell [ReminderDoneReceiver] to complete
     * the task [itemId] and take away the notification [tag]. The key is the
     * intent's data URI, so every notification's button is its own (the
     * extras alone would not tell Android's pending intents apart).
     */
    private fun doneIntent(
        context: Context,
        key: String,
        tag: String,
        itemId: String,
        title: String,
    ): PendingIntent {
        val intent = Intent(context, ReminderDoneReceiver::class.java)
            .setAction(ReminderDoneReceiver.ACTION_DONE)
            .setData(Uri.fromParts("zerohour-done", key, null))
            .putExtra(ReminderScheduler.EXTRA_ITEM_ID, itemId)
            .putExtra(ReminderScheduler.EXTRA_TITLE, title)
            .putExtra(ReminderDoneReceiver.EXTRA_TAG, tag)
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The tag a reminder's notification is posted with (see the class notes). */
    fun tagFor(key: String, itemId: String?): String = if (itemId == null) DIGEST_TAG else key

    /** Takes away the notification posted with [tag], if it is still showing. */
    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, NOTIFICATION_ID)
    }

    /**
     * Takes away every reminder notification that no longer applies: core
     * would not plan it now (`reminderNow`), because its task was completed
     * or deleted, its date or range moved, its event's reminders were
     * stopped (here, or on another device and then synced), or a day-named
     * reminder's day is over. The digest is left: it is a list as of its
     * moment. With [db] null (the demo database), every reminder notification
     * is taken away.
     */
    fun removeClosed(context: Context, db: Database?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (shown in manager.activeNotifications) {
            val tag = shown.tag ?: continue
            if (shown.id != NOTIFICATION_ID) continue
            if (db == null) {
                manager.cancel(tag, NOTIFICATION_ID)
                continue
            }
            if (tag == DIGEST_TAG) continue
            // Every other tag is a reminder's key ("<item id>|Due|...").
            val applies = try {
                db.reminderNow(tag, nowText()) != null
            } catch (e: FfiException) {
                // Can't tell: leave it.
                true
            }
            if (!applies) manager.cancel(tag, NOTIFICATION_ID)
        }
    }

    /**
     * The theme's accent colour as an Android colour number, or null if the
     * settings can't be read. It comes from the saved theme, so the
     * notification matches the app (no colour is written in the code).
     */
    fun accentOf(db: Database): Int? =
        try {
            val accent = db.displaySettings().theme.colors.accent
            Color.rgb(accent.r.toInt(), accent.g.toInt(), accent.b.toInt())
        } catch (e: FfiException) {
            null
        }

    /**
     * What a tap on a reminder does: open the app, the same way the launcher
     * icon does (an app already open is brought to the front).
     */
    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }
}
