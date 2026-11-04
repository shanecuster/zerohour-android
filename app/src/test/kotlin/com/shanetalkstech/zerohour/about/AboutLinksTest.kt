package com.shanetalkstech.zerohour.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the About page's rules ([installSourceOf], [showsRateRow])
 * and links ([shareMessage], the store links, [versionLine]). They run with
 * `./gradlew :app:testDebugUnitTest`, no phone needed.
 */
class AboutLinksTest {

    @Test
    fun onlyThePlayStoreCountsAsGooglePlay() {
        assertEquals(InstallSource.GOOGLE_PLAY, installSourceOf("com.android.vending"))
        // F-Droid, Android's own package installer (a file opened on the
        // phone), and no record at all (adb install).
        assertEquals(InstallSource.OTHER, installSourceOf("org.fdroid.fdroid"))
        assertEquals(InstallSource.OTHER, installSourceOf("com.google.android.packageinstaller"))
        assertEquals(InstallSource.OTHER, installSourceOf(null))
        assertEquals(InstallSource.OTHER, installSourceOf(""))
    }

    @Test
    fun theRateRowIsShownOnlyForPlayInstalls() {
        assertTrue(showsRateRow(InstallSource.GOOGLE_PLAY))
        assertFalse(showsRateRow(InstallSource.OTHER))
    }

    @Test
    fun storeLinksUseTheReleasePackageNeverTheDebugOne() {
        assertEquals("https://play.google.com/store/apps/details?id=com.shanetalkstech.zerohour", PLAY_WEB_URL)
        assertEquals("market://details?id=com.shanetalkstech.zerohour", PLAY_APP_URI)
        assertFalse(PLAY_WEB_URL.contains(".debug"))
        assertFalse(PLAY_APP_URI.contains(".debug"))
    }

    @Test
    fun sharingLinksToWhereTheAppCameFrom() {
        val intro = "zerohour: a to-do list and countdown app with a terminal look."
        assertEquals("$intro $PLAY_WEB_URL", shareMessage(intro, InstallSource.GOOGLE_PLAY))
        assertEquals(
            "$intro https://github.com/shanecuster/zerohour-android",
            shareMessage(intro, InstallSource.OTHER),
        )
    }

    @Test
    fun thePrivacyPolicyIsTheRepositorysPolicyFile() {
        assertEquals("https://github.com/shanecuster/zerohour-android/blob/master/PRIVACY.md", PRIVACY_POLICY_URL)
    }

    @Test
    fun everyBrowserLinkIsHttps() {
        for (url in listOf(ANDROID_SOURCE_URL, CORE_SOURCE_URL, PRIVACY_POLICY_URL, PLAY_WEB_URL)) {
            assertTrue(url, isWebLink(url))
        }
        assertFalse(isWebLink("http://example.org"))
        assertFalse(isWebLink(PLAY_APP_URI))
    }

    @Test
    fun theVersionLineSaysDebugOnlyOnDebugBuilds() {
        assertEquals("zerohour 0.2.1 (4)", versionLine("zerohour", "0.2.1", 4, debug = false, debugWord = "debug"))
        assertEquals("zerohour 0.2.1 (4) debug", versionLine("zerohour", "0.2.1", 4, debug = true, debugWord = "debug"))
    }
}
