package com.youhao.fueltrack.data.prefs

import com.youhao.fueltrack.domain.error.LastError
import com.youhao.fueltrack.domain.model.SyncMetadata
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.WebDavConfig
import com.youhao.fueltrack.domain.sync.SyncBase
import kotlinx.coroutines.flow.StateFlow

/**
 * The credentials the legacy app deliberately kept out of persistent storage. They live in
 * `sessionStorage` there and for the lifetime of the process here — unless the user asked for one
 * of them to be remembered, in which case [SecretStore] also holds it.
 */
data class SessionSecrets(
    val webDavPassword: String = "",
    val encryptionPassphrase: String = "",
) {
    companion object {
        val EMPTY = SessionSecrets()
    }
}

/**
 * Port of the persistence side of `src/services/database.ts`, plus the small stores that the legacy
 * app scattered across `localStorage`: device identity, the per-target sync base, the device
 * metadata cache and the last fatal error.
 *
 * Kept behind an interface so the sync engine can be unit-tested on the JVM without Android.
 */
interface SettingsRepository {

    /** Merged view: persisted, non-secret fields plus the in-session secrets. */
    suspend fun config(): WebDavConfig

    /**
     * Persists only the non-secret fields; the password and passphrase go to [SessionSecrets].
     * Each additionally goes to the [SecretStore] when the caller asked to remember it — the
     * passphrase so an encrypted remote can be read, the password so a background sync can
     * authenticate after the process has been killed.
     */
    suspend fun saveConfig(config: WebDavConfig)

    suspend fun deviceId(): String

    /** Mirrors `readBase` in `sync-conflicts.ts`: any decoding failure yields an empty base. */
    suspend fun syncBase(target: String): SyncBase

    /** Mirrors `saveSyncBase`: hashes every entity in [payload] and stores the result. */
    suspend fun saveSyncBase(target: String, payload: SyncPayloadV1)

    suspend fun metadataCache(target: String): SyncMetadata?

    suspend fun saveMetadataCache(target: String, metadata: SyncMetadata)

    suspend fun lastError(): LastError?

    suspend fun recordLastError(error: Throwable)

    suspend fun clearLastError()

    val secrets: StateFlow<SessionSecrets>

    suspend fun rememberedPassphrase(): String?
}

/** Default WebDAV file name, matching the legacy `getConfig()` default. */
const val DEFAULT_SYNC_FILE_NAME: String = "fuel-track.json"

internal fun WebDavConfig.withDefaults(): WebDavConfig = copy(
    fileName = fileName.ifBlank { DEFAULT_SYNC_FILE_NAME },
    encryptionEnabled = encryptionEnabled ?: false,
    rememberEncryptionPassphrase = rememberEncryptionPassphrase ?: false,
    rememberPassword = rememberPassword ?: false,
)

/**
 * The password a sync should authenticate with: one typed this session always wins, otherwise the
 * keystore copy is used — but only while the user still wants it remembered. Reading through the
 * flag (rather than just checking whether something is stored) is what makes switching the option
 * off take effect immediately, instead of at the next save.
 */
internal fun resolveWebDavPassword(
    sessionPassword: String,
    remember: Boolean,
    storedPassword: String?,
): String = sessionPassword.ifEmpty { if (remember) storedPassword.orEmpty() else "" }

/**
 * Same rule for the encryption passphrase. Note there is no flag here: the legacy client read
 * whatever the keychain held, and the value is only ever written when the user opted in.
 */
internal fun resolvePassphrase(
    sessionPassphrase: String,
    storedPassphrase: String?,
): String = sessionPassphrase.ifEmpty { storedPassphrase.orEmpty() }
