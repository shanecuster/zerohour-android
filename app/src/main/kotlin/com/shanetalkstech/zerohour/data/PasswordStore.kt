package com.shanetalkstech.zerohour.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What [PasswordStore.load] found.
 *
 * A `sealed interface` is a closed set of cases, so a `when` over it must
 * handle each one.
 */
sealed interface SavedPassword {
    /** No password is saved for this server, database and user. */
    data object None : SavedPassword

    /** The saved [password], decrypted. Never shown, never logged. */
    class Found(val password: String) : SavedPassword {
        /** Overridden so the password can never end up in a log by accident. */
        override fun toString(): String = "SavedPassword.Found(****)"
    }

    /**
     * A password was saved but cannot be decrypted any more (for example the
     * phone's security settings were reset, which deletes Keystore keys). The
     * unreadable copy has been removed; the user has to enter it again.
     */
    data object Unreadable : SavedPassword
}

/**
 * Keeps the one sync password, encrypted, on this phone.
 *
 * How it is protected:
 *
 * - **The key** is an AES-256 key made inside the Android Keystore. The app
 *   can ask the Keystore to encrypt and decrypt with it, but can never read
 *   the key itself, and it never leaves this phone (it is not backed up and
 *   not transferred to a new phone).
 * - **The encryption** is AES-GCM, which also detects any change to the
 *   stored bytes. A fresh random starting value (IV) is used every time, as
 *   GCM requires. The account name is fed in as "associated data": it is not
 *   secret, but it is checked on decrypt, so a password cannot be moved to
 *   another account by editing the file.
 * - **The file** is in `noBackupFilesDir`, which Android never backs up or
 *   transfers, inside the app's private storage.
 *
 * The password is stored together with core's account name, "user @
 * server/database" (`syncAccount`). [load] only returns it while that name
 * still matches, so after the server, database or user changes, the old
 * password is never sent to the new server.
 *
 * Nothing here logs, prints or throws the password. Every failure to read it
 * becomes [SavedPassword.Unreadable] or [SavedPassword.None], never a crash.
 * Call these functions on a background thread (they touch the disk and the
 * Keystore).
 */
object PasswordStore {

    /** The Keystore provider's name. */
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** This app's key name inside the Keystore. */
    private const val KEY_ALIAS = "zerohour-sync-password"

    /** AES in GCM mode (GCM needs no padding). */
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The length of GCM's tamper check, in bits (the standard 128). */
    private const val TAG_BITS = 128

    /** The file holding the encrypted password. */
    private const val FILE_NAME = "sync-password"

    /**
     * Encrypts and saves [password] for [account], replacing any password
     * saved before. Throws only if the Keystore itself fails.
     */
    fun save(context: Context, account: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // Three lines, each base64 so no value can contain a line break:
        // the account name, the IV, the encrypted password.
        val text = listOf(account.toByteArray(Charsets.UTF_8), cipher.iv, encrypted)
            .joinToString("\n") { Base64.encodeToString(it, Base64.NO_WRAP) }
        // Write a temporary file, then rename it over the old one: a rename
        // is all-or-nothing, so a crash never leaves half a file.
        val target = file(context)
        val temporary = File(target.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("could not save the password file")
        }
    }

    /**
     * The password saved for [account], if any. A password saved for a
     * different account counts as [SavedPassword.None] (and is kept, in case
     * the old details are typed back in). One that cannot be decrypted is
     * deleted and reported as [SavedPassword.Unreadable].
     */
    fun load(context: Context, account: String): SavedPassword {
        val file = file(context)
        if (!file.exists()) return SavedPassword.None
        // `runCatching` turns any exception into a failure value, so a
        // damaged file or a missing key can never crash the app.
        val parts = runCatching {
            file.readText().split("\n").map { Base64.decode(it, Base64.NO_WRAP) }
        }.getOrNull()
        if (parts == null || parts.size != 3) return unreadable(file)
        val (savedAccount, iv, encrypted) = parts
        if (String(savedAccount, Charsets.UTF_8) != account) return SavedPassword.None

        val key = existingKey() ?: return unreadable(file)
        val password = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(savedAccount)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull() ?: return unreadable(file)
        return SavedPassword.Found(password)
    }

    /** Deletes the saved password (if there is one). */
    fun clear(context: Context) {
        file(context).delete()
    }

    /** Deletes a file that cannot be read and says so. */
    private fun unreadable(file: File): SavedPassword {
        file.delete()
        return SavedPassword.Unreadable
    }

    /** The password file in `noBackupFilesDir`. */
    private fun file(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    /**
     * The app's key if it exists, or null (never saved yet, or deleted by
     * the system). Any Keystore failure counts as "no key".
     */
    private fun existingKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        store.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    /**
     * Makes the app's key: AES-256, for encrypting and decrypting only, GCM
     * with no padding. The Keystore insists on a fresh random IV for every
     * encryption (its default), which is what GCM needs.
     */
    private fun newKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
