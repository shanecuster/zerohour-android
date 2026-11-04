package com.shanetalkstech.zerohour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.zerohour.BuildConfig
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.about.ANDROID_SOURCE_URL
import com.shanetalkstech.zerohour.about.CORE_SOURCE_URL
import com.shanetalkstech.zerohour.about.InstallSource
import com.shanetalkstech.zerohour.about.PRIVACY_POLICY_URL
import com.shanetalkstech.zerohour.about.installSource
import com.shanetalkstech.zerohour.about.openPlayListing
import com.shanetalkstech.zerohour.about.openWebLink
import com.shanetalkstech.zerohour.about.shareMessage
import com.shanetalkstech.zerohour.about.shareText
import com.shanetalkstech.zerohour.about.showsRateRow
import com.shanetalkstech.zerohour.about.versionLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot

/**
 * The About page, opened from "About ›" on the settings page. It fills the
 * screen like the settings page:
 *
 * ```
 * ╭ About ───────────────────────────────╮
 * │ zerohour 0.2.1 (4)                   │
 * │                                      │
 * │ Privacy policy ›                     │
 * │ Source: Android app ›                │
 * │ Source: core and desktop app ›       │
 * │ Share zerohour ›                     │
 * │ Rate on Google Play ›                │
 * │ Open source licenses ›               │
 * │                                      │
 * │ [ close ]                            │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * The version comes from the build (BuildConfig), with "debug" after it on
 * debug builds. "Rate on Google Play" shows only when Google Play installed
 * the app. Every link is handed to another app (browser, Play, share sheet);
 * when no app can take it, a short message in the danger colour says so.
 * [onOpenLicenses] opens the licenses page; [onClose] (or back) closes this.
 */
@Composable
fun AboutPage(look: TuiLook, onOpenLicenses: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    // Where the app came from. Asking Android is a quick call to another
    // process, so it runs off the main thread; until it answers (null) the
    // Rate row stays hidden.
    var source by remember { mutableStateOf<InstallSource?>(null) }
    LaunchedEffect(Unit) {
        source = withContext(Dispatchers.IO) { installSource(context) }
    }
    // True after a link could not be opened: shows the "No app" message.
    var failed by remember { mutableStateOf(false) }

    // Runs one link action; `false` from it means no app could take it.
    fun open(action: () -> Boolean) {
        failed = !action()
    }

    val version = versionLine(
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        debug = BuildConfig.DEBUG,
        debugWord = stringResource(R.string.about_debug),
    )
    val shareIntro = stringResource(R.string.share_message)
    val shareTitle = stringResource(R.string.share_chooser)
    val accent = look.color(ColorSlot.ACCENT)
    val foreground = look.color(ColorSlot.FOREGROUND)

    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " ${stringResource(R.string.about_title)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            MutedText(look, version)
            Spacer(Modifier.height(look.cell.rows(1)))

            AboutRow(look, stringResource(R.string.about_privacy), foreground) {
                open { openWebLink(context, PRIVACY_POLICY_URL) }
            }
            AboutRow(look, stringResource(R.string.about_source_app), foreground) {
                open { openWebLink(context, ANDROID_SOURCE_URL) }
            }
            AboutRow(look, stringResource(R.string.about_source_core), foreground) {
                open { openWebLink(context, CORE_SOURCE_URL) }
            }
            AboutRow(look, stringResource(R.string.about_share), foreground) {
                // Until the install source is known, share the GitHub page.
                val message = shareMessage(shareIntro, source ?: InstallSource.OTHER)
                open { shareText(context, message, shareTitle) }
            }
            val known = source
            if (known != null && showsRateRow(known)) {
                AboutRow(look, stringResource(R.string.about_rate), foreground) {
                    open { openPlayListing(context) }
                }
            }
            AboutRow(look, stringResource(R.string.about_licenses), foreground, onClick = onOpenLicenses)
            Spacer(Modifier.height(look.cell.rows(1)))

            if (failed) {
                BasicText(
                    text = stringResource(R.string.about_no_app),
                    style = look.text.copy(color = look.color(ColorSlot.DANGER)),
                )
                Spacer(Modifier.height(look.cell.rows(1)))
            }
            TapText(look, stringResource(R.string.close_button), look.color(ColorSlot.MUTED), onClick = onClose)
        }
    }
}

/**
 * One row of the About page: [text] across the full width, with a tap area
 * of at least 48dp each way that does not move the text ([LargeTapArea]).
 */
@Composable
private fun AboutRow(look: TuiLook, text: String, color: Color, onClick: () -> Unit) {
    TapText(look, text, color, onClick = onClick, modifier = Modifier.fillMaxWidth(), largeTarget = true)
}
