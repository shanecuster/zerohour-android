package com.shanetalkstech.zerohour.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.nowText
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.PlannedReminder

/**
 * Turns core's reminder plan into Android alarms, and keeps them up to date.
 *
 * **How it works.** [replan] asks the Rust side for every reminder in the
 * next [WINDOW_HOURS] hours and sets one alarm per reminder. Each alarm is
 * named by its reminder's key (core's "<item id>|Due|2026-10-26T14:00"), so
 * planning again *replaces* an alarm instead of adding a second, and alarms
 * whose keys are no longer in the plan are cancelled. The keys that are set
 * are remembered in [ScheduledKeys] for that.
 *
 * **Refill.** Only 48 hours are planned at a time (a repeating task would
 * otherwise need alarms forever). One more alarm, [REFILL_HOURS] hours ahead,
 * runs [replan] again. Halfway through the window leaves a full day of
 * margin even if Android delays it.
 *
 * **When it runs.** After anything that can change the plan: an item added,
 * edited, completed or deleted; a sync; a reminder or time-format setting;
 * the phone restarting, its clock or time zone changing, the app being
 * updated, and the exact-alarm permission being granted (see the
 * receivers). Running it more often than needed is harmless.
 *
 * **Never** for the demo database (debug builds): demo data never reminds,
 * so every alarm is cancelled and the demo database is not even opened.
 */
object ReminderScheduler {

    /** How far ahead each plan looks, in hours. */
    const val WINDOW_HOURS: UInt = 48u

    /** When the next re-plan is due, in hours from now (see "Refill" above). */
    private const val REFILL_HOURS = 24L

    /** The intent action of a reminder's alarm. */
    const val ACTION_FIRE = "com.shanetalkstech.zerohour.action.REMINDER_FIRE"

    /** The intent action of the refill alarm. */
    const val ACTION_REFILL = "com.shanetalkstech.zerohour.action.REMINDER_REFILL"

    /** The URI scheme that carries a reminder's key in its alarm's intent. */
    private const val KEY_SCHEME = "zerohour-reminder"

    /** The refill alarm's request code (its action alone already sets it apart). */
    private const val REFILL_REQUEST_CODE = 0

    /** Intent extras: what the alarm needs to show the notification. */
    const val EXTRA_KEY = "key"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"

    /** The tag for the (rare) log lines. They never contain titles or other data. */
    private const val TAG = "zerohour.reminders"

    /**
     * The background scope re-plans and receivers run in: threads meant for
     * disk work (`Dispatchers.IO`). `SupervisorJob` keeps one failure from
     * stopping the next run. It lives as long as the app process.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lets only one re-plan run at a time, so two never undo each other. */
    private val mutex = Mutex()

    /**
     * Starts a re-plan in the background and returns at once. For the
     * screens, after a change; receivers use [replan] directly so Android
     * keeps them alive until it is done.
     */
    fun replanSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { replan(app) }
    }

    /**
     * Plans the next [WINDOW_HOURS] hours and makes the alarms match: sets
     * or replaces one alarm per planned reminder, cancels the ones no longer
     * planned, and sets the refill alarm. If the plan cannot be read (the
     * database failed), the alarms already set are left as they are.
     */
    suspend fun replan(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            // `null` means "no reminders at all": the demo, reminders switched
            // off, or notifications blocked.
            val plan: List<PlannedReminder>? = try {
                plannedReminders(app)
            } catch (e: FfiException) {
                Log.w(TAG, "re-plan skipped: the plan could not be read (${e.javaClass.simpleName})")
                return@withLock
            }
            apply(app, plan ?: emptyList())
            if (plan == null) cancelRefill(app) else setRefill(app)
        }
    }

    /**
     * The reminders to schedule, or null when there must be none: the demo
     * database, reminders switched off, or notifications not allowed.
     */
    private fun plannedReminders(context: Context): List<PlannedReminder>? {
        if (DatabaseHolder.usesDemo(context)) return null
        if (!ReminderPermissions.notificationsAllowed(context)) return null
        val db = DatabaseHolder.get(context)
        if (!db.reminderSettings().enabled) return null
        return db.planReminders(nowText(), WINDOW_HOURS)
    }

    /** Sets an alarm for each reminder in [plan] and cancels every other one set before. */
    private fun apply(context: Context, plan: List<PlannedReminder>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val exact = ReminderPermissions.exactAlarmsAllowed(context)
        val set = mutableSetOf<String>()
        for (reminder in plan) {
            val at = fireAtMillis(parseFireAt(reminder.fireAt), zone)
            // Already passed in real time (the repeated hour after the clocks
            // fall back): it fired before, so it is not set again.
            if (!isStillAhead(at, now)) continue
            // FLAG_UPDATE_CURRENT: if this key's alarm exists, refresh its
            // texts. FLAG_IMMUTABLE: nobody else may change the intent.
            val pending = PendingIntent.getBroadcast(
                context,
                requestCodeFor(reminder.key),
                fireIntent(context, reminder.key).apply {
                    putExtra(EXTRA_KEY, reminder.key)
                    putExtra(EXTRA_ITEM_ID, reminder.itemId)
                    putExtra(EXTRA_TITLE, reminder.title)
                    putExtra(EXTRA_BODY, reminder.body)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setAlarm(alarms, at, pending, exact)
            set += reminder.key
        }
        // Cancel what was set before and is not planned any more. (`-` on
        // sets keeps the keys of the first that are missing from the second.)
        for (key in ScheduledKeys.load(context) - set) {
            cancelAlarm(context, alarms, key)
        }
        ScheduledKeys.save(context, set)
    }

    /**
     * Sets one alarm at [at] (milliseconds since 1970 UTC). `RTC_WAKEUP` means
     * "at this clock time, waking the phone if it sleeps". `...AndAllowWhileIdle`
     * lets it fire even in Doze, the deep sleep of a phone left alone.
     * Without the exact-alarm permission, the inexact kind is used, which
     * Android may delay.
     */
    private fun setAlarm(alarms: AlarmManager, at: Long, pending: PendingIntent, exact: Boolean) {
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (e: SecurityException) {
                // The permission was taken away a moment ago: fall through
                // to the inexact kind.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Cancels the alarm of the reminder with this [key], if it is still set. */
    private fun cancelAlarm(context: Context, alarms: AlarmManager, key: String) {
        // FLAG_NO_CREATE: only find an existing one (null if there is none).
        // It matches the one that was set because the action, the receiver
        // and the key URI are the same (extras are not compared).
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(key),
            fireIntent(context, key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    /**
     * The intent a reminder's alarm delivers to [ReminderAlarmReceiver]. The
     * key is its data URI, so alarms for different reminders are always told
     * apart, even if their request codes happened to be equal.
     */
    private fun fireIntent(context: Context, key: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            // `fromParts` escapes the key, whatever characters it has.
            .setData(Uri.fromParts(KEY_SCHEME, key, null))

    /** The refill alarm's pending intent (see "Refill" above). */
    private fun refillIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REFILL_REQUEST_CODE,
            Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REFILL),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Sets (or moves) the refill alarm to [REFILL_HOURS] from now. It is
     * inexact on purpose: a few minutes either way do not matter, and it
     * needs no permission.
     */
    private fun setRefill(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + REFILL_HOURS * 60 * 60 * 1000
        val pending = refillIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Cancels the refill alarm, when there is nothing to remind. */
    private fun cancelRefill(context: Context) {
        val pending = refillIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }
}

/**
 * Runs [work] on a background thread after a broadcast arrives, and tells
 * Android when it is done.
 *
 * A receiver's `onReceive` runs on the main thread and must return quickly,
 * and Android may end the app's process soon after it returns. `goAsync()`
 * says "not finished yet": the process is kept until `finish()` is called
 * (within about ten seconds, plenty for a database call and a re-plan).
 * `finally` makes sure `finish()` happens even if [work] fails.
 */
internal fun BroadcastReceiver.finishInBackground(work: suspend () -> Unit) {
    val pending = goAsync()
    ReminderScheduler.scope.launch {
        try {
            work()
        } catch (e: Exception) {
            // Nothing a receiver could show; the next re-plan tries again.
            Log.w("zerohour.reminders", "receiver work failed (${e.javaClass.simpleName})")
        } finally {
            pending.finish()
        }
    }
}
