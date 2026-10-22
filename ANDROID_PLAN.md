# zerohour-android: approved plan

This file is the source of truth for the Android app. A fresh Claude Code session that has no earlier context should read this file first, then continue from "Status" at the bottom. The inspect-and-report step is already DONE and APPROVED: do not repeat it, and do not guess at anything this file doesn't say. Ask instead.

## What this repo is

The native Android app for zerohour, a Rust + Ratatui to-do and countdown app. It lives in its own repo (`~/Projects/zerohour-android`, remote `shanecuster/zerohour-android`, branch `master`). The app must LOOK like the terminal UI: bundled monospace Nerd Font, box-drawing borders drawn on a Compose Canvas, the same theme palettes, icon table and banner as the TUI (all from core), block-character charts, about 40 to 45 monospace columns in portrait at normal phone font sizes, with touch on top.

Read-only references (never modify, build in, or commit to either):
- `~/Projects/zerohour`: the Rust core. Read `NOTES.md` and `docs/BUILD_PLAN.md` (Phase 3).
- `~/Projects/tuxwire-android`: a finished app that is frozen in store review. Never touch the tuxwire repos.

## The Rust core

Pinned as a git dependency in `rust/Cargo.toml`:

```toml
zerohour-core = { git = "https://github.com/shanecuster/zerohour",
                  rev = "87255bf5396dbf552ee107a63b882d14efcb7eda",
                  default-features = false, features = ["sync"] }   # sync without keyring
```

- The pinned commit must already be on GitHub (it is). New core work (the reminder planner and migration 4) is merged and pushed in `~/Projects/zerohour` first, then the hash is bumped here.
- Local development override: a git-ignored `rust/.cargo/config.toml` with a `[patch."https://github.com/shanecuster/zerohour"]` entry using a RELATIVE path. `scripts/build-rust.sh` prints a loud "DEV CORE OVERRIDE" warning when it exists, drops `--locked` for debug builds, and REFUSES release builds. The file must be in `.gitignore` from the first commit.
- `Cargo.lock` is committed. No machine paths anywhere.

## Reuse from tuxwire-android (rename tuxwire to zerohour)

- Gradle: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, the Gradle 9.6.0 wrapper with its sha256.
- `app/build.gradle.kts`: NDK 30.0.16248370, build-tools 36.0.0, ABI handling, signing from a git-ignored `keystore.properties` (or environment variables), `.debug` suffix on debug builds, R8 off, `preBuild` runs `buildRust`.
- `scripts/build-rust.sh` (cargo-ndk 4.1.2, `--locked`, bindgen from the host build) and `rust/rust-toolchain.toml` (Rust 1.97.0 and the Android targets).
- `rust/uniffi-bindgen/`, `.gitignore`, and the layout of `third_party/fonts/` (OFL licence, README with sha256). Note: tuxwire bundles *plain* JetBrains Mono, so zerohour's JetBrainsMono Nerd Font Mono 3.5.1 comes from the official Nerd Fonts release instead (provenance in `third_party/fonts/README.md`).
- The AES-GCM password store pattern from `SyncSettings.kt`: adapt it, don't copy it.

## Change from tuxwire-android

- No tokio. zerohour's sync is blocking `ureq`. Kotlin calls Rust on `Dispatchers.IO`.
- A new FFI surface in `rust/zerohour-ffi` (a cdylib that depends on core, so core never imports UniFFI). Only the pattern carries over: `FfiError`, record types, theme export. Dates cross the boundary as ISO strings.
- Manifest: `INTERNET` only, until the reminders step adds `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`.
- `PRIVACY.md` and the fastlane text are rewritten for zerohour (no collection, `allowBackup=false`, sync off by default to a user-chosen server over https, password encrypted with the Keystore, notifications and alarms local only).
- A NEW signing key, `zerohour-upload`. Never reference tuxwire's.
- Leave out: `.idea/`, `.kotlin/`, `local.properties`, and tuxwire's committed `gradle-daemon-jvm.properties`.

## Layout

```
README.md  LICENSE (MIT)  PRIVACY.md  CLAUDE.md  ANDROID_PLAN.md  .gitignore
keystore.properties.example
build.gradle.kts  settings.gradle.kts  gradle.properties  gradlew(.bat)
gradle/libs.versions.toml  gradle/wrapper/
rust/{Cargo.toml,Cargo.lock,rust-toolchain.toml,zerohour-ffi/,uniffi-bindgen/}
scripts/build-rust.sh
app/{build.gradle.kts, src/main/{AndroidManifest.xml, kotlin/com/shanetalkstech/zerohour/..., res/...}}
third_party/fonts/{OFL.txt,README.md}
fastlane/metadata/android/en-US/{title,short_description,full_description}.txt, changelogs/1.txt
```

`CLAUDE.md` is modelled on tuxwire-android's, plus: the commenting rule, https only, no hostnames or machine paths, `-D warnings`, core pinned by `rev`.

## Dependencies (all free software, no Google Play Services)

AGP 9.4.1, Kotlin and the Compose plugin 2.2.10, Compose BOM 2026.02.01 (ui, foundation, tooling), androidx core-ktx 1.10.1, activity-compose 1.8.0, kotlinx-coroutines-android 1.10.2, JNA 5.17.0 (`@aar`), uniffi 0.32.2, zerohour-core (pinned). No WorkManager: reminders use the platform `AlarmManager` and `NotificationManager`. Swipes use Compose foundation's `AnchoredDraggable`, not Material 3. `keyring` is excluded (`default-features = false`); confirm with `cargo tree`. Ask before adding anything else, including `chrono` or `uuid` to the FFI crate (allowed if needed, but prefer core's own conversion helpers).

## Decisions

- Application ID `com.shanetalkstech.zerohour` (`.debug` on debug builds). Confirm it matches the pattern in `tuxwire-android/app/build.gradle*`. It is permanent after the first Play release.
- minSdk 26.
- The sync password lives in an AES-GCM key held in the Android Keystore, handled on the Kotlin side and passed into Rust. Exclude the database and the encrypted password from Android backup.
- https only. No plain http.
- Touch: one tap opens edit. Swipe right completes. Swipe left asks to confirm the delete in a TUI-style box. The checkbox is display-only.
- Demo data comes from core (`core/src/demo.rs`), is used only in debug builds, stays in memory, and never syncs.
- Exact alarms use `SCHEDULE_EXACT_ALARM` with an inexact fallback. Do NOT use `USE_EXACT_ALARM` (Google restricts it to alarm and calendar apps).
- Reminders are the LAST feature step. The reminder planner and migration 4 get their own core branch in `~/Projects/zerohour` first: back up the real database (`~/.local/share/zerohour/zerohour.db`) to `~/zerohour-backups/` first and test only on copies.
- Sync testing uses only the `sandbox` database (user `sandbox`). Never the real `zerohour` database. Never put a server URL or credentials in code, tests, docs or git: they are entered at runtime.
- Build with Android Studio's bundled JDK through `JAVA_HOME` set in the shell only (`/opt/android-studio/jbr`). Never commit machine paths, SDK paths or keys. Never commit `keystore.properties`, `*.jks` or `local.properties`.

## Steps

Step 1 is committed on `master` this one time. Every later step is a feature branch off `master` with small commits.

1. Scaffolding: README, LICENSE, `.gitignore` (including `rust/.cargo/config.toml`), version catalog, Gradle wrapper, `keystore.properties.example`, `PRIVACY.md`, `CLAUDE.md`, this file. No code.
2. Rust workspace: `zerohour-ffi` with `core_version()` and the theme list. Tests, clippy, fmt. `Cargo.lock` committed. `cargo tree` check that `keyring` is absent.
3. Skeleton app: Gradle plus `build-rust.sh`, one Compose screen showing the core version in a theme color, `.debug` install. **STOP: the owner tests on the emulator.**
4. FFI read side: open the database in app storage, `view::` rows and labels and slots, the demo database for debug builds.
5. Read-only main screen: TUI-style panels for Tasks and Events, themes, the bundled font. **STOP: the owner tests.**
6. Add/edit form on core's `form.rs`, with the calendar and weekday picker.
7. Complete and delete (swipes).
8. Stats screen.
9. Sync settings: the Keystore password store, https-only checks, test connection.
10. Manual "sync now", off the main thread. **STOP: the owner tests on a real phone against `sandbox` only.**
11. Reminders (needs the core branch first), then alarms, notifications and rescheduling after reboot.
12. Release prep: fastlane text, screenshots, signing with the new key, F-Droid notes. Check the APK for any server URL, credentials or test values.

## Working rules

- Inspect and report first for anything non-trivial, then stop and wait. Never guess at a plan: if this file doesn't say, ask.
- Commenting rule (Kotlin and Rust): a doc comment on every file, class, struct, enum and function, and inline comments on non-obvious blocks, explaining what and why. Prefer clear, boring code over clever code.
- After every Rust commit: tests, `cargo clippy --all-targets -- -D warnings`, and `cargo fmt --check` must pass.
- Use only named tmux sessions you create, and kill only those by name. Never `tmux kill-server`.
- Do NOT push, merge, or delete branches or tags. The owner merges after testing.
- No secrets, hostnames, private URLs or machine paths in the repo.

## Status

- Core refactor (`view.rs`, `form.rs`) and the demo seeder are merged and pushed in `~/Projects/zerohour`; the pinned hash above is current.
- Steps 1 to 3 are done, merged into `master` and pushed. The skeleton app was tested by the owner on the emulator. Core is pinned at `87255bf`.
- Steps 4 and 5 are merged into `master` and tested by the owner. Decisions made with the owner: `chrono` is a direct dependency of `zerohour-ffi` (core's own version, no new crate), for `chrono::Local` and parsing "now"; the font comes from the Nerd Fonts v3.5.1 release; the icon mode defaults to Nerd when nothing is saved.
- Steps 6 and 7 are built on `feature/edit-forms` (not merged). No core pin change was needed: core `87255bf` already has `form.rs`, `calendar.rs`, `complete_task` and `delete_item`. FFI: an `ItemForm` object holding core's `FormInput` (Kotlin draws its `FormView`), `Database::new_form/edit_form/save_form/set_week_start/complete_task/delete_item`. "Now" crosses the bridge with its UTC offset (`2026-10-22T14:30:00+02:00`) so Rust gets both wall-clock time and the UTC moment without reading the clock. Touch decisions made with the owner: the form is a full-screen box; choice fields cycle with tappable `‹ ›`; swiping an event right shows the TUI's "Events count down on their own" message. The debug build still uses the in-memory demo database.
- Follow-ups on `feature/edit-forms` after the owner's first look: the open form survives turning the phone (`configChanges` in the manifest); "+ add", the calendar arrows and the form's `‹ ›` arrows get tap areas of at least 48dp without moving their text (`LargeTapArea`); the banner letters are painted on a Canvas so they have no seams, with a blank row above. The main screen has NO outer frame (unlike the TUI, to give the width to the content): the app name and date are a plain header row, and the bottom row holds the status message on the left and "+ add" on the right. When there is no status message, that row shows a gesture hint ("→ done  ← delete  tap edit") in the TUI's key-hint style (keys accent and bold, words muted).
- Next: the owner tests steps 6 and 7 on the emulator. Then step 8.
