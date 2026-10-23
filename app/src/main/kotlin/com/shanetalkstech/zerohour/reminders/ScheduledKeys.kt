package com.shanetalkstech.zerohour.reminders

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Remembers which reminder alarms are currently set, so the next re-plan can
 * cancel the ones that are no longer planned (a task completed or edited, a
 * setting changed).
 *
 * Android can set and cancel alarms but cannot list an app's own alarms, so
 * the app keeps this list itself. It holds only core's reminder keys
 * ("<item id>|Due|2026-10-26T14:00"), one per line: item IDs and times, no
 * titles. The file lives in `noBackupFilesDir`, which Android never backs up
 * or copies to a new phone (and the backup rules exclude everything anyway):
 * alarms belong to this phone only.
 *
 * `AtomicFile` (part of Android) writes a new copy beside the old one and
 * swaps it in only when the write is complete, so a crash halfway through
 * leaves the previous list, never half a file.
 */
object ScheduledKeys {

    /** The file's name inside `noBackupFilesDir`. */
    private const val FILE_NAME = "scheduled-reminders.txt"

    /** The list file, wrapped for safe writing. */
    private fun file(context: Context): AtomicFile =
        AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))

    /**
     * The keys saved by the last re-plan; empty if there is no list yet or it
     * cannot be read (then nothing stale is cancelled, which at worst leaves
     * an old alarm that the fire-time check still stops).
     */
    fun load(context: Context): Set<String> =
        try {
            file(context).readFully()
                .toString(Charsets.UTF_8)
                .lines()
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: FileNotFoundException) {
            emptySet()
        } catch (e: IOException) {
            emptySet()
        }

    /** Replaces the saved list with [keys]. */
    fun save(context: Context, keys: Set<String>) {
        val atomic = file(context)
        // `startWrite` opens the new copy; `finishWrite` swaps it in, and
        // `failWrite` throws the half-written copy away.
        val stream = try {
            atomic.startWrite()
        } catch (e: IOException) {
            return
        }
        try {
            stream.write(keys.joinToString(separator = "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: IOException) {
            atomic.failWrite(stream)
        }
    }
}
