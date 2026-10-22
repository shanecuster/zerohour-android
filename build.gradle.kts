// Top-level build file. It only *declares* the plugins; the `app` module
// applies them. `apply false` means "make this plugin available, but do not
// activate it here".
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
