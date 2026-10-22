package com.shanetalkstech.zerohour

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shanetalkstech.zerohour.ui.MainRoute

/**
 * The app's one and only Android "Activity": the screen the launcher opens.
 *
 * In Android, an Activity is the entry point for a screen. Everything you see
 * is drawn by Jetpack Compose, a toolkit where the UI is described by ordinary
 * Kotlin functions marked `@Composable`, so this class has almost nothing to
 * do: it sets up the window and hands the screen over to Compose.
 */
class MainActivity : ComponentActivity() {

    /**
     * Called by Android once, when the screen is created.
     *
     * [savedInstanceState] holds state Android saved if the screen is being
     * recreated (for example after the phone is rotated). We do not need it
     * directly, but we must pass it up to the parent class.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status bar and the navigation bar so the theme's
        // background fills the whole screen, like a terminal would. The bars
        // themselves are transparent; `SystemBarStyle.dark` asks for light
        // icons on them, because every zerohour theme has a dark background.
        // (TRANSPARENT is "no colour at all", not a theme colour.)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // `setContent` gives Compose the root of the screen. Everything from
        // here down is `@Composable` functions.
        setContent {
            MainRoute()
        }
    }
}
