package com.shanetalkstech.zerohour.about

// The About page's links and the rules for which ones to show, as plain
// Kotlin with no Android types. Keeping them free of Android's `Uri`,
// `Context` and `PackageManager` lets the JVM unit tests check them on the
// computer, with no phone (Android's own classes are only empty stubs there).
// The Android side that actually opens the links is in AboutActions.kt.

/**
 * The app's package name on Google Play. Debug builds install as
 * `com.shanetalkstech.zerohour.debug`, which Play does not know, so the store
 * links always use this fixed name instead of the running app's own.
 */
const val PLAY_PACKAGE = "com.shanetalkstech.zerohour"

/**
 * The installer package name Android records for apps installed by the
 * Google Play Store app.
 */
const val PLAY_STORE_INSTALLER = "com.android.vending"

/** This app's source code. */
const val ANDROID_SOURCE_URL = "https://github.com/shanecuster/zerohour-android"

/** The shared Rust core and the terminal (desktop) app's source code. */
const val CORE_SOURCE_URL = "https://github.com/shanecuster/zerohour"

/**
 * The privacy policy's web address.
 *
 * TODO(privacy-url): PLACEHOLDER. Replace with the real privacy policy URL
 * before any release. The `.invalid` name can never resolve (it is reserved
 * for exactly this), so a tap opens only the browser's error page, and
 * scripts/check-release-apk.sh rejects any release APK that still contains
 * it, because the host is not on its allowed list.
 */
const val PRIVACY_POLICY_URL = "https://privacy-policy-url.invalid/REPLACE-ME"

/** The app's Google Play page in a web browser. */
const val PLAY_WEB_URL = "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE"

/**
 * The same page in the form only the Google Play app opens (`market://` is
 * the scheme Play registers). Without Play, opening it fails and the page
 * falls back to [PLAY_WEB_URL].
 */
const val PLAY_APP_URI = "market://details?id=$PLAY_PACKAGE"

/** Where the installed app came from, as far as the About page cares. */
enum class InstallSource {
    /** Installed by the Google Play Store app. */
    GOOGLE_PLAY,

    /**
     * Anything else: F-Droid or another store, `adb install`, a file opened
     * on the phone, or unknown (Android has no record).
     */
    OTHER,
}

/**
 * The install source for the installer package name Android reports
 * ([installer]; null when it has none, as after `adb install`).
 */
fun installSourceOf(installer: String?): InstallSource =
    if (installer == PLAY_STORE_INSTALLER) InstallSource.GOOGLE_PLAY else InstallSource.OTHER

/**
 * True when the "Rate on Google Play" row is shown: only for Play installs,
 * since rating needs the Play listing the app came from.
 */
fun showsRateRow(source: InstallSource): Boolean = source == InstallSource.GOOGLE_PLAY

/**
 * The link put in a share: the Play page when the app came from Play
 * (where the person sharing got it), otherwise the project's GitHub page
 * (which also explains the other ways to install it).
 */
fun shareLink(source: InstallSource): String = when (source) {
    InstallSource.GOOGLE_PLAY -> PLAY_WEB_URL
    InstallSource.OTHER -> ANDROID_SOURCE_URL
}

/** The whole shared text: [intro] (from strings.xml), a space, and the link. */
fun shareMessage(intro: String, source: InstallSource): String = "$intro ${shareLink(source)}"

/**
 * The version line's text, for example "zerohour 0.2.1 (4)", with
 * " [debugWord]" added on debug builds ("zerohour 0.2.1 (4) debug").
 * [appName] and [debugWord] come from strings.xml; [versionName],
 * [versionCode] and [debug] from the build (BuildConfig), so the version is
 * never typed in by hand.
 */
fun versionLine(appName: String, versionName: String, versionCode: Int, debug: Boolean, debugWord: String): String {
    val base = "$appName $versionName ($versionCode)"
    return if (debug) "$base $debugWord" else base
}

/**
 * True for an address the About page may hand to a browser: `https://` only,
 * as everywhere else in the app.
 */
fun isWebLink(url: String): Boolean = url.startsWith("https://")
