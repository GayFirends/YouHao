package com.youhao.fueltrack.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.data.backup.BackupFiles
import com.youhao.fueltrack.data.diagnostics.Diagnostics
import com.youhao.fueltrack.data.prefs.DEFAULT_SYNC_FILE_NAME
import com.youhao.fueltrack.data.prefs.SettingsRepository
import com.youhao.fueltrack.data.sync.SyncEngine
import com.youhao.fueltrack.domain.backup.parseBackup
import com.youhao.fueltrack.domain.backup.recordsToCsv
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.LastError
import com.youhao.fueltrack.domain.error.userErrorMessage
import com.youhao.fueltrack.domain.model.SchemaInfo
import com.youhao.fueltrack.domain.model.WebDavConfig
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Everything the settings screen draws.
 *
 * [config] carries the merged view of the persisted settings (including the in-session secrets that
 * [SettingsRepository.config] folds in), while [editingPassword] mirrors the text field so a
 * half-typed password is never written back into [config] until the user saves or runs an action.
 *
 * [statusIsError] picks the banner palette for [statusMessage]. It is a separate flag rather than a
 * derivation from [lastError]: a failed test-connection or a validation error is reported inline and
 * deliberately never recorded as the persisted "last error".
 */
data class SettingsUiState(
    val config: WebDavConfig = WebDavConfig(
        url = "",
        username = "",
        password = "",
        fileName = DEFAULT_SYNC_FILE_NAME,
        encryptionEnabled = false,
        encryptionPassphrase = "",
        rememberEncryptionPassphrase = false,
        rememberPassword = false,
    ),
    val editingPassword: String = "",
    val deviceId: String = "",
    val schemaInfo: SchemaInfo? = null,
    val lastError: LastError? = null,
    val statusMessage: String? = null,
    val busy: Boolean = false,
    val conflictCount: Int = 0,
    val loaded: Boolean = false,
    val statusIsError: Boolean = false,
)

/**
 * Backs the settings screen: loads the WebDAV configuration, persists edits and drives the two
 * long-running actions (测试连接 / 立即同步) on [viewModelScope] so a rotation does not cancel them
 * halfway through a write.
 *
 * Both actions persist the form before touching the network, matching the legacy client, which
 * always wrote the settings before calling `testWebDav`/`syncWebDav`.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val store: FuelTrackStore,
    private val files: BackupFiles,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    // -----------------------------------------------------------------------
    // Form editing
    // -----------------------------------------------------------------------

    fun onUrlChange(value: String) = edit { it.copy(config = it.config.copy(url = value)) }

    fun onUsernameChange(value: String) = edit { it.copy(config = it.config.copy(username = value)) }

    fun onPasswordChange(value: String) = edit { it.copy(editingPassword = value) }

    fun onFileNameChange(value: String) = edit { it.copy(config = it.config.copy(fileName = value)) }

    fun onEncryptionEnabledChange(enabled: Boolean) =
        edit { it.copy(config = it.config.copy(encryptionEnabled = enabled)) }

    fun onPassphraseChange(value: String) =
        edit { it.copy(config = it.config.copy(encryptionPassphrase = value)) }

    fun onRememberPassphraseChange(remember: Boolean) =
        edit { it.copy(config = it.config.copy(rememberEncryptionPassphrase = remember)) }

    /**
     * Turns on background sync. Opting back out also drops the stored copy —
     * [SettingsRepository.saveConfig] saves null when the flag is false.
     */
    fun onRememberPasswordChange(remember: Boolean) =
        edit { it.copy(config = it.config.copy(rememberPassword = remember)) }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /** Persists the form; the secrets go to the session/keystore stores, never to the settings file. */
    fun saveSettings() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = null) }
            val config = persist() ?: return@launch
            _state.update {
                it.copy(busy = false, config = config, statusMessage = "设置已保存", statusIsError = false)
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = null) }
            val config = persist() ?: return@launch
            try {
                syncEngine.testConnection(config)
                _state.update {
                    it.copy(busy = false, config = config, statusMessage = "连接正常", statusIsError = false)
                }
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, config = config) }
                fail(error)
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = null) }
            val config = persist() ?: return@launch
            try {
                syncEngine.sync(config)
                settings.clearLastError()
                // Suspend reads must happen before `update`, which takes a plain lambda.
                val conflictCount = store.conflictCount()
                _state.update {
                    it.copy(
                        busy = false,
                        config = config,
                        statusMessage = "同步完成",
                        statusIsError = false,
                        lastError = null,
                        conflictCount = conflictCount,
                    )
                }
            } catch (error: Throwable) {
                // Same contract as AppContainer.syncNow(): a failed sync leaves the persisted marker
                // so the next launch still explains what went wrong.
                settings.recordLastError(error)
                val lastError = settings.lastError()
                val conflictCount = store.conflictCount()
                _state.update {
                    it.copy(
                        busy = false,
                        config = config,
                        statusMessage = userErrorMessage(error, AppErrorCode.UNKNOWN),
                        statusIsError = true,
                        lastError = lastError,
                        conflictCount = conflictCount,
                    )
                }
            }
        }
    }

    fun clearLastError() {
        viewModelScope.launch {
            settings.clearLastError()
            _state.update { it.copy(lastError = null) }
        }
    }

    // -----------------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------------

    /**
     * The document picker lives in the composable, so every file action takes the [uri] the user
     * chose. Writes go through the Storage Access Framework, which means no storage permission and
     * an export the user can actually find again.
     */
    fun exportCsv(uri: Uri) = writeTo(uri, "CSV 已导出") {
        recordsToCsv(store.records(), store.vehicles())
    }

    fun exportBackup(uri: Uri) = writeTo(uri, "备份已导出") {
        SyncJson.encodeToString(store.exportData())
    }

    fun exportDiagnostics(uri: Uri) = writeTo(uri, "诊断报告已导出") {
        val report = Diagnostics.create(
            generatedAt = Timestamps.nowIso(),
            database = store.getSchemaInfo(),
            deviceId = settings.deviceId(),
            lastError = settings.lastError(),
        )
        Diagnostics.render(report)
    }

    /**
     * Import merges rather than replaces: [FuelTrackStore.mergeData] applies the same
     * last-writer-wins rule the sync engine uses, so restoring a stale backup cannot silently roll
     * back newer records. Deletions come along as tombstones.
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = null) }
            try {
                val payload = parseBackup(files.readText(uri))
                store.mergeData(payload)
                val conflictCount = store.conflictCount()
                _state.update {
                    it.copy(
                        busy = false,
                        statusMessage = "已导入 ${payload.vehicles.size} 辆车、${payload.records.size} 条记录",
                        statusIsError = false,
                        conflictCount = conflictCount,
                    )
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun writeTo(uri: Uri, successMessage: String, build: suspend () -> String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = null) }
            try {
                files.writeText(uri, build())
                _state.update {
                    it.copy(busy = false, statusMessage = successMessage, statusIsError = false)
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun load() {
        viewModelScope.launch {
            try {
                val config = settings.config()
                val deviceId = settings.deviceId()
                val schemaInfo = store.getSchemaInfo()
                val lastError = settings.lastError()
                val conflictCount = store.conflictCount()
                _state.update {
                    it.copy(
                        config = config,
                        // The merged config already carries the session password; the field starts there.
                        editingPassword = config.password,
                        deviceId = deviceId,
                        schemaInfo = schemaInfo,
                        lastError = lastError,
                        conflictCount = conflictCount,
                        loaded = true,
                    )
                }
            } catch (error: Throwable) {
                _state.update { it.copy(loaded = true) }
                fail(error)
            }
        }
    }

    /** The form as a config, with the password field folded back in just before it is persisted. */
    private fun currentConfig(): WebDavConfig {
        val current = _state.value
        return current.config.copy(password = current.editingPassword)
    }

    /** Saves the form and returns the config to send to the engine, or null when saving failed. */
    private suspend fun persist(): WebDavConfig? {
        val config = currentConfig()
        return try {
            settings.saveConfig(config)
            config
        } catch (error: Throwable) {
            fail(error)
            null
        }
    }

    private fun edit(transform: (SettingsUiState) -> SettingsUiState) {
        _state.update { transform(it).copy(statusMessage = null, statusIsError = false) }
    }

    private fun fail(error: Throwable) {
        _state.update {
            it.copy(
                busy = false,
                statusMessage = userErrorMessage(error, AppErrorCode.UNKNOWN),
                statusIsError = true,
            )
        }
    }
}
