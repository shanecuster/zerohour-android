# zerohour-android

A native Android to-do list and countdown app that looks and feels like a
terminal app: a monospace font, bordered panels, and the same colour themes as
[zerohour](https://github.com/shanecuster/zerohour). No Termux needed.

Track **tasks** (things to do, with optional due dates and times) and **events**
(dates to count down to) in one themeable screen. Add a birthday once and it
counts down every year; after the date passes it rolls over to the next one on
its own.

Status: early development. The app is built in small steps (see
[ANDROID_PLAN.md](ANDROID_PLAN.md)); the Rust engine is shared with the terminal
app and pinned to one commit.

## How it fits together

- **UI:** Kotlin + Jetpack Compose. Box-drawing borders are drawn on a Compose
  Canvas, with a bundled monospace Nerd Font, so the screen reads like the
  terminal UI.
- **Engine:** `zerohour-core`, the same Rust crate the terminal app uses, called
  through [UniFFI](https://mozilla.github.io/uniffi-rs/). The themes, icon
  table, banner and the decisions about what the list shows all come from core,
  so both apps look and behave the same.
- **Privacy:** everything is stored on the device. The permissions are
  `INTERNET` (sync) and, for reminders, `POST_NOTIFICATIONS`,
  `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`. Syncing to
  your own CouchDB server is optional, off by default, and `https://` only;
  it runs when you tap sync now, and when the app opens if you switch
  **On launch** on.
  The server needs a certificate from a public certificate authority (such as
  Let's Encrypt): the app checks it against Mozilla's list built into the app,
  so self-signed and private-CA servers are not supported. The full policy is
  in [PRIVACY.md](PRIVACY.md).

## Reminders

Off until switched on in **Settings → Reminders ›**. Core plans them (the same
rules the terminal app's `zerohour remind` uses) and the phone turns them into
Android alarms and notifications; nothing about them leaves the phone.

- **What reminds:** a task with a due time, at that time (plus an earlier one
  if **Lead** is set); a repeating task also at each later scheduled time
  while it stays open, so a missed Monday 2 PM still reminds on Tuesday at
  2 PM; a task due on a day without a time, at **Day time** (09:00) that day;
  and, if **Digest** is on, a daily list of overdue tasks at Day time (a
  task is overdue once its due day is over, not when its due time passes).
  Never events, completed or deleted tasks, or anything on the demo database.
- **Done** on a notification completes the task like a swipe (a repeating
  task gets its next instance). The digest has no Done.
- **Exact or late:** with *Alarms & reminders* allowed for the app (off by
  default on Android 14 and later), reminders come at the minute. Without it
  they use inexact alarms, which Android may deliver up to about an hour
  late; the Settings row then says "on, may be late" and the Reminders page
  offers the setting.
- **48 hours at a time:** the app sets alarms for the next 48 hours and plans
  again after every change, sync, time or time-zone change, app update and
  restart, each time the app is opened, and once a day on its own.
- **After a restart, nothing fires until the phone is first unlocked:** the
  database is in storage Android keeps locked until then, so the alarms are
  set again only after the first unlock. A reminder whose time passed while
  the phone was off or locked is not replayed.
- **Force stop** (in the system's app settings) wipes the app's alarms until
  the app is opened again.
- **Daylight saving:** a reminder in the hour the clocks skip comes just after
  it (02:30 becomes 03:30); in the hour that happens twice, at the first one.

To see the alarms the app has set, with the phone or emulator connected:

```bash
adb shell dumpsys alarm | grep -B1 -A4 com.shanetalkstech.zerohour
```

Each reminder shows as `REMINDER_FIRE` with its time (`origWhen`), plus one
`REMINDER_REFILL` about a day ahead. Add `-s <serial>` after `adb` when more
than one device is connected (`adb devices` lists them).

## About

**Settings → About ›** shows the installed version (read from the build, for
example `zerohour 0.2.1 (4)`, with `debug` after it on debug builds) and these
rows:

- **Privacy policy**, **Source: Android app** and **Source: core and desktop
  app** open the page in your browser.
- **Share zerohour** opens Android's share sheet with a short text and a link:
  the Google Play page if Google Play installed the app, otherwise this
  repository.
- **Rate on Google Play** opens the Play app (or the Play web page without
  it). It is shown only when Google Play installed the app; sideloaded and
  F-Droid installs don't have it. No Google Play library is used.
- **Open source licenses** lists what is built into the app and opens each
  license's full text, offline. Where each text comes from, and how to check
  the list again after a dependency changes, is in `third_party/licenses/`.

zerohour itself sends nothing from this page: each row hands a link to the app
you pick. If no app can open it, the page says so.

## Building

You need Android Studio (with the SDK and the NDK from SDK Manager → SDK
Tools), Rust via [rustup](https://rustup.rs), and cargo-ndk:

```bash
cargo install cargo-ndk --version 4.1.2 --locked
```

The build is pinned so it comes out the same every time:

- **NDK:** exactly the version in `pinnedNdkVersion` in `app/build.gradle.kts`
  (currently `30.0.16248370`). Install that version in SDK Manager (tick
  *Show Package Details* under *NDK (Side by side)*). The build stops with a
  message if it is missing, rather than using a different one.
- **cargo-ndk:** version `4.1.2`, the version this app is built and tested
  with. Check yours with `cargo ndk --version`.
- **Rust:** `rust/rust-toolchain.toml` names the exact compiler version and the
  four Android targets; rustup downloads them the first time you build, so you
  do not need `rustup target add`.
- **Core:** `zerohour-core` is pinned to one commit in `rust/Cargo.toml` and
  built with `features = ["sync"]` but without the OS keyring. `Cargo.lock` is
  committed and the build uses `--locked`.

### Working on core at the same time

To test an unreleased change to `zerohour-core` without pushing it, create a
git-ignored `rust/.cargo/config.toml` that points the pinned dependency at a
local checkout with a **relative** path:

```toml
[patch."https://github.com/shanecuster/zerohour"]
zerohour-core = { path = "../../zerohour/core" }
```

`scripts/build-rust.sh` prints a loud `DEV CORE OVERRIDE` warning whenever that
file exists, builds debug without `--locked`, and refuses to make a release
build (releases must use the pinned commit). Delete the file to go back to the
pinned core. It is never committed.

### Build tools

Every tool that shapes the finished APK, and where its version is set. Change
one and the APK may differ, so change the table and the source together.

| Tool | Version | Set in |
| --- | --- | --- |
| JDK | Android Studio's bundled JDK (`/opt/android-studio/jbr` via `JAVA_HOME`) | installed on the machine |
| Gradle | 9.6.0 (checksum-verified) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| SDK Build-Tools | 36.0.0 | `buildToolsVersion` in `app/build.gradle.kts` |
| Android platform | API 37 | `compileSdk` in `app/build.gradle.kts` |
| NDK | 30.0.16248370 | `pinnedNdkVersion` in `app/build.gradle.kts` |
| Rust | 1.97.0 | `rust/rust-toolchain.toml` |
| cargo-ndk | 4.1.2 (checked by the build) | `CARGO_NDK_VERSION` in `scripts/build-rust.sh` |
| Crates | exact versions | `rust/Cargo.lock` (`--locked`) |

Then open this folder in Android Studio (**Open**, not New Project) and press
Run. Gradle calls `scripts/build-rust.sh`, which builds the Rust library for
each CPU type and generates the Kotlin bindings. Everyday builds make
`arm64-v8a` and `x86_64`; any release build (a Gradle task with "release" in
its name) also makes `armeabi-v7a`. Override with
`-PzerohourAbis="arm64-v8a x86_64"`.

## Releasing

### Every release

1. **Version:** raise `versionCode` (it must go up every release) and
   `versionName` in `app/build.gradle.kts`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. **Merge, then tag** the release `v<versionName>` (for example `v0.1.1`) and
   push the tag, because F-Droid builds from tags. Never move or reuse a tag; a
   fix is a new version and a new tag.
3. **Check the release build.** `./gradlew lintRelease` must report 0 errors
   (lint's `NewApi` check is also fatal in `assembleRelease` itself, so a call
   newer than minSdk stops the build). Then `./gradlew assembleRelease` and
   `scripts/check-release-apk.sh` (see "Checking a release APK" below), which
   covers the `arm64-v8a`, `armeabi-v7a` and `x86_64` libraries, and the 16 KB
   page alignment Google Play requires for updates from 1 February 2027.
4. **About page:** the Privacy policy row opens `PRIVACY.md` on `master`
   (`PRIVACY_POLICY_URL` in
   `app/src/main/kotlin/com/shanetalkstech/zerohour/about/AboutLinks.kt`), so
   a policy change must be merged and pushed to `master` to reach users; update
   its "Last updated" line. When a dependency or core's pin changed, check the
   Open source licenses list as `third_party/licenses/README.md` describes.

### Checking a release APK

`scripts/check-release-apk.sh [apk] [--allow-unsigned]` only reads the APK
(by default `app/build/outputs/apk/release/app-release.apk`) and prints PASS,
FAIL or INFO per check: the package (no `.debug`) and not debuggable; exactly
the four permissions listed under Google Play below; the Rust library for all
three CPU types; no `/home/` paths, test values (`sandbox`, `.invalid`
addresses, `ts.net`), PEM private keys or password settings; no web address
outside a short, commented allowlist in the script; 16 KB alignment; and
`apksigner verify`, refusing Android Studio's debug key. It exits with 1 if
anything failed.

Your **real hostnames and user names** are checked too, without ever being
written into this repo: keep them in a plain text file outside every repo (one
per line; blank lines and `#` comments are ignored) and point
`ZEROHOUR_CHECK_STRINGS` at it. A match is reported by line number, never by
value. If the variable is unset, that check fails rather than being skipped.

```bash
# fish
set -x ZEROHOUR_CHECK_STRINGS /path/outside/the/repo/zerohour-check-strings.txt
scripts/check-release-apk.sh
```

The Rust library has no machine paths because `scripts/build-rust.sh` passes
`--remap-path-prefix` (cargo's folder becomes `/cargo`, this project
`/zerohour-android`). Core's demo seeder is compiled in but cannot be opened
in a release build; the script notes it as INFO.

### Sideloading a signed release on your own phone

1. Make the `zerohour-upload` key and `keystore.properties` once (steps 1 and
   2 under "Google Play" below; the same key signs both).
2. In the terminal you build from, set both passwords (fish:
   `read -s -x ZEROHOUR_STORE_PASSWORD` and `read -s -x ZEROHOUR_KEY_PASSWORD`;
   with keytool's default PKCS12 keystore they are the same password), then
   `./gradlew assembleRelease`. The signed APK is
   `app/build/outputs/apk/release/app-release.apk` (an `-unsigned` name means
   the signing details were not found).
3. Run `scripts/check-release-apk.sh`; every line must be PASS or INFO.
4. Install on one specific phone: `adb devices -l` lists the serial numbers,
   then `adb -s <serial> install app/build/outputs/apk/release/app-release.apk`.
   It installs next to the `.debug` app (a separate app with its own data,
   left untouched). Later versions install over it with `install -r`, as long
   as they are signed with the same key and `versionCode` does not go down.
5. First launch: the real, empty database (no "demo" in the header, no Data
   row in Settings), reminders off and sync off until you switch them on.

### Google Play: the signed bundle

Play takes an Android App Bundle (`.aab`), signed with an **upload key**; Play
re-signs what users download with its own app signing key.

1. **Make the upload key once**, outside this folder, and back it up. If it is
   lost you cannot upload updates with it.

   ```bash
   mkdir -p ~/keys
   keytool -genkeypair -v -keystore ~/keys/zerohour-upload.jks \
     -alias zerohour-upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   keytool's default keystore type is PKCS12, which has a single password: it
   ignores a separate key password, so `storePassword` and `keyPassword` (or
   the two environment variables below) are the same. Keep the password in a
   password manager.

2. **Point the build at it.** Copy `keystore.properties.example` to
   `keystore.properties` (git-ignored) and fill in `storeFile` (a full path) and
   `keyAlias`. To keep the passwords out of any file, leave them out and set
   them in the terminal you build from (bash: `read -rs ZEROHOUR_STORE_PASSWORD;
   export ZEROHOUR_STORE_PASSWORD`; fish: `read -s -x ZEROHOUR_STORE_PASSWORD`),
   and the same for `ZEROHOUR_KEY_PASSWORD`.
3. **Build the bundle:** `./gradlew bundleRelease`. It lands in
   `app/build/outputs/bundle/release/app-release.aab`. Without
   `keystore.properties` the same command still works but the result is
   unsigned, which Play will reject. If the file exists but a detail (such as a
   password) is missing, the build prints a `WARNING` naming it and the result
   is unsigned too. Debug builds, including Android Studio's Run button, never
   need the key.
4. **In Play Console,** use the public link to `PRIVACY.md` as the privacy
   policy. The app collects no data itself (see that file). Its permissions
   are `INTERNET`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` (not
   `USE_EXACT_ALARM`, which Play keeps for alarm-clock and calendar apps) and
   `RECEIVE_BOOT_COMPLETED`. Re-check Play's exact-alarm policy wording at
   each release.

Code shrinking (R8) is off on purpose; the reason is next to `isMinifyEnabled`
in `app/build.gradle.kts`.

### F-Droid

- **Metadata** lives in `fastlane/metadata/android/en-US/`. Add phone
  screenshots as `phoneScreenshots/1.png`, `2.png`, ... in that folder.
- **No JDK download plugin:** the project builds with whichever JDK is
  installed, as F-Droid's build server requires. Do not commit
  `gradle/gradle-daemon-jvm.properties` (it is git-ignored).
- **Signing keys:** F-Droid signs with its own key unless the build is verified
  reproducible and your signature is used. Android refuses to update an app with
  one signed by a different key, so a phone cannot move between the F-Droid and
  Play builds without reinstalling.

Rust-only checks:

```bash
cd rust
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

Kotlin unit tests (plain JVM, no phone needed; the daylight-saving rule and
the alarm request codes):

```bash
./gradlew :app:testDebugUnitTest
```

## License

MIT. The bundled font is JetBrainsMono Nerd Font Mono 3.5.1 (unmodified) under
the SIL OFL 1.1, and the Font Awesome icons it carries are CC BY 4.0; see
`third_party/fonts/`. Every component built into the app, with its license's
full text, is listed in the app under **Settings → About › → Open source
licenses** (sources in `third_party/licenses/`).
