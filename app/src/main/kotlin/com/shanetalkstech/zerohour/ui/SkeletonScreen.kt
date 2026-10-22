package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zerohour_ffi.coreVersion

/**
 * The skeleton screen (plan step 3).
 *
 * It calls into the Rust library for the build version and the default theme,
 * then shows the version centred on the theme's background, in the theme's
 * accent colour, with the theme's name below in muted text. Seeing the right
 * colours and version on the emulator confirms the Kotlin <-> Rust bridge is
 * wired up end to end. The real UI (panels, the list, the banner) comes later.
 *
 * Text is drawn with `BasicText` from Compose *foundation*: the app does not
 * depend on Material (the plan avoids Material 3), so it styles text itself.
 * `remember` keeps the values from the first composition, so the (cheap) calls
 * into Rust are not repeated every time Compose redraws.
 */
@Composable
fun SkeletonScreen() {
    val theme = remember { defaultTheme() }
    val version = remember { coreVersion() }
    val colors = theme.colors

    // A full-screen column, painted with the theme background, centring its
    // children both ways. All colours come from the theme -- never hard-coded.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.toColor())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app name, in the Tasks colour, so it is obviously theme-driven.
        BasicText(
            text = "zerohour",
            style = TextStyle(
                color = colors.task.toColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
            ),
        )
        // The version the Rust side reports, in the accent colour.
        BasicText(
            text = "core $version",
            modifier = Modifier.padding(top = 12.dp),
            style = TextStyle(
                color = colors.accent.toColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            ),
        )
        // The active theme's name, in muted text.
        BasicText(
            text = "theme: ${theme.name}",
            modifier = Modifier.padding(top = 8.dp),
            style = TextStyle(
                color = colors.muted.toColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
