package com.shanetalkstech.zerohour.data

import android.content.Context
import com.shanetalkstech.zerohour.BuildConfig
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.zerohour_ffi.Database

/**
 * Opens zerohour's database once and hands the same handle to every screen.
 *
 * Which database depends on the build:
 *
 * - **Debug builds** (what Android Studio's Run button installs, with the
 *   `.debug` application ID) open core's *demo* database by default: sample
 *   tasks and events that live only in memory. Nothing is written to the
 *   phone and the demo is never synced, so test data can never reach a real
 *   database. The settings screen's debug-only "Data" row switches to a real
 *   database file (for trying sync against the `sandbox` database). The
 *   debug app is a separate app, so that file is its own and never the
 *   release app's.
 * - **Release builds** always open the real database, `zerohour.db` in the
 *   app's private storage. Android backup and device-to-device transfer are
 *   off for all the app's data (see `res/xml/data_extraction_rules.xml`), so
 *   the file stays on the device.
 *
 * Kotlin's `object` makes this a *singleton*: one shared instance for the whole
 * app process. Keeping the handle here (instead of inside a screen) means it
 * survives the screen being rebuilt, for example when the phone is rotated.
 */
object DatabaseHolder {

    /** The database file's name inside the app's private databases folder. */
    private const val FILE_NAME = "zerohour.db"

    /**
     * The file remembering the debug-only "Data" choice. It exists when the
     * real database was chosen; no file means the demo (the default). It is
     * in `noBackupFilesDir`, a folder Android never backs up or transfers.
     */
    private const val REAL_DATA_FLAG = "debug-use-real-database"

    /** The open database, or null until the first [get]. */
    private var database: Database? = null

    /**
     * Returns the database, opening it on the first call.
     *
     * Opening touches the disk and runs SQLite migrations, so call this from a
     * background thread (`Dispatchers.IO`), never the main (UI) thread.
     * `synchronized` makes two threads calling at the same moment take turns,
     * so the database is opened only once. Throws `FfiException` if opening
     * fails.
     */
    fun get(context: Context): Database = synchronized(this) {
        // `?:` ("elvis"): use the open database if there is one, otherwise open
        // it now and remember it (`also` runs the block, then returns the value).
        database ?: open(context.applicationContext).also { database = it }
    }

    /**
     * True when the demo database is the one in use: in a debug build, unless
     * the real database was chosen in settings. Release builds never use it.
     */
    fun usesDemo(context: Context): Boolean =
        BuildConfig.DEBUG && !realDataFlag(context).exists()

    /**
     * Debug builds only: switches between the demo ([demo] true) and the
     * real database file, remembers the choice, and closes the open database
     * so the next [get] opens the chosen one. Call it on a background thread,
     * then reload every screen. Does nothing in a release build.
     */
    fun chooseDemo(context: Context, demo: Boolean) = synchronized(this) {
        if (!BuildConfig.DEBUG) return@synchronized
        val flag = realDataFlag(context)
        if (demo) flag.delete() else flag.createNewFile()
        // `destroy` frees the Rust object. UniFFI waits for any call still
        // running on it to finish first, so this is safe from any thread.
        database?.destroy()
        database = null
        // A sync result from the other database would be misleading now.
        SyncRunner.forget()
    }

    /** The "real database chosen" flag file (see [REAL_DATA_FLAG]). */
    private fun realDataFlag(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, REAL_DATA_FLAG)

    /** Opens the demo database or the real file, as [usesDemo] says. */
    private fun open(context: Context): Database =
        if (usesDemo(context)) {
            Database.openDemo(nowText())
        } else {
            // getDatabasePath gives /data/data/<app id>/databases/zerohour.db,
            // a folder only this app can read. Core creates the file (and the
            // folder) on first use and brings its tables up to date.
            Database.open(context.getDatabasePath(FILE_NAME).path)
        }
}

/**
 * The pattern for the "now" text the Rust side expects, e.g.
 * `2026-10-22T14:30:00+02:00`: the local date and time to the second, plus
 * the phone's offset from UTC. It must match `NOW_FORMAT` in
 * `rust/zerohour-ffi/src/database.rs`. `'T'` in quotes is a literal letter T;
 * `HH` is the 24-hour hour; `xxx` is the offset as `+02:00` (always digits,
 * even `+00:00`, where `XXX` would write `Z`). `Locale.ROOT` keeps the digits
 * plain ASCII whatever the phone's language.
 */
private val NOW_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)

/**
 * The current local date and time, with the UTC offset, as the text the Rust
 * side expects. Rust needs both: wall-clock time for due dates and repeats,
 * and the exact moment (UTC) for the timestamps it stores.
 *
 * The Rust core never reads the clock itself (so its tests can pretend it is
 * any moment); the app reads it here and passes it in.
 */
fun nowText(): String = OffsetDateTime.now().format(NOW_FORMATTER)
