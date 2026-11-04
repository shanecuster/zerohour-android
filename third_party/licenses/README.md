# Licence texts shown in the app

The app's "Open source licenses" page (Settings › About › Open source
licenses) lists what is built into the app and opens each license's full text
offline from `app/src/main/res/raw/license_*.txt`. Every text there is copied
unchanged from the component it belongs to; only a short heading line naming
the component (and, where a component offers a choice of licenses, which one
zerohour uses) is added in front.

## Where each file comes from

| File | Copied from |
|---|---|
| `license_mit_zerohour.txt` | this repository's `LICENSE` (the app, `zerohour-core` and the app icon) |
| `license_mit_others.txt` | the `LICENSE` / `LICENSE-MIT` files of the crates bytes 1.12.1, rusqlite 0.40.2, libsqlite3-sys 0.38.2, cargo_metadata 0.23.1, zmij 1.0.23 and memchr 2.8.3; and libffi's `LICENSE` from JNA 5.17.0's source (`native/libffi/LICENSE` at the `5.17.0` tag), since JNA's native library contains libffi |
| `license_apache_2_0.txt` | the SPDX text of the Apache License 2.0 |
| `license_isc.txt` | rustls 0.23.46 `LICENSE-ISC`, rustls-webpki 0.103.16 `LICENSE`, untrusted 0.9.0 `LICENSE.txt` |
| `license_ring.txt` | ring 0.17.14 `LICENSE`, `LICENSE-other-bits`, `LICENSE-BoringSSL` |
| `license_bsd_3_clause.txt` | subtle 2.6.1 `LICENSE`, sha1_smol 1.0.1 `LICENSE` |
| `license_mpl_2_0.txt` | option-ext 0.2.0 `LICENSE.txt` (the Mozilla Public License 2.0, also UniFFI's license) |
| `license_cdla_permissive_2_0.txt` | webpki-roots 1.0.10 `LICENSE` |
| `license_zlib.txt` | foldhash 0.2.0 `LICENSE` |
| `license_unicode_3_0.txt` | unicode-ident 1.0.27 `LICENSE-UNICODE` |
| `license_ofl_1_1.txt` | `third_party/fonts/OFL.txt` (from the Nerd Fonts v3.5.1 release) |
| `license_cc_by_4_0.txt` | the Font Awesome attribution, then the SPDX text of CC BY 4.0 |
| `license_sqlite.txt` | the public-domain blessing at the top of SQLite 3.53.2's `sqlite3.c` (bundled in libsqlite3-sys 0.38.2) |

The Rust crate files are in Cargo's download folder after a build, under
`registry/src/<index>/<crate>-<version>/`.

## Keeping the list right

Only what ships in the app is listed: the Rust crates compiled into
`libzerohour_ffi.so`, which this command prints (build-only crates such as
proc-macros are left out):

    cargo tree -p zerohour-ffi -e normal,no-proc-macro --target aarch64-linux-android --prefix none

and the Android libraries in `./gradlew :app:dependencies --configuration
releaseRuntimeClasspath` (AndroidX, Kotlin, kotlinx.coroutines, Guava's
listenablefuture, JSpecify and JetBrains annotations, all Apache-2.0; JNA,
Apache-2.0 chosen from "Apache-2.0 OR LGPL-2.1-or-later").

When a dependency or core's pin changes, run both again and update the list in
`ui/LicensesPage.kt`, its names in `res/values/strings.xml`, and these files.
