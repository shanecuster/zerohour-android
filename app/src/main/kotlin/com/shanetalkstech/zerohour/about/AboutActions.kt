package com.shanetalkstech.zerohour.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri

// The Android side of the About page: asking Android which app installed
// zerohour, and handing links to other apps (the browser, Google Play, the
// share sheet). zerohour itself sends nothing anywhere: each action only asks
// Android to open another app. Every action reports failure as `false`
// (no browser, no Play, nothing that can share) instead of crashing, so the
// page can show a short message.

/**
 * Where this app was installed from. Asks Android for the installer's
 * package name; any failure counts as "not Google Play", which only hides
 * the Rate row.
 */
fun installSource(context: Context): InstallSource {
    val installer = try {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) and later: the app that did the install.
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            // Older Android has only this call, deprecated since API 30.
            // `@Suppress` silences that warning here, where it is the only choice.
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(context.packageName)
        }
    } catch (e: Exception) {
        // NameNotFoundException and the like: treat as unknown.
        null
    }
    return installSourceOf(installer)
}

/**
 * Starts [intent] in another app. False when no app on the phone can take
 * it (`ActivityNotFoundException`) or Android refuses (`SecurityException`).
 */
private fun startOther(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * Opens [url] in the browser. Only `https://` links are opened (see
 * [isWebLink]); anything else, or no browser, gives false.
 */
fun openWebLink(context: Context, url: String): Boolean {
    if (!isWebLink(url)) return false
    // ACTION_VIEW means "show this to the user"; Android picks the browser.
    // `toUri()` (from androidx core-ktx) turns the text into Android's
    // address type, `Uri`.
    return startOther(context, Intent(Intent.ACTION_VIEW, url.toUri()))
}

/**
 * Opens zerohour's page in the Google Play app, where it can be rated, or
 * in the browser when the Play app can't open it. False when neither works.
 */
fun openPlayListing(context: Context): Boolean =
    // `||` tries the right side only when the left side is false.
    startOther(context, Intent(Intent.ACTION_VIEW, PLAY_APP_URI.toUri())) ||
        openWebLink(context, PLAY_WEB_URL)

/**
 * Opens Android's share sheet with [message] as plain text; [chooserTitle]
 * heads the sheet. The app the person picks does the sending. False when no
 * app can share text.
 */
fun shareText(context: Context, message: String, chooserTitle: String): Boolean {
    // ACTION_SEND with plain text is the standard "share this" request.
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    // `createChooser` always shows the share sheet, even when the person
    // once picked a default app for sharing.
    return startOther(context, Intent.createChooser(send, chooserTitle))
}
