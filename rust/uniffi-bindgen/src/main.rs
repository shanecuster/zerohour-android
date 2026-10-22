//! The `uniffi-bindgen` helper program.
//!
//! All of the real work happens inside the `uniffi` crate; this program only
//! hands the command-line arguments over to it. `scripts/build-rust.sh` runs it
//! like this to write the Kotlin bindings:
//!
//! ```text
//! uniffi-bindgen generate --library path/to/libzerohour_ffi.so \
//!     --language kotlin --out-dir path/to/output
//! ```

/// Program entry point. `uniffi::uniffi_bindgen_main()` reads the arguments the
/// program was started with (`generate`, `--library`, ...), does the job, and
/// exits with a success or error code, so there is nothing else to do here.
fn main() {
    uniffi::uniffi_bindgen_main()
}
