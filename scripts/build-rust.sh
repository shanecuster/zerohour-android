#!/usr/bin/env bash
# build-rust.sh -- compile the Rust half of the app and generate the Kotlin
# bridge code. Gradle runs this automatically before every Android build (see
# the `buildRust` task in app/build.gradle.kts); you can also run it by hand:
#
#     scripts/build-rust.sh
#
# It does two jobs:
#
#   1. Compile `zerohour-ffi` (and through it `zerohour-core`) into a native
#      library, `libzerohour_ffi.so`, once for each CPU type the app supports,
#      and put the results where Android packages them:
#          app/src/main/jniLibs/<cpu type>/libzerohour_ffi.so
#
#   2. Run UniFFI's bindgen over the library to write the Kotlin file that lets
#      the app call into it:
#          app/src/main/kotlin/uniffi/zerohour_ffi/zerohour_ffi.kt
#
# Neither output is committed to git; both are rebuilt from the Rust source.
#
# Local core override: if rust/.cargo/config.toml exists (a git-ignored
# `[patch]` pointing zerohour-core at a working copy on this machine), this
# script prints a loud DEV CORE OVERRIDE warning, builds debug WITHOUT --locked
# (the patched source is not in the committed Cargo.lock), and REFUSES any
# release build (releases must use the pinned commit). See the README.
#
# Settings (all optional, passed as environment variables):
#   ZEROHOUR_ABIS      CPU types to build, space separated.
#                     Default: "arm64-v8a x86_64"
#                       arm64-v8a    real phones (your Pixel)
#                       x86_64       the Android Studio emulator on a PC
#                     Also supported: armeabi-v7a (old 32-bit phones), x86.
#                     A release build for the stores should build all of them:
#                       ZEROHOUR_ABIS="arm64-v8a armeabi-v7a x86_64" scripts/build-rust.sh
#   ZEROHOUR_MIN_API   Lowest Android version supported (API level). Default: 26
#   ZEROHOUR_SKIP_NDK  If set to 1, skip step 1 and only write the Kotlin
#                     bindings. Handy for checking the bridge without the NDK.
#   ZEROHOUR_RELEASE   Set to 1 by Gradle for a release build, so this script
#                     can refuse to use a local core override for releases.
#   ANDROID_HOME      Where the Android SDK is. If unset, it is read from
#                     `sdk.dir` in local.properties, which Android Studio writes.
#   ZEROHOUR_NDK_VERSION  The exact NDK version to use, e.g. 30.0.16248370
#                     (Gradle sets this from `pinnedNdkVersion` in
#                     app/build.gradle.kts). If that version is not installed
#                     the build stops with a message instead of quietly using
#                     another one, so builds stay reproducible.
#   (cargo-ndk is not a setting: the script requires exactly the version in
#                     CARGO_NDK_VERSION below and stops if yours differs.)
#   ANDROID_NDK_HOME  Which NDK folder to use. If set, it wins over everything.
#                     If neither this nor ZEROHOUR_NDK_VERSION is set (running
#                     the script by hand), the newest installed NDK is used.

# `set -e`  stop at the first command that fails.
# `set -u`  treat use of an undefined variable as an error (catches typos).
# `set -o pipefail`  a failure anywhere in a `a | b` pipeline counts as failure.
set -euo pipefail

# --- Find the project root ---------------------------------------------------
# `${BASH_SOURCE[0]}` is the path of this script file; `dirname` strips the file
# name and `cd ... && pwd` turns it into an absolute path. The project root is
# the folder above `scripts/`. This lets the script work no matter which folder
# it is started from (Gradle starts it from the project root, you might not).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

# --- Make sure cargo can be found --------------------------------------------
# Android Studio is usually started from the desktop launcher, which does NOT
# read your shell's startup files, so ~/.cargo/bin (where rustup puts `cargo`)
# may be missing from PATH. Add it explicitly.
export PATH="$HOME/.cargo/bin:$PATH"

# --- Settings ----------------------------------------------------------------
# `${NAME:-default}` means "the value of NAME, or the default if NAME is unset
# or empty".
ABIS="${ZEROHOUR_ABIS:-arm64-v8a x86_64}"
MIN_API="${ZEROHOUR_MIN_API:-26}"

# The one cargo-ndk version this app is built with. It is a fixed value, not a
# setting, on purpose: cargo-ndk decides how the compiler is called, so a
# different version could change the finished library. The check further down
# stops the build if the installed one differs. F-Droid reads this line too
# (its recipe installs the same version). Change it here, in the README's
# "Build tools" table, and in F-Droid's recipe together.
CARGO_NDK_VERSION="4.1.2"

# --- Small helper: print an error and quit -----------------------------------
# `>&2` sends the message to the "error" output stream; `exit 1` stops the
# script with a failure code, which makes Gradle report the build as failed.
fail() {
    echo "build-rust.sh: error: $*" >&2
    exit 1
}

# --- Local core override check -----------------------------------------------
# A git-ignored rust/.cargo/config.toml patches zerohour-core to a local path.
# When present it changes the build, so say so loudly and adjust the rules:
#   * never for a release build (releases must use the pinned commit);
#   * debug builds drop --locked, because the patched source is not recorded in
#     the committed Cargo.lock.
LOCKED="--locked"
if [ -f "$ROOT/rust/.cargo/config.toml" ]; then
    if [ "${ZEROHOUR_RELEASE:-0}" = "1" ]; then
        fail "rust/.cargo/config.toml (DEV CORE OVERRIDE) is present, but release builds must use the pinned zerohour-core commit. Remove the override and build again."
    fi
    echo "build-rust.sh: =====================================================" >&2
    echo "build-rust.sh:  DEV CORE OVERRIDE: rust/.cargo/config.toml is in use." >&2
    echo "build-rust.sh:  Building against a LOCAL zerohour-core, not the" >&2
    echo "build-rust.sh:  pinned commit, and WITHOUT --locked. Delete that file" >&2
    echo "build-rust.sh:  to build the pinned core again." >&2
    echo "build-rust.sh: =====================================================" >&2
    LOCKED=""
fi

# --- Check the tools we need are installed -----------------------------------
# `command -v NAME` prints where NAME is, and fails if it is not installed.
# `&>/dev/null` hides the output; we only care about success or failure.
command -v cargo &>/dev/null \
    || fail "cargo not found. Install Rust from https://rustup.rs and try again."

# --- Step 1: compile for Android ---------------------------------------------
if [ "${ZEROHOUR_SKIP_NDK:-0}" != "1" ]; then
    command -v cargo-ndk &>/dev/null \
        || fail "cargo-ndk not found. Install it with:  cargo install cargo-ndk --version $CARGO_NDK_VERSION --locked"

    # Make sure it is exactly the pinned version. `cargo ndk --version` prints
    # something like "cargo-ndk 4.1.2"; `grep -oE` keeps only the part that
    # looks like a version number (digits and dots), and `head -n 1` keeps the
    # first such match. `|| true` stops `set -e` from quitting on a grep that
    # finds nothing, so our own message below is shown instead.
    FOUND_CARGO_NDK="$(cargo ndk --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || true)"
    [ "$FOUND_CARGO_NDK" = "$CARGO_NDK_VERSION" ] \
        || fail "cargo-ndk $CARGO_NDK_VERSION is required but found '${FOUND_CARGO_NDK:-unknown}'. Install the pinned one with:  cargo install cargo-ndk --version $CARGO_NDK_VERSION --locked"

    # Locate the Android SDK: environment variable first, then local.properties.
    if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ROOT/local.properties" ]; then
        # `sed -n 's/^sdk\.dir=//p'` prints only the line starting with
        # "sdk.dir=", with that prefix removed -- leaving just the path.
        ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
    fi
    [ -n "${ANDROID_HOME:-}" ] \
        || fail "Android SDK not found. Open the project in Android Studio once (it writes local.properties), or set ANDROID_HOME."

    # Locate the NDK. Three ways, in order:
    #   1. ANDROID_NDK_HOME is already set: use it as it is.
    #   2. ZEROHOUR_NDK_VERSION is set (the normal case, set by Gradle): use
    #      exactly that version, and stop if it is not installed.
    #   3. Neither: take the newest installed. `ls -1` lists one folder per
    #      line, `sort -V` sorts version numbers properly (so 30.0 comes after
    #      9.0), `tail -n 1` keeps the last, which is the newest.
    if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ZEROHOUR_NDK_VERSION:-}" ]; then
        # `-d` tests that the path is a folder.
        [ -d "$ANDROID_HOME/ndk/$ZEROHOUR_NDK_VERSION" ] \
            || fail "NDK $ZEROHOUR_NDK_VERSION is not installed in $ANDROID_HOME/ndk. In Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side) > tick Show Package Details, then install exactly that version."
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ZEROHOUR_NDK_VERSION"
    elif [ -z "${ANDROID_NDK_HOME:-}" ]; then
        NDK_VERSION="$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -n 1)"
        [ -n "$NDK_VERSION" ] \
            || fail "No NDK found in $ANDROID_HOME/ndk. In Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side)."
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
    fi
    # `export` makes the variables visible to the programs we start below
    # (cargo-ndk reads them).
    export ANDROID_HOME ANDROID_NDK_HOME
    echo "build-rust.sh: using NDK at $ANDROID_NDK_HOME"

    # Build the list of `-t <cpu type>` arguments for cargo-ndk, one per ABI.
    # An array (`( )`) keeps each argument separate, even if one had spaces.
    NDK_ARGS=()
    for abi in $ABIS; do
        NDK_ARGS+=(-t "$abi")
    done

    # Run cargo-ndk from inside rust/ (where Cargo.toml lives):
    #   --platform   lowest Android API level the library must run on
    #   -o           where to copy the finished .so files
    #   build ...    the ordinary `cargo build`, in release (optimized) mode
    #   $LOCKED      --locked normally; empty when a local core override is on
    # The parentheses run this in a subshell so the `cd` does not affect the
    # rest of the script. `${LOCKED:+$LOCKED}` adds the flag only when set.
    (
        cd "$ROOT/rust"
        cargo ndk "${NDK_ARGS[@]}" \
            --platform "$MIN_API" \
            -o "$ROOT/app/src/main/jniLibs" \
            build --release ${LOCKED:+$LOCKED} --package zerohour-ffi
    )
fi

# --- Step 2: generate the Kotlin bridge --------------------------------------
# UniFFI reads the *description* of the exported functions that is embedded in
# the compiled library. That description is identical for every CPU type, so we
# read it from a normal build for THIS computer rather than from an Android
# build. This is quick, and it works even when the NDK step was skipped.
(
    cd "$ROOT/rust"
    cargo build ${LOCKED:+$LOCKED} --package zerohour-ffi

    # `rm -rf` then `mkdir -p`: start from a clean folder so a function we
    # removed from Rust cannot linger in an old generated file.
    OUT="$ROOT/app/src/main/kotlin/uniffi"
    rm -rf "$OUT"
    mkdir -p "$ROOT/app/src/main/kotlin"

    # `cargo run -p uniffi-bindgen -- generate ...` starts our little helper
    # program (rust/uniffi-bindgen) with these arguments:
    #   --library    the compiled glue library to read
    #   --language   write Kotlin (UniFFI also supports Swift, Python, ...)
    #   --out-dir    where to put the generated file
    #   --no-format  skip the optional ktlint pretty-printer (not installed)
    cargo run ${LOCKED:+$LOCKED} --quiet --package uniffi-bindgen -- generate \
        --library target/debug/libzerohour_ffi.so \
        --language kotlin \
        --out-dir "$ROOT/app/src/main/kotlin" \
        --no-format
)

echo "build-rust.sh: done."
