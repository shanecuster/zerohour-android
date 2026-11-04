package com.shanetalkstech.zerohour.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.zerohour.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot

/**
 * One line of the licenses list: a component (or a group of components
 * under the same license), its license's name, the raw resource with the
 * license's full text, and an optional muted [detail] line under it (used
 * for Font Awesome's attribution).
 */
private data class LicenseEntry(
    @param:StringRes val name: Int,
    @param:StringRes val license: Int,
    @param:RawRes val text: Int,
    @param:StringRes val detail: Int? = null,
)

/**
 * Everything built into the app, by license. What ships, and where each text
 * comes from, is in `third_party/licenses/README.md`, with the commands that
 * list it again when a dependency changes.
 */
private val LICENSES = listOf(
    LicenseEntry(R.string.lic_zerohour, R.string.license_mit, R.raw.license_mit_zerohour),
    LicenseEntry(R.string.lic_font, R.string.license_ofl, R.raw.license_ofl_1_1),
    LicenseEntry(
        R.string.lic_font_awesome,
        R.string.license_cc_by,
        R.raw.license_cc_by_4_0,
        detail = R.string.lic_font_awesome_attribution,
    ),
    LicenseEntry(R.string.lic_sqlite, R.string.license_public_domain, R.raw.license_sqlite),
    LicenseEntry(R.string.lic_android, R.string.license_apache, R.raw.license_apache_2_0),
    LicenseEntry(R.string.lic_jna, R.string.license_apache_or_lgpl, R.raw.license_apache_2_0),
    LicenseEntry(R.string.lic_uniffi, R.string.license_mpl, R.raw.license_mpl_2_0),
    LicenseEntry(R.string.lic_rustls, R.string.license_isc, R.raw.license_isc),
    LicenseEntry(R.string.lic_ring, R.string.license_ring, R.raw.license_ring),
    LicenseEntry(R.string.lic_webpki_roots, R.string.license_cdla, R.raw.license_cdla_permissive_2_0),
    LicenseEntry(R.string.lic_mit_others, R.string.license_mit, R.raw.license_mit_others),
    LicenseEntry(R.string.lic_bsd, R.string.license_bsd, R.raw.license_bsd_3_clause),
    LicenseEntry(R.string.lic_foldhash, R.string.license_zlib, R.raw.license_zlib),
    LicenseEntry(R.string.lic_rust_apache, R.string.license_apache_or_mit, R.raw.license_apache_2_0),
)

/**
 * The Open source licenses page, opened from the About page:
 *
 * ```
 * ╭ Open source licenses ────────────────╮
 * │ Built into zerohour. Tap one to read │
 * │ its license.                         │
 * │                                      │
 * │ JetBrainsMono Nerd Font Mono 3.5.1   │
 * │   SIL Open Font License 1.1 ›        │
 * │ Font Awesome 6.5.1 icons             │
 * │   CC BY 4.0 ›                        │
 * │   Font Awesome by Fonticons, Inc. …  │
 * │ ...                                  │
 * │ [ close ]                            │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * Tapping an entry shows that license's full text in place of the list,
 * read from `res/raw` (so it works offline); back, or "[ back ]", returns to
 * the list. Back on the list, or "[ close ]", runs [onClose].
 */
@Composable
fun LicensesPage(look: TuiLook, onClose: () -> Unit) {
    // The index of the entry whose text is open, or -1 for the list.
    // `rememberSaveable` keeps it if Android re-creates the screen.
    var open by rememberSaveable { mutableIntStateOf(-1) }
    val entry = LICENSES.getOrNull(open)
    if (entry == null) {
        LicenseList(look, onOpen = { open = it }, onClose = onClose)
    } else {
        LicenseText(look, entry, onBack = { open = -1 })
    }
}

/** The list of entries; [onOpen] runs with the tapped entry's index. */
@Composable
private fun LicenseList(look: TuiLook, onOpen: (Int) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val accent = look.color(ColorSlot.ACCENT)
    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " ${stringResource(R.string.licenses_title)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            MutedText(look, stringResource(R.string.licenses_hint))
            Spacer(Modifier.height(look.cell.rows(1)))
            // `forEachIndexed` gives each entry with its position in the list.
            LICENSES.forEachIndexed { index, entry ->
                // The whole entry is one tap target of at least 48dp.
                LargeTapArea(onClick = { onOpen(index) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicText(
                            text = stringResource(entry.name),
                            style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
                        )
                        BasicText(
                            text = "  " + stringResource(R.string.licenses_read, stringResource(entry.license)),
                            style = look.text.copy(color = accent),
                        )
                        val detail = entry.detail
                        if (detail != null) {
                            BasicText(
                                text = stringResource(detail),
                                style = look.text.copy(color = look.color(ColorSlot.MUTED)),
                                modifier = Modifier.padding(start = look.cell.columns(2)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(look.cell.rows(1)))
            }
            TapText(look, stringResource(R.string.close_button), look.color(ColorSlot.MUTED), onClick = onClose)
        }
    }
}

/**
 * One license's full text, titled with the license's name. The text is read
 * from its raw resource on a background thread (the longest is about 17 KB).
 * The Font Awesome text starts with its attribution, which is part of the file.
 */
@Composable
private fun LicenseText(look: TuiLook, entry: LicenseEntry, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resources = LocalResources.current
    var text by remember(entry) { mutableStateOf("") }
    LaunchedEffect(entry) {
        text = withContext(Dispatchers.IO) {
            // `use` closes the file even if reading fails.
            resources.openRawResource(entry.text).bufferedReader().use { it.readText() }
        }
    }
    val accent = look.color(ColorSlot.ACCENT)
    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " ${stringResource(entry.license)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TapText(look, stringResource(R.string.licenses_back), look.color(ColorSlot.MUTED), onClick = onBack)
            BasicText(text = stringResource(entry.name), style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)))
            Spacer(Modifier.height(look.cell.rows(1)))
            BasicText(text = text, style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)))
            Spacer(Modifier.height(look.cell.rows(1)))
            TapText(look, stringResource(R.string.licenses_back), look.color(ColorSlot.MUTED), onClick = onBack)
        }
    }
}
