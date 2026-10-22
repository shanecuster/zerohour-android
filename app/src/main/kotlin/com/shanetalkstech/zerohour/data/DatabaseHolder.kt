package com.shanetalkstech.zerohour.data

import android.content.Context
import com.shanetalkstech.zerohour.BuildConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.zerohour_ffi.Database

/**
 * Opens zerohour's database once and hands the same handle to every screen.
 *
 * Which database depends on the build:
 *
 * - **Debug builds** (what Android Studio's Run button installs, with the
 *   `.debug` application ID) open core's *demo* database: sample tasks and
 *   events that live only in memory. Nothing is written to the phone and the
 *   demo is never synced, so test data can never reach the real database.
 * - **Release builds** open the real database, `zerohour.db` in the app's
 *   private storage. Android backup is off for the whole app
 *   (`allowBackup="false"` in the manifest), so the file stays on the device.
 *
 * Kotlin's `object` makes this a *singleton*: one shared instance for the whole
 * app process. Keeping the handle here (instead of inside a screen) means it
 * survives the screen being rebuilt, for example when the phone is rotated.
 */
object DatabaseHolder {

    /** The database file's name inside the app's private databases folder. */
    private const val FILE_NAME = "zerohour.db"

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

    /** Opens the demo database (debug builds) or the real file (release builds). */
    private fun open(context: Context): Database =
        if (BuildConfig.DEBUG) {
            Database.openDemo(nowText())
        } else {
            // getDatabasePath gives /data/data/<app id>/databases/zerohour.db,
            // a folder only this app can read. Core creates the folder if needed.
            Database.open(context.getDatabasePath(FILE_NAME).path)
        }
}

/**
 * The pattern for the "now" text the Rust side expects, e.g.
 * `2026-10-22T14:30:00`: the local date and time to the second, with no time
 * zone. It must match `NOW_FORMAT` in `rust/zerohour-ffi/src/database.rs`.
 * `'T'` in quotes is a literal letter T; `HH` is the 24-hour hour.
 * `Locale.ROOT` keeps the digits plain ASCII whatever the phone's language.
 */
private val NOW_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

/**
 * The current local date and time as the text the Rust side expects.
 *
 * The Rust core never reads the clock itself (so its tests can pretend it is
 * any moment); the app reads it here and passes it in.
 */
fun nowText(): String = LocalDateTime.now().format(NOW_FORMATTER)
