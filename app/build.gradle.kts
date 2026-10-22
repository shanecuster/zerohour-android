// Build file for the `app` module: what the app is called, which Android
// versions it supports, and which libraries it uses.

// An import must come first in the file. It is needed because, inside a Gradle
// script, the bare word `java` means Gradle's own `java { }` setting, so the
// full name `java.util.Properties` would not be found. With the import, the
// short name `Properties` (a key=value file reader) works.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Turns on the Compose compiler (Kotlin support is built into the Android
    // Gradle Plugin itself, so there is no separate Kotlin plugin line).
    alias(libs.plugins.kotlin.compose)
}

// The exact Android NDK (the C/Rust cross-compiler kit) the Rust library is
// built with. Pinning it, instead of "whatever is newest", is what makes two
// builds of the same source come out the same. It is used twice below: AGP's
// `ndkVersion`, and an environment variable that tells scripts/build-rust.sh
// which NDK folder to use. To change it, install that NDK version in Android
// Studio (SDK Manager > SDK Tools > NDK (Side by side)) and edit this line.
val pinnedNdkVersion = "30.0.16248370"

// True when this run was asked for a release build, i.e. any requested Gradle
// task has "release" in its name (assembleRelease, bundleRelease, ...). Used
// below to choose the CPU types, to warn about an incomplete signing setup,
// and to tell build-rust.sh to refuse a local core override.
val releaseBuildRequested: Boolean =
    gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

// Which CPU types the Rust library is built for. Release builds (see above)
// get all three: 64-bit phones, old 32-bit phones, and the x86_64 emulator.
// Everyday debug builds leave this unset, so the script's quicker default
// (arm64-v8a and x86_64) applies. `-PzerohourAbis="..."` on the Gradle command
// line overrides both.
val requestedAbis: String? = providers.gradleProperty("zerohourAbis").orNull
    ?: if (releaseBuildRequested) {
        "arm64-v8a armeabi-v7a x86_64"
    } else {
        null
    }

// The CPU types whose libraries are packed into the APK. It must match what
// scripts/build-rust.sh actually builds (its default is "arm64-v8a x86_64"),
// because our own library `libzerohour_ffi.so` exists only for those. Without
// this list the JNA dependency adds its own libraries for four more CPU types
// (armeabi, mips, mips64, x86); a 32-bit x86 device would then install the app
// and crash at launch, since our library is missing there.
val packagedAbis: List<String> =
    (requestedAbis ?: "arm64-v8a x86_64").split(" ").filter { it.isNotBlank() }

// Release signing details come from `keystore.properties` in the project root.
// That file (and the keystore it points to) is git-ignored: a signing key must
// never be committed. If the file is missing, as on F-Droid's build server or
// a fresh clone, release builds still work; they just come out unsigned.
// See keystore.properties.example and the "Releasing" section of the README.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    // `.use { }` closes the file again when loading is done.
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Looks up one signing setting. The environment variable (when a name is given)
// wins over keystore.properties, and a blank value counts as missing. Returns
// null when neither has it; it never stops the build by itself.
fun signingSetting(key: String, envName: String? = null): String? =
    listOfNotNull(envName?.let { System.getenv(it) }, keystoreProperties.getProperty(key))
        .firstOrNull { it.isNotBlank() }

val signingStoreFile = signingSetting("storeFile")
val signingKeyAlias = signingSetting("keyAlias")
val signingStorePassword = signingSetting("storePassword", "ZEROHOUR_STORE_PASSWORD")
val signingKeyPassword = signingSetting("keyPassword", "ZEROHOUR_KEY_PASSWORD")

// The names of the signing details that are still missing. The signing key is
// used only when this list is empty. Everyday debug builds (Android Studio's
// Run button, which does not see passwords typed into a terminal) never need
// the key, so an incomplete setup must not break them.
val missingSigningDetails: List<String> = listOf(
    "storeFile" to signingStoreFile,
    "keyAlias" to signingKeyAlias,
    "storePassword" to signingStorePassword,
    "keyPassword" to signingKeyPassword,
).filter { it.second == null }.map { it.first }

val canSignRelease: Boolean = missingSigningDetails.isEmpty()

// A release build that was meant to be signed but cannot be: say so loudly,
// because the result will be unsigned and Google Play rejects unsigned files.
if (releaseBuildRequested && keystorePropertiesFile.exists() && !canSignRelease) {
    logger.warn(
        "WARNING: keystore.properties exists but these signing details are missing: " +
            "${missingSigningDetails.joinToString()}. This release build will be UNSIGNED. " +
            "Put them in the file, or set ZEROHOUR_STORE_PASSWORD and ZEROHOUR_KEY_PASSWORD " +
            "in the terminal you build from (see keystore.properties.example).",
    )
}

android {
    // Where the code lives and the prefix of the generated `R` class (R.font...).
    namespace = "com.shanetalkstech.zerohour"
    // The NDK version AGP expects (see `pinnedNdkVersion` above).
    ndkVersion = pinnedNdkVersion
    // The Android SDK Build-Tools (aapt2, zipalign, d8 ...) used to package the
    // app. Naming it (instead of letting AGP pick its own default, which can
    // change when AGP is upgraded) keeps the tool versions readable from the
    // code, which F-Droid's reproducible-build check relies on.
    buildToolsVersion = "36.0.0"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The app's permanent identity on the device and in app stores. It
        // matches tuxwire-android's pattern (com.shanetalkstech.<app>). This is
        // permanent after the first Play release.
        applicationId = "com.shanetalkstech.zerohour"
        // Oldest Android supported: 8.0. Keep in step with ZEROHOUR_MIN_API in
        // scripts/build-rust.sh.
        minSdk = 26
        targetSdk = 37
        // Must go up with every release (stores refuse a lower or equal number).
        // The matching F-Droid changelog is fastlane/.../changelogs/<versionCode>.txt.
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Keep only the CPU types we built (see `packagedAbis` above).
            // `+=` adds each name in the list to the set of allowed types.
            abiFilters += packagedAbis
        }
    }

    // The key used to sign release builds, defined only when every signing
    // detail is available (see `missingSigningDetails` above). For Google Play
    // this is the *upload key*: Play re-signs what you upload with its own app
    // signing key. The two passwords may be left out of keystore.properties and
    // given as the environment variables ZEROHOUR_STORE_PASSWORD and
    // ZEROHOUR_KEY_PASSWORD instead, so they are never written to disk.
    signingConfigs {
        if (canSignRelease) {
            create("release") {
                // `checkNotNull` turns "maybe missing" into "definitely there"
                // (it cannot fail here: canSignRelease was just checked).
                storeFile = File(checkNotNull(signingStoreFile))
                keyAlias = checkNotNull(signingKeyAlias)
                storePassword = checkNotNull(signingStorePassword)
                keyPassword = checkNotNull(signingKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds install as a *separate app* (com.shanetalkstech.zerohour.debug)
            // so they never overwrite or share data with a real release install.
            applicationIdSuffix = ".debug"
        }
        release {
            // Code shrinking (R8) is deliberately OFF. JNA and the Kotlin that
            // UniFFI generates look up classes and methods by name while the
            // app runs, so shrinking would need hand-written "keep" rules, and a
            // mistake in them only shows up as a crash on a real phone. Turn it
            // on only together with a rule set that has been tested on a device.
            isMinifyEnabled = false
            // Sign with the release key when all its details are available.
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose: the BOM first (it pins the versions), then the pieces we use.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)

    // Needed by the Kotlin code that UniFFI generates from the Rust library,
    // and used to call the blocking Rust functions off the main thread.
    implementation(libs.kotlinx.coroutines.android)
    // JNA must be the Android flavour (`@aar`), which bundles the native pieces
    // for each CPU type. A plain jar would not work on a phone.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}

// Runs scripts/build-rust.sh: compiles the Rust library for Android and writes
// the Kotlin bridge file. `workingDir = rootDir` starts it from the project root.
val buildRust = tasks.register<Exec>("buildRust") {
    group = "build"
    description = "Compiles zerohour-ffi with cargo-ndk and generates the UniFFI Kotlin bindings."
    workingDir = rootDir
    commandLine("bash", "scripts/build-rust.sh")
    // Tell the script which NDK to use (it refuses to fall back to another).
    environment("ZEROHOUR_NDK_VERSION", pinnedNdkVersion)
    // Tell the script when this is a release build, so it refuses to build
    // against a local core override (releases must use the pinned commit).
    if (releaseBuildRequested) {
        environment("ZEROHOUR_RELEASE", "1")
    }
    // Only set when a release build, or -PzerohourAbis, asked for specific CPU
    // types; otherwise the script's own default applies.
    requestedAbis?.let { environment("ZEROHOUR_ABIS", it) }
}

// Make every Android build run the Rust build first, so the .so files and the
// generated Kotlin always exist before Kotlin is compiled.
tasks.named("preBuild") {
    dependsOn(buildRust)
}
