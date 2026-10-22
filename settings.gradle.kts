// settings.gradle.kts: tells Gradle which projects make up the build and where
// to download plugins and libraries from.

// Where Gradle looks for *build plugins* (such as the Android Gradle Plugin).
pluginManagement {
    repositories {
        // Google's repository, restricted to the groups it actually hosts so
        // Gradle does not waste time asking it for everything.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// No JDK auto-download plugin here on purpose: F-Droid builds with the
// operating system's own OpenJDK and does not allow fetching one mid-build.
// Gradle simply runs on whichever JDK is installed (Android Studio brings its
// own); the project never asks for a specific Java "toolchain".

// Where Gradle looks for the app's *libraries*. FAIL_ON_PROJECT_REPOS means
// modules may not add their own repositories, so every download source is
// listed here, in one place.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The project's name, and the one module it contains (the `app/` folder).
rootProject.name = "zerohour"
include(":app")
