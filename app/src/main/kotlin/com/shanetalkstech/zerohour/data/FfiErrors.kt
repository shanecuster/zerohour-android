package com.shanetalkstech.zerohour.data

import uniffi.zerohour_ffi.FfiException

/**
 * The human-readable explanation inside an error from the Rust side.
 *
 * Every Rust call can fail with an `FfiException`, which has one subclass per
 * kind of error (see `rust/zerohour-ffi/src/error.rs`), each carrying a
 * `detail` text. This picks that text out whatever the kind, so screens can
 * show it in one line. The `when` lists every kind, so a new kind added in
 * Rust won't compile until it is handled here.
 */
fun FfiException.explanation(): String = when (this) {
    is FfiException.Database -> detail
    is FfiException.InvalidInput -> detail
    is FfiException.Sync -> detail
}
