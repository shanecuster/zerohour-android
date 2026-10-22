package com.shanetalkstech.zerohour

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shanetalkstech.zerohour.ui.SkeletonScreen

/**
 * The app's one and only Android "Activity": the screen the launcher opens.
 *
 * In Android, an Activity is the entry point for a screen. Everything you see
 * is drawn by Jetpack Compose, a toolkit where the UI is described by ordinary
 * Kotlin functions marked `@Composable`, so this class has almost nothing to
 * do: it just hands the screen over to Compose.
 *
 * This is the skeleton (plan step 3): it proves the whole chain works -- Gradle
 * builds the Rust library, loads it, and calls into it -- by showing the
 * version string the Rust side reports, in a colour from the shared theme. The
 * real screens come in later steps.
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
        // background colour fills the whole screen, like a terminal would.
        enableEdgeToEdge()

        // `setContent` gives Compose the root of the screen. Everything from
        // here down is `@Composable` functions.
        setContent {
            SkeletonScreen()
        }
    }
}
