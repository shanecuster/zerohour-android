package com.shanetalkstech.zerohour.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.Database
import uniffi.zerohour_ffi.FfiException

/**
 * Shows reminder notifications, and takes them away again when their task
 * is no longer open.
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
     * Shows the reminder with this [key]: [title] and [body] from core, a tap
     * opens the app. [itemId] is the task it is about (null for the digest).
     * [accent] is the theme's accent colour, used by Android for the small
     * icon and the app name (null leaves Android's default).
     */
    fun show(
        context: Context,
        key: String,
        itemId: String?,
        title: String,
        body: String,
        accent: Int?,
    ) {
        // Checked here as well as before scheduling: the user may have
        // blocked notifications since the alarm was set. (Android 13 and
        // later need the runtime permission; earlier ones only the switch.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
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
        context.getSystemService(NotificationManager::class.java)
            .notify(tagFor(key, itemId), NOTIFICATION_ID, builder.build())
    }

    /** The tag a reminder's notification is posted with (see the class notes). */
    fun tagFor(key: String, itemId: String?): String = if (itemId == null) DIGEST_TAG else key

    /** Takes away the notification posted with [tag], if it is still showing. */
    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, NOTIFICATION_ID)
    }

    /**
     * Takes away every reminder notification whose task is no longer open
     * (completed or deleted, here or on another device and then synced).
     * The digest is left: it is a list as of its moment. With [db] null (the
     * demo database), every reminder notification is taken away.
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
            // A key starts with the item's id: "<item id>|Due|...".
            val itemId = tag.substringBefore('|')
            val open = try {
                db.isOpenTask(itemId)
            } catch (e: FfiException) {
                // Can't tell: leave it.
                true
            }
            if (!open) manager.cancel(tag, NOTIFICATION_ID)
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
