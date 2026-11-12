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
import uniffi.zerohour_ffi.SyncConfig
import uniffi.zerohour_ffi.SyncOutcome
import uniffi.zerohour_ffi.syncAccount

/**
 * Where "Sync now" stands. A `sealed interface` is a closed set of cases, so
 * a `when` over it must handle each one.
 *
 * The finished cases carry `atLaunch`: true when the sync was the automatic
 * one started as the app opened ([SyncRunner.startAtLaunch]). The main screen
 * then keeps its bottom row quiet: a launch sync that fails shows only in the
 * header indicator ("○ offline", "✗ error"). Kotlin's `= false` is a default
 * value, so every existing `Done(outcome)` still means "the user asked".
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
    class Done(val outcome: SyncOutcome, val atLaunch: Boolean = false) : SyncState

    /** The server could not be reached; [detail] says why (a warning, not an error). */
    class Offline(val detail: String, val atLaunch: Boolean = false) : SyncState

    /** The last sync failed; [detail] says why, in words for the user. */
    class Failed(val detail: String, val atLaunch: Boolean = false) : SyncState
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
 * A sync starts when the user asks ("Sync now"), or once as the app opens
 * when the user switched "On launch" on ([startAtLaunch]). Nothing here runs
 * on a timer or while the app is closed.
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
     *
     * [atLaunch] marks the automatic sync at app start (see [startAtLaunch]);
     * it is passed on into the finished state so the screens can stay quiet.
     */
    fun start(context: Context, atLaunch: Boolean = false): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        current.value = SyncState.Running
        scope.launch {
            current.value = try {
                runSync(app, atLaunch)
            } catch (e: FfiException.Offline) {
                SyncState.Offline(e.explanation(), atLaunch)
            } catch (e: FfiException) {
                SyncState.Failed(e.explanation(), atLaunch)
            } catch (e: Exception) {
                // Anything else (a bug, or the Keystore failing in a way
                // nobody expected) must not crash the app from a background
                // thread. Its own message is not shown: it was not written
                // for the user.
                SyncState.Failed(app.getString(R.string.sync_failed_unexpectedly), atLaunch)
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
     * The automatic sync as the app opens: checks, in the background, whether
     * the user wants one and whether it can run without a question
     * ([shouldSyncAtLaunch]), then starts it with [start]. Called by
     * `MainActivity` only when its screen is first created, never from the
     * alarm, boot or "Done" receivers (those start the app's process too, but
     * must never cause network traffic).
     *
     * It never slows the start: everything happens on [scope]'s background
     * threads, and the screen draws at once. A launch sync is bounded by
     * core's network timeouts (5 seconds to connect, 30 per request), so an
     * unreachable server ends it quickly as "offline".
     *
     * Every problem here is silent: if the settings cannot be read, nothing
     * happens and the user can still tap "sync now".
     */
    fun startAtLaunch(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val wanted = try {
                val db = DatabaseHolder.get(app)
                val status = db.syncStatus(nowText())
                // Only ask the Keystore when everything else says yes, so a
                // phone with sync off never touches the saved password.
                val readyApartFromPassword = shouldSyncAtLaunch(
                    syncOn = status.enabled,
                    onLaunch = status.onLaunch,
                    demo = status.demo,
                    firstSyncWarning = status.firstSyncWarning,
                    passwordSaved = true,
                )
                readyApartFromPassword && hasUsablePassword(app, db.syncScreen().config)
            } catch (e: Exception) {
                // Silent on purpose: a launch sync is a convenience.
                false
            }
            if (wanted) start(app, atLaunch = true)
        }
    }

    /**
     * True when a password is saved for [config] and can be decrypted. The
     * password itself is dropped at once; [runSync] reads it again just
     * before the sync, as for "Sync now".
     */
    private fun hasUsablePassword(context: Context, config: SyncConfig): Boolean =
        PasswordStore.load(context, syncAccount(config)) is SavedPassword.Found

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
     * password is reported without calling Rust. [atLaunch] is copied into
     * the result (see [SyncState]).
     */
    private fun runSync(context: Context, atLaunch: Boolean): SyncState {
        val db = DatabaseHolder.get(context)
        val config = db.syncScreen().config
        val password = when (val saved = PasswordStore.load(context, syncAccount(config))) {
            is SavedPassword.Found -> saved.password
            SavedPassword.None -> return SyncState.Failed(context.getString(R.string.sync_no_password), atLaunch)
            SavedPassword.Unreadable ->
                return SyncState.Failed(context.getString(R.string.sync_password_unreadable), atLaunch)
        }
        return SyncState.Done(db.syncNow(password, nowText()), atLaunch)
    }
}
