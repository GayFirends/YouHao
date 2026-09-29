package com.youhao.fueltrack.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.LastError
import com.youhao.fueltrack.domain.error.toAppException
import com.youhao.fueltrack.domain.model.SyncMetadata
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.WebDavConfig
import com.youhao.fueltrack.domain.sync.SyncBase
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.sync.buildSyncBase
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fuel-track-settings",
)

/**
 * DataStore-backed [SettingsRepository].
 *
 * The legacy app stored its settings as one JSON blob in `localStorage`; preferences are used here
 * instead so that a partial write cannot drop unrelated keys. Secret material never reaches
 * DataStore — see [SecretStore] and [SessionSecrets] for the reasoning.
 */
class DataStoreSettingsRepository(
    context: Context,
    private val secretStore: SecretStore = KeystoreSecretStore(context.applicationContext),
) : SettingsRepository {

    private val appContext = context.applicationContext
    private val store = appContext.settingsDataStore
    private val _secrets = MutableStateFlow(SessionSecrets())

    override val secrets: StateFlow<SessionSecrets> = _secrets.asStateFlow()

    override suspend fun config(): WebDavConfig {
        val preferences = store.data.first()
        val session = _secrets.value
        return WebDavConfig(
            url = preferences[Keys.URL].orEmpty(),
            username = preferences[Keys.USERNAME].orEmpty(),
            // Same reasoning as the passphrase below: a remembered password is the only way the
            // periodic worker can authenticate after the process died. A password typed this
            // session still wins, and switching the option off clears the stored copy.
            password = resolveWebDavPassword(
                sessionPassword = session.webDavPassword,
                remember = preferences[Keys.REMEMBER_PASSWORD] == true,
                storedPassword = secretStore.loadPassword(),
            ),
            fileName = preferences[Keys.FILE_NAME] ?: DEFAULT_SYNC_FILE_NAME,
            encryptionEnabled = preferences[Keys.ENCRYPTION_ENABLED] ?: false,
            // A remembered passphrase has to survive process death, otherwise "remember" would only
            // mean "keep it until Android kills the app" — and a background sync would never be able
            // to decrypt the remote file. A passphrase typed this session still wins.
            encryptionPassphrase = resolvePassphrase(
                sessionPassphrase = session.encryptionPassphrase,
                storedPassphrase = secretStore.loadPassphrase(),
            ),
            rememberEncryptionPassphrase = preferences[Keys.REMEMBER_PASSPHRASE] ?: false,
            rememberPassword = preferences[Keys.REMEMBER_PASSWORD] ?: false,
        )
    }

    override suspend fun saveConfig(config: WebDavConfig) {
        val normalized = config.withDefaults()
        _secrets.value = SessionSecrets(
            webDavPassword = config.password,
            encryptionPassphrase = config.encryptionPassphrase.orEmpty(),
        )
        store.edit { preferences ->
            preferences[Keys.URL] = normalized.url
            preferences[Keys.USERNAME] = normalized.username
            preferences[Keys.FILE_NAME] = normalized.fileName
            preferences[Keys.ENCRYPTION_ENABLED] = normalized.encryptionEnabled ?: false
            preferences[Keys.REMEMBER_PASSPHRASE] = normalized.rememberEncryptionPassphrase ?: false
            preferences[Keys.REMEMBER_PASSWORD] = normalized.rememberPassword ?: false
        }
        secretStore.savePassphrase(
            if (normalized.rememberEncryptionPassphrase == true) normalized.encryptionPassphrase else null,
        )
        secretStore.savePassword(if (normalized.rememberPassword == true) normalized.password else null)
    }

    override suspend fun deviceId(): String {
        store.data.first()[Keys.DEVICE_ID]?.let { return it }
        val created = UUID.randomUUID().toString()
        // `edit` is atomic, and the `?:` keeps a concurrent writer's value if there was one.
        store.edit { preferences -> preferences[Keys.DEVICE_ID] = preferences[Keys.DEVICE_ID] ?: created }
        return store.data.first()[Keys.DEVICE_ID] ?: created
    }

    override suspend fun syncBase(target: String): SyncBase {
        val raw = store.data.first()[Keys.syncBase(target)] ?: return SyncBase.EMPTY
        return runCatching { SyncJson.decodeFromString<SyncBase>(raw) }.getOrElse { SyncBase.EMPTY }
    }

    override suspend fun saveSyncBase(target: String, payload: SyncPayloadV1) {
        val encoded = SyncJson.encodeToString(buildSyncBase(payload))
        store.edit { preferences -> preferences[Keys.syncBase(target)] = encoded }
    }

    override suspend fun metadataCache(target: String): SyncMetadata? {
        val raw = store.data.first()[Keys.metadataCache(target)] ?: return null
        return runCatching { SyncJson.decodeFromString<SyncMetadata>(raw) }.getOrNull()
    }

    override suspend fun saveMetadataCache(target: String, metadata: SyncMetadata) {
        val encoded = SyncJson.encodeToString(metadata)
        store.edit { preferences -> preferences[Keys.metadataCache(target)] = encoded }
    }

    override suspend fun lastError(): LastError? {
        val raw = store.data.first()[Keys.LAST_ERROR] ?: return null
        val wire = runCatching { SyncJson.decodeFromString<LastErrorWire>(raw) }.getOrNull() ?: return null
        val code = AppErrorCode.entries.firstOrNull { it.wireName == wire.code } ?: AppErrorCode.UNKNOWN
        return LastError(code, wire.occurredAt)
    }

    override suspend fun recordLastError(error: Throwable) {
        val normalized = error.toAppException()
        val encoded = SyncJson.encodeToString(
            LastErrorWire(code = normalized.code.wireName, occurredAt = Timestamps.nowIso()),
        )
        store.edit { preferences -> preferences[Keys.LAST_ERROR] = encoded }
    }

    override suspend fun clearLastError() {
        store.edit { preferences -> preferences.remove(Keys.LAST_ERROR) }
    }

    override suspend fun rememberedPassphrase(): String? = secretStore.loadPassphrase()

    /** Mirrors `fuel-track-last-error`: `{ code, occurredAt }`, never the message. */
    @Serializable
    private data class LastErrorWire(val code: String, val occurredAt: String)

    private object Keys {
        val URL = stringPreferencesKey("webdav.url")
        val USERNAME = stringPreferencesKey("webdav.username")
        val FILE_NAME = stringPreferencesKey("webdav.fileName")
        val ENCRYPTION_ENABLED = booleanPreferencesKey("webdav.encryptionEnabled")
        val REMEMBER_PASSPHRASE = booleanPreferencesKey("webdav.rememberEncryptionPassphrase")
        val REMEMBER_PASSWORD = booleanPreferencesKey("webdav.rememberPassword")
        val DEVICE_ID = stringPreferencesKey("device.id")
        val LAST_ERROR = stringPreferencesKey("diagnostics.lastError")

        // Per-target, like `fuel-track-sync-base-v1:<target>` and
        // `fuel-track-sync-metadata-v1:<target>`. DataStore keys accept the URL verbatim, so the
        // legacy `encodeURIComponent` step is unnecessary here.
        fun syncBase(target: String) = stringPreferencesKey("sync.base.$target")

        fun metadataCache(target: String) = stringPreferencesKey("sync.metadata.$target")
    }
}
