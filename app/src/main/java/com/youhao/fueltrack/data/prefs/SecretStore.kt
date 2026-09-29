package com.youhao.fueltrack.data.prefs

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Port of `src/services/secure-secret.ts` (the Capacitor `SecureSession` plugin), which stored the
 * optional "remember my sync passphrase" value in the platform keychain.
 *
 * Two secrets can be remembered here, and both are opt-in:
 *
 * - the sync passphrase, which the legacy app could already remember;
 * - the WebDAV password, which the legacy app deliberately never persisted. It is what the
 *   password switch exists for: without it a background sync started after the process died has
 *   nothing to authenticate with.
 *
 * Anything the user did not ask to remember stays in [SessionSecrets] for the length of the
 * process, exactly as the legacy app kept it in `sessionStorage` — losing it on app restart is
 * the intended behaviour.
 */
interface SecretStore {
    fun loadPassphrase(): String?

    fun savePassphrase(value: String?)

    fun loadPassword(): String?

    fun savePassword(value: String?)
}

/**
 * Android Keystore-backed implementation. Falling back to "nothing is remembered" is deliberate:
 * if the Keystore is unavailable (locked device, corrupted key) the app must still be able to
 * sync, it just has to ask for the secret again.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val preferences = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { Log.w(TAG, "Keystore unavailable; secrets will not be remembered.", it) }
        .getOrNull()

    override fun loadPassphrase(): String? = preferences?.getString(KEY_PASSPHRASE, null)

    override fun savePassphrase(value: String?) = write(KEY_PASSPHRASE, value)

    override fun loadPassword(): String? = preferences?.getString(KEY_PASSWORD, null)

    override fun savePassword(value: String?) = write(KEY_PASSWORD, value)

    private fun write(key: String, value: String?) {
        val editor = preferences?.edit() ?: return
        if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val TAG = "KeystoreSecretStore"
        const val FILE_NAME = "fuel-track-secrets"
        const val KEY_PASSPHRASE = "fuel-track-sync-passphrase"
        const val KEY_PASSWORD = "fuel-track-webdav-password"
    }
}

/** Test double. */
class InMemorySecretStore(
    initialPassphrase: String? = null,
    initialPassword: String? = null,
) : SecretStore {
    private var passphrase: String? = initialPassphrase
    private var password: String? = initialPassword

    override fun loadPassphrase(): String? = passphrase

    override fun savePassphrase(value: String?) {
        passphrase = value
    }

    override fun loadPassword(): String? = password

    override fun savePassword(value: String?) {
        password = value
    }
}
