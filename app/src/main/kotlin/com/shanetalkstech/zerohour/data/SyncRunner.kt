package com.shanetalkstech.zerohour.data

import android.content.Context
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.reminders.ReminderScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.SyncOutcome
import uniffi.zerohour_ffi.syncAccount

/**
 * Where "Sync now" stands. A `sealed interface` is a closed set of cases, so
 * a `when` over it must handle each one.
 */
sealed interface SyncState {
    /** No sync has run since the app started (the saved "last synced" applies). */
    data object Idle : SyncState

    /** A sync is running right now. */
    data object Running : SyncState

    /**
     * The last sync worked; [outcome] says what it did. A plain `class`, not
     * a `data class`: two syncs with the same numbers must still count as two
     * different results, so the screens notice the second one too.
     */
    class Done(val outcome: SyncOutcome) : SyncState

    /** The server could not be reached; [detail] says why (a warning, not an error). */
    class Offline(val detail: String) : SyncState

    /** The last sync failed; [detail] says why, in words for the user. */
    class Failed(val detail: String) : SyncState
}

/**
 * Runs "Sync now" for the whole app, one sync at a time, and tells every
 * screen how it went.
 *
 * It lives as long as the app process (a Kotlin `object`), not as long as a
 * screen, with its own background scope. So a sync keeps going if the user
 * turns the phone or leaves the page, and its result still arrives. (Had a
 * screen started it, leaving the screen would cancel the waiting coroutine
 * and lose the result, while the Rust call carried on anyway.)
 *
 * Screens read [state], a `StateFlow`: a value that can be watched, which
 * Compose turns into redraws with `collectAsState()`.
 *
 * The password: [start] reads it from [PasswordStore] on the background
 * thread just before the sync, passes it straight into Rust, and keeps no
 * copy. It is never logged and never part of [state].
 *
 * Syncing happens only when the user asks ("Sync now"): nothing here runs on
 * a timer, at start-up or in the background.
 */
object SyncRunner {

    /**
     * The scope the syncs run in: background threads meant for disk and
     * network work (`Dispatchers.IO`). `SupervisorJob` means one failed sync
     * does not stop the scope from running the next one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while a sync runs; checked and set in one step, so taps can't race. */
    private val running = AtomicBoolean(false)

    /** The changeable state behind [state]. */
    private val current = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Where "Sync now" stands, for the screens to watch. */
    val state: StateFlow<SyncState> = current.asStateFlow()

    /**
     * Starts a sync in the background, unless one is already running: then it
     * returns false and does nothing (a second sync is never queued).
     */
    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        current.value = SyncState.Running
        scope.launch {
            current.value = try {
                runSync(app)
            } catch (e: FfiException.Offline) {
                SyncState.Offline(e.explanation())
            } catch (e: FfiException) {
                SyncState.Failed(e.explanation())
            } catch (e: Exception) {
                // Anything else (a bug, or the Keystore failing in a way
                // nobody expected) must not crash the app from a background
                // thread. Its own message is not shown: it was not written
                // for the user.
                SyncState.Failed(app.getString(R.string.sync_failed_unexpectedly))
            } finally {
                running.set(false)
            }
            // A sync can add, change, complete or delete tasks: the alarms
            // must follow, whichever screen (if any) is open.
            if (current.value is SyncState.Done) ReminderScheduler.replanSoon(app)
        }
        return true
    }

    /**
     * Forgets the last result (after the debug-only "Data" switch, where it
     * would describe the other database). Does nothing while a sync runs.
     */
    fun forget() {
        if (!running.get()) current.value = SyncState.Idle
    }

    /**
     * One sync: the saved details, the password saved for exactly those
     * details, then the Rust side's `syncNow`. A missing or unreadable
     * password is reported without calling Rust.
     */
    private fun runSync(context: Context): SyncState {
        val db = DatabaseHolder.get(context)
        val config = db.syncScreen().config
        val password = when (val saved = PasswordStore.load(context, syncAccount(config))) {
            is SavedPassword.Found -> saved.password
            SavedPassword.None -> return SyncState.Failed(context.getString(R.string.sync_no_password))
            SavedPassword.Unreadable -> return SyncState.Failed(context.getString(R.string.sync_password_unreadable))
        }
        return SyncState.Done(db.syncNow(password, nowText()))
    }
}
