#!/usr/bin/env bash
# check-release-apk.sh -- inspect a built release APK before it goes onto a
# phone or into a store. It never changes the APK; it only reads it.
#
#     scripts/check-release-apk.sh [path/to/app-release.apk] [--allow-unsigned]
#
# Without a path it checks app/build/outputs/apk/release/app-release.apk (what
# a signed `./gradlew assembleRelease` writes). `--allow-unsigned` turns the
# missing signature into a note instead of a failure, for checking an unsigned
# build (app-release-unsigned.apk) before the key is set up.
#
# What it checks (each line prints PASS, FAIL or INFO; any FAIL makes the
# script exit with code 1):
#   1. The app's identity: package com.shanetalkstech.zerohour (no .debug),
#      and not debuggable.
#   2. Permissions: exactly INTERNET, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM
#      and RECEIVE_BOOT_COMPLETED.
#   3. The Rust library is there for every CPU type a release ships.
#   4. No machine paths (/home/...), test values, private keys or passwords.
#   5. No server address other than a short list of known, harmless ones.
#   6. YOUR real hostnames and user names do not appear anywhere. They are
#      read from a file OUTSIDE this repo, named by the environment variable
#      ZEROHOUR_CHECK_STRINGS, so they are never written into git. One string
#      per line; blank lines and lines starting with # are ignored. Matches
#      are reported by line number only, so the value is never printed.
#   7. 16 KB page alignment (Google Play requires it for updates).
#   8. The signature (`apksigner verify`), and that it is not the debug key.
#
# The SDK tools (aapt2, apksigner, zipalign) come from the Android SDK:
# ANDROID_HOME if set, otherwise `sdk.dir` in local.properties, with the
# build-tools version named in app/build.gradle.kts.

# `set -e`  stop at the first command that fails.
# `set -u`  treat use of an undefined variable as an error (catches typos).
# `set -o pipefail`  a failure anywhere in a `a | b` pipeline counts as failure.
# Note: grep "fails" (exit code 1) when it finds nothing, so the greps below
# whose empty result is fine end in `|| true`.
set -euo pipefail

# --- Find the project root ---------------------------------------------------
# The folder above scripts/, as in build-rust.sh, so the script works from
# any folder.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

# --- Arguments -----------------------------------------------------------------
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
ALLOW_UNSIGNED=0
# `"$@"` is every argument the script was given, each kept whole.
for arg in "$@"; do
    case "$arg" in
        --allow-unsigned) ALLOW_UNSIGNED=1 ;;
        *) APK="$arg" ;;
    esac
done

# The package name a release must have, and the permissions it must ask for.
PACKAGE="com.shanetalkstech.zerohour"
EXPECTED_PERMISSIONS="android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.SCHEDULE_EXACT_ALARM"

# The CPU types a release build packs (see `requestedAbis` in
# app/build.gradle.kts).
RELEASE_ABIS="arm64-v8a armeabi-v7a x86_64"

# Web addresses that are allowed inside the app's own files (the Rust
# library, the compiled Kotlin and the resources). Anything else that looks
# like http(s)://host.name fails check 5. Each one is explained:
#   couch.example.org     core's placeholder in the sync URL hint text
#   android.googlesource.com, r.android.com, goo.gle, schemas.android.com,
#   www.w3.org            Android, AndroidX and the NDK compiler's own texts
#   youtrack.jetbrains.com  Kotlin's standard library error messages
#   docs.rs               a link in an error message of rustls (TLS)
#   github.com            the About page's source-code links (the app's and
#                         core's public repositories) and the share link
#                         for installs not from Google Play; also the
#                         JetBrains Mono link in the font's license text
#   play.google.com       the About page's Google Play listing (share and
#                         rate links for Play installs)
#   www.apache.org        the Apache License 2.0's own text (res/raw)
#   creativecommons.org   the CC BY 4.0 link in Font Awesome's attribution
#                         (res/raw)
#   scripts.sil.org       the OFL's link to its FAQ, in the font's license
#                         text (res/raw)
#   fontawesome.com       Font Awesome's attribution (res/raw)
#   mozilla.org           the Mozilla Public License 2.0's own text (res/raw)
# The privacy policy's placeholder on a .invalid host is deliberately NOT
# here: it fails this check (and the .invalid check) until the real address
# replaces it.
ALLOWED_HOSTS="couch.example.org
android.googlesource.com
r.android.com
goo.gle
schemas.android.com
www.w3.org
youtrack.jetbrains.com
docs.rs
github.com
play.google.com
www.apache.org
creativecommons.org
scripts.sil.org
fontawesome.com
mozilla.org"

# Not addresses at all: core's setting names. The compiled Rust library keeps
# its short texts back to back with nothing between them, so right after an
# "http://" text it holds, for example,
#     http://sync.enabledsync.databasesync.usernamefalsethe server URL ...
# (the setting names sync.enabled, sync.database, sync.username, the value
# "false", then the start of core's "the server URL must ..." message). The
# address pattern reads that as one long host name, and what sits next to the
# names changes whenever core changes, so an exact entry in ALLOWED_HOSTS
# breaks on the next re-pin. Instead, check 5 skips a candidate when EVERY
# dot-separated part of it is made only of the words below, glued together
# (`enabledsync` = `enabled` + `sync`). A real host name always has a part
# that is not one of these words (its domain, such as "example" or "net").
# The words: core's setting-key words, the values "true" and "false", and
# "the", the first word of the message stored next to them. Underscores
# (lead_minutes) cannot appear in a candidate, so the parts are listed singly.
# Kept short on purpose: only words that really sit next to an "http://" text
# in the library. "day", for example, is left out because .day is a real
# top-level domain, so "sync.day" could be a real server.
SETTING_WORDS="sync|enabled|database|username|password|false|true|reminders|lead|minutes|digest|overdue|the"

# --- Small helpers: report a result ------------------------------------------
# FAILURES counts the failed checks; the exit code at the end depends on it.
FAILURES=0
pass() { echo "PASS  $*"; }
info() { echo "INFO  $*"; }
fail() { echo "FAIL  $*"; FAILURES=$((FAILURES + 1)); }
# For problems that stop the script before any check can run.
die() { echo "check-release-apk.sh: error: $*" >&2; exit 2; }

[ -f "$APK" ] || die "no APK at $APK. Build it first (./gradlew assembleRelease) or pass its path."

# --- Find the SDK tools ------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ROOT/local.properties" ]; then
    # Print only the `sdk.dir=` line, with that prefix removed.
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
fi
[ -n "${ANDROID_HOME:-}" ] || die "Android SDK not found. Set ANDROID_HOME, or open the project in Android Studio once (it writes local.properties)."
# The build-tools version the app is built with: the text between the quotes
# on the `buildToolsVersion = "..."` line.
BUILD_TOOLS_VERSION="$(sed -n 's/^ *buildToolsVersion = "\(.*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"
BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
for tool in aapt2 apksigner zipalign; do
    [ -x "$BT/$tool" ] || die "$tool not found in $BT. Install build-tools $BUILD_TOOLS_VERSION with Android Studio's SDK Manager."
done

# --- Unpack the APK into a temporary folder -----------------------------------
# An APK is a zip file. `mktemp -d` makes a new, empty, private folder; the
# `trap ... EXIT` line deletes it again however the script ends.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$APK" -d "$WORK"
# The files that come from THIS project (the Rust library, the compiled Kotlin,
# the resources, the manifest), as opposed to libraries' prebuilt files.
# `shopt -s nullglob` makes a pattern that matches nothing expand to nothing.
shopt -s nullglob
# `res/*.txt` are the app's own res/raw texts (the license texts of the
# About page's licenses list): AGP renames res/raw files to short names
# like res/0M.txt and keeps their .txt ending, while libraries' text files
# stay under META-INF/.
OWN_FILES=("$WORK"/lib/*/libzerohour_ffi.so "$WORK"/classes*.dex "$WORK/resources.arsc" "$WORK/AndroidManifest.xml" "$WORK"/res/*.txt)
shopt -u nullglob

echo "Checking $APK"
echo

# --- 1. Identity ---------------------------------------------------------------
# `aapt2 dump badging` prints the manifest's main facts, one per line.
BADGING="$("$BT/aapt2" dump badging "$APK")"
# The `package:` line looks like: package: name='...' versionCode='1' ...
PKG_LINE="$(grep '^package:' <<< "$BADGING" || true)"
FOUND_PACKAGE="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$PKG_LINE")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<< "$PKG_LINE")"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "$PKG_LINE")"
if [ "$FOUND_PACKAGE" = "$PACKAGE" ]; then
    pass "package $FOUND_PACKAGE, versionCode $VERSION_CODE, versionName $VERSION_NAME"
else
    fail "package is '$FOUND_PACKAGE', expected $PACKAGE (a debug build has .debug)"
fi
# A debuggable app lets anyone with a USB cable read its private files.
if grep -q '^application-debuggable' <<< "$BADGING"; then
    fail "the APK is debuggable (this is a debug build)"
else
    pass "not debuggable"
fi

# --- 2. Permissions ------------------------------------------------------------
# Lines look like: uses-permission: name='android.permission.INTERNET'.
# AndroidX adds <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION by itself
# (a private permission it uses to keep receivers registered in code private
# on older Android versions), so that one is left out of the comparison.
FOUND_PERMISSIONS="$("$BT/aapt2" dump permissions "$APK" \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" \
    | grep -v "^$PACKAGE\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$" \
    | sort || true)"
if [ "$FOUND_PERMISSIONS" = "$EXPECTED_PERMISSIONS" ]; then
    pass "permissions: $(tr '\n' ' ' <<< "$FOUND_PERMISSIONS")"
else
    fail "permissions differ. Found:"
    sed 's/^/        /' <<< "$FOUND_PERMISSIONS"
fi

# --- 3. Native libraries ---------------------------------------------------------
for abi in $RELEASE_ABIS; do
    if [ -f "$WORK/lib/$abi/libzerohour_ffi.so" ]; then
        pass "libzerohour_ffi.so for $abi"
    else
        fail "libzerohour_ffi.so missing for $abi"
    fi
done

# --- 4. Machine paths, test values, keys, passwords ----------------------------
# /home/<name> anywhere in the APK. The one known exception is JNA's own
# prebuilt libjnidispatch.so, which carries the path of the JNA maintainers'
# build machine (/home/matthias/...), not ours. `grep -o` prints each match
# as "file:match"; the exception is then filtered out.
HOME_HITS="$(cd "$WORK" && grep -raoE '/home/[A-Za-z0-9._-]*' . \
    | grep -vE '^\./lib/[^/]+/libjnidispatch\.so:/home/matthias$' \
    | sort -u || true)"
if [ -z "$HOME_HITS" ]; then
    pass "no /home/ paths (apart from JNA's prebuilt library)"
else
    fail "machine paths found (is --remap-path-prefix in scripts/build-rust.sh still working?):"
    sed 's/^/        /' <<< "$HOME_HITS"
fi

# Each entry: a label, then an extended regular expression (`grep -E`)
# searched case-insensitively in the project's own files. The entries are
# chosen so that none of them matches anything in a clean build.
#   /Users/, C:\Users\   macOS and Windows home folders
#   ts.net              Tailscale host names
#   sandbox             the sync test database and user
#   <name>.invalid      the test addresses used on the emulator
#   PRIVATE KEY         a key pasted in as PEM text
#   password=, storePassword, keyPassword
#                       a password in a URL or a signing setting. The
#                       "not a letter before it" part skips Compose's
#                       harmless "isPassword=".
PATTERNS=(
    "macOS or Windows home folders|/Users/|C:\\\\Users\\\\"
    "Tailscale host names|ts\\.net"
    "mentions of the sync test database or user (sandbox)|sandbox"
    ".invalid test addresses|[a-z0-9-]\\.invalid([^a-z]|$)"
    "PEM private keys|-----BEGIN [A-Z ]*PRIVATE KEY-----"
    "password settings|(^|[^a-z])password=|storepassword|keypassword"
)
for entry in "${PATTERNS[@]}"; do
    # Split "label|regex" at the FIRST |: `%%|*` keeps what is before it,
    # `#*|` what is after it (the regex may contain more | characters).
    label="${entry%%|*}"
    regex="${entry#*|}"
    # `-l` lists the files that match; `-a` reads binary files as text;
    # `-i` ignores upper/lower case; `--` ends the options, in case the
    # pattern starts with a dash.
    hits="$(grep -laiE -- "$regex" "${OWN_FILES[@]}" || true)"
    if [ -z "$hits" ]; then
        pass "no $label"
    else
        fail "$label found in: $(sed "s#^$WORK/##" <<< "$hits" | tr '\n' ' ')"
    fi
done

# --- 5. Server addresses ---------------------------------------------------------
# Every http(s)://host.name in the project's own files must be on the
# ALLOWED_HOSTS list above, or be made only of SETTING_WORDS. `sed` strips
# the http(s):// part.
FOUND_HOSTS="$(grep -aohE 'https?://[A-Za-z0-9.-]+\.[A-Za-z0-9.-]+' "${OWN_FILES[@]}" \
    | sed -E 's#^https?://##' | sort -u || true)"
# One or more setting words glued together, then one or more ".<the same>"
# parts, and nothing else. `-v` keeps the lines that do NOT match; `-i`
# ignores upper/lower case.
SETTING_WORDS_ONLY="^($SETTING_WORDS)+(\\.($SETTING_WORDS)+)+\$"
# `comm -23 A B` prints the lines only in A; both inputs must be sorted.
UNEXPECTED_HOSTS="$(comm -23 <(printf '%s\n' "$FOUND_HOSTS" | sed '/^$/d') <(sort -u <<< "$ALLOWED_HOSTS") \
    | grep -viE -- "$SETTING_WORDS_ONLY" || true)"
if [ -z "$UNEXPECTED_HOSTS" ]; then
    pass "no server addresses other than the known harmless ones"
else
    fail "unexpected addresses (a server URL in the code? or a new library text to add to ALLOWED_HOSTS):"
    sed 's/^/        /' <<< "$UNEXPECTED_HOSTS"
fi

# --- 6. Your real hostnames and user names -------------------------------------
STRINGS_FILE="${ZEROHOUR_CHECK_STRINGS:-}"
if [ -z "$STRINGS_FILE" ]; then
    fail "ZEROHOUR_CHECK_STRINGS is not set: your real hostnames and user names were NOT checked. Point it at your strings file (kept outside every repo)."
elif [ ! -r "$STRINGS_FILE" ]; then
    fail "ZEROHOUR_CHECK_STRINGS names a file that cannot be read: $STRINGS_FILE"
else
    # `realpath` gives the full, resolved path, so the test below cannot be
    # fooled by `..` or a link. A strings file inside this repo could be
    # committed by mistake, which is exactly what it must never be.
    STRINGS_REAL="$(realpath "$STRINGS_FILE")"
    case "$STRINGS_REAL" in
        "$ROOT"/*) fail "the strings file is inside this repo ($STRINGS_REAL); move it outside every repo." ;;
        *)
            checked=0
            line_no=0
            # Read the file one line at a time. `IFS=` keeps spaces at the
            # ends of a line; `-r` keeps backslashes as they are. The
            # `|| [ -n "$line" ]` also handles a last line with no newline.
            while IFS= read -r line || [ -n "$line" ]; do
                line_no=$((line_no + 1))
                # Remove a Windows line ending (\r), then skip blanks and comments.
                line="${line%$'\r'}"
                case "$line" in "" | "#"*) continue ;; esac
                checked=$((checked + 1))
                # `-F` searches for the text exactly as written (no regex
                # meaning for dots and so on). The whole APK is searched.
                hits="$(grep -rlaiF -- "$line" "$WORK" || true)"
                if [ -n "$hits" ]; then
                    fail "line $line_no of the strings file was found in: $(sed "s#^$WORK/##" <<< "$hits" | tr '\n' ' ')"
                fi
            done < "$STRINGS_FILE"
            if [ "$checked" -eq 0 ]; then
                fail "the strings file has no strings in it (only blank or # lines)"
            else
                info "checked $checked string(s) from the strings file (any match is listed above as FAIL)"
            fi
            ;;
    esac
fi

# --- Demo data (information only) ------------------------------------------------
# Core's demo seeder is compiled into every build; release builds can never
# open it (DatabaseHolder.usesDemo needs BuildConfig.DEBUG). "Take out
# recycling" is one of its fictional task titles. Removing the code from
# release builds would need a core feature split (see ANDROID_PLAN.md).
if grep -qaF 'Take out recycling' "${OWN_FILES[@]}"; then
    info "demo seeder text is present in the Rust library but unreachable in a release build (expected)"
fi

# --- 7. 16 KB alignment ----------------------------------------------------------
# `-c` only checks; `-P 16` asks for 16 KB pages for native libraries; `4`
# is the usual 4-byte alignment for everything else.
if "$BT/zipalign" -c -P 16 4 "$APK" > /dev/null 2>&1; then
    pass "16 KB page alignment"
else
    fail "not 16 KB aligned (zipalign -c -P 16 -v 4 shows which files)"
fi

# --- 8. Signature ------------------------------------------------------------------
# `apksigner verify` exits with an error when the APK is unsigned or the
# signature is broken. `--print-certs` shows who signed it.
if SIGNER_OUT="$("$BT/apksigner" verify --verbose --print-certs "$APK" 2>&1)"; then
    pass "apksigner verify: signature is valid"
    # Show the scheme lines, the signer's name (DN) and its SHA-256
    # fingerprint, to compare with `keytool -list -v` on your keystore.
    grep -E '^Verified using v[0-9]|Signer #1 certificate (DN|SHA-256)' <<< "$SIGNER_OUT" | sed 's/^/        /'
    # Android Studio's debug key is the same for everyone; a release signed
    # with it could be replaced by anybody.
    if grep -q 'CN=Android Debug' <<< "$SIGNER_OUT"; then
        fail "signed with the Android debug key, not zerohour-upload"
    fi
elif [ "$ALLOW_UNSIGNED" = "1" ]; then
    info "not signed (allowed by --allow-unsigned; a phone will refuse to install it)"
else
    fail "apksigner verify failed (unsigned, or a broken signature):"
    sed 's/^/        /' <<< "$SIGNER_OUT"
fi

# --- Result ------------------------------------------------------------------------
echo
if [ "$FAILURES" -eq 0 ]; then
    echo "All checks passed."
else
    echo "$FAILURES check(s) FAILED."
    exit 1
fi
