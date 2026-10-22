# CLAUDE.md — zerohour-android

Native Android app (Kotlin + Jetpack Compose) for zerohour, a to-do list and
countdown app. The UI imitates the terminal app: a bundled monospace Nerd Font,
box-drawing borders drawn on a Compose Canvas, the same theme palettes, icon
table and banner as the TUI (all from core), and block-character charts. All
logic lives in the shared Rust crate `zerohour-core` (from the `zerohour` repo),
reached through UniFFI.

`ANDROID_PLAN.md` is the approved, source-of-truth plan. Read it first; continue
from its "Status" section. Do not repeat the inspect-and-report step, and never
guess at anything the plan does not say — ask instead.

## Layout

- `rust/` — Cargo workspace: `zerohour-ffi` (the UniFFI glue over core) and
  `uniffi-bindgen` (helper that writes the Kotlin bindings).
- `app/` — the Android app (Gradle module).
- `scripts/build-rust.sh` — cross-compiles Rust with cargo-ndk and generates
  the Kotlin bindings. Gradle runs it before every build.
- Generated and git-ignored: `app/src/main/jniLibs/`, `app/src/main/kotlin/uniffi/`.

## Rules

- Never merge, push, or delete branches or tags. Shane merges and pushes after
  testing. Feature branches off master; commit in small single-purpose steps.
  (Step 1, the scaffolding, is the one exception: it is committed on master.)
- Inspect and report first, then wait for answers before building.
- No new dependencies (Rust or Gradle) without asking first. That includes
  `chrono` or `uuid` in the FFI crate: prefer core's own conversion helpers.
- `zerohour-core` is pinned by git `rev` in `rust/Cargo.toml`, built with
  `default-features = false, features = ["sync"]` (sync, but not the OS
  keyring). Bump the `rev` deliberately, in its own commit, only after the new
  core work is merged and pushed in the `zerohour` repo first.
- No tokio. zerohour's sync is blocking `ureq`; Kotlin calls Rust on
  `Dispatchers.IO`.
- `https://` only for the sync server. No plain `http://`.
- No hostnames, credentials, private URLs, server addresses, test values, SDK
  paths or machine paths anywhere in the repo (code, tests, docs or git). Sync
  details are entered at runtime.
- The sync password lives in an AES-GCM key held in the Android Keystore,
  handled on the Kotlin side and passed into Rust. The database and the
  encrypted password are excluded from Android backup (`allowBackup=false`).
- UI code never hard-codes a colour: use the theme's colour slots.
- Demo data comes from core (`core/src/demo.rs`), is used only in debug builds,
  stays in memory, and never syncs.
- Reminders are the LAST feature step and need a core branch first. Before
  running anything that migrates a database, back up the real database
  (`~/.local/share/zerohour/zerohour.db`) to `~/zerohour-backups/` and test
  only on copies. Sync testing uses only the `sandbox` database, never the real
  one.
- A NEW signing key, `zerohour-upload`. Never reference another app's key.
- Never run `tmux kill-server`; use named sessions you create and kill only
  those by name.

## Documentation standard

Shane is learning through this code. Every file, class, struct, enum, function
and property gets a doc comment saying what it is for and why. Non-obvious
blocks get `//` comments explaining what and why, written for someone new to
Kotlin/Rust/Compose. Prefer clear, boring code over clever code.

## Checks

In `rust/`: `cargo test`, `cargo clippy --all-targets -- -D warnings`,
`cargo fmt --check` must all pass before each Rust commit.

At the end of each piece of work: a short report (what changed, how to test,
anything surprising), and flag anything worth saving to notes as
title / command / when to use it.
