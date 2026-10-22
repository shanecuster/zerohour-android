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
- **Privacy:** the only permission is `INTERNET` (a later version adds the
  permissions reminders need). Everything is stored on the device. Syncing to
  your own CouchDB server is optional, off by default, and `https://` only. The
  full policy is in [PRIVACY.md](PRIVACY.md).

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
3. **Check the release build.** `./gradlew assembleRelease` must succeed and
   `app/build/outputs/apk/release/` must hold an APK with `arm64-v8a`,
   `armeabi-v7a` and `x86_64` libraries (`unzip -l <apk> | grep '\.so'`). Google
   Play requires 16 KB page alignment for updates from 1 February 2027:
   `zipalign -c -P 16 -v 4 <apk>` (from the SDK's `build-tools`) must say
   "Verification successful".

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
   policy. The app collects no data itself (see that file) and asks only for
   `INTERNET`.

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

## Licence

MIT. The bundled font is JetBrains Mono (unmodified) under the SIL OFL 1.1; see
`third_party/fonts/`.
