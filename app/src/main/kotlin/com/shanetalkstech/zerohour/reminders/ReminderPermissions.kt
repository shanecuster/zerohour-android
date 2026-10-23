package com.shanetalkstech.zerohour.reminders

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * What Android currently lets the app do for reminders. Both can change at
 * any time in the system settings, so they are asked fresh every time.
 */
object ReminderPermissions {

    /**
     * True when the app may show notifications. On Android 13 and later that
     * needs the `POST_NOTIFICATIONS` permission; on every version the user can
     * also block the app's notifications in the system settings. Without it,
     * no alarms are set (a reminder nobody can see would be pointless).
     */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * True when the app may set *exact* alarms, which fire at the minute.
     * Android 12 added the "Alarms & reminders" permission
     * (`SCHEDULE_EXACT_ALARM`); from Android 14 it is off for new installs
     * until the user allows it. Older versions need no permission. Without
     * it, reminders use inexact alarms, which Android may delay (Google's
     * documentation says within about an hour on Android 12 and later).
     */
    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
