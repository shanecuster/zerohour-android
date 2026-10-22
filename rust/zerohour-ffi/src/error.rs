//! The one error type the Kotlin side sees, and the conversions into it.
//!
//! Rust functions that can fail return `Result<T, E>`. Across the bridge,
//! UniFFI turns the `Err` case into a Kotlin exception (`FfiException`) that
//! the app catches with an ordinary `try`/`catch`.

use zerohour_core::db::DbError;

/// Everything that can go wrong in a call from Kotlin.
///
/// Three variants, because the app reacts to them differently:
///
/// * `Database` -- the SQLite file could not be opened, read or written. The
///   app shows the explanation to the user.
/// * `InvalidInput` -- Kotlin passed something this crate could not
///   understand (for example a badly formatted date). That is a bug in the
///   app, not something the user did, so it is mostly useful in logs.
/// * `Sync` -- the sync details are incomplete or not allowed, or the server
///   could not be reached or refused the login. The app shows the
///   explanation on the sync settings screen. It never contains the
///   password.
///
/// The field is called `detail`, not `message`, on purpose: Kotlin's built-in
/// exception class already has a property named `message`, and a generated
/// subclass declaring its own would clash with it.
#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    /// The database failed. `detail` is core's human-readable explanation.
    Database {
        /// The explanation to show or log.
        detail: String,
    },
    /// An argument from Kotlin was not in the expected form.
    InvalidInput {
        /// What was wrong with it.
        detail: String,
    },
    /// Sync settings or a connection to the sync server failed. `detail` says
    /// what to fix, in core's words where core has them.
    Sync {
        /// The explanation to show.
        detail: String,
    },
}

/// How an `FfiError` prints. Required for any error type: it is the text shown
/// when the error is displayed or logged.
impl std::fmt::Display for FfiError {
    /// Writes the variant's detail text, prefixed with what kind of error it is.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // `match` takes the value apart by variant, binding the field to `detail`.
        match self {
            FfiError::Database { detail } => write!(f, "database error: {detail}"),
            FfiError::InvalidInput { detail } => write!(f, "invalid input: {detail}"),
            FfiError::Sync { detail } => write!(f, "sync: {detail}"),
        }
    }
}

/// Marks `FfiError` as a proper Rust error type. All the required behaviour
/// already comes from `Debug` + `Display` above, so the body is empty.
impl std::error::Error for FfiError {}

/// Lets `?` convert core's database error into an `FfiError` automatically.
///
/// With this in place, a function returning `Result<_, FfiError>` can call a
/// core function that returns `zerohour_core::db::Result<_>` and simply write
/// `?` after it: on failure, `?` calls this `from` to do the conversion.
impl From<DbError> for FfiError {
    /// Keeps core's wording (it already names the file and the reason).
    fn from(error: DbError) -> FfiError {
        FfiError::Database {
            detail: error.to_string(),
        }
    }
}
