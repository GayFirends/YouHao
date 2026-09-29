package com.youhao.fueltrack

import android.content.Context
import android.util.Log
import com.youhao.fueltrack.data.FuelTrackStore
import com.youhao.fueltrack.data.backup.BackupFiles
import com.youhao.fueltrack.data.local.FuelTrackDatabase
import com.youhao.fueltrack.data.prefs.DataStoreSettingsRepository
import com.youhao.fueltrack.data.prefs.KeystoreSecretStore
import com.youhao.fueltrack.data.prefs.SecretStore
import com.youhao.fueltrack.data.sync.SyncEngine
import com.youhao.fueltrack.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Hand-rolled dependency container. The object graph is small and linear — one database, one store,
 * one settings repository, one sync engine — so a DI framework would cost more than it saves.
 *
 * Everything is lazy so that merely starting the process does not open the database or touch the
 * keystore.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: FuelTrackDatabase by lazy { FuelTrackDatabase.create(appContext) }

    val store: FuelTrackStore by lazy { FuelTrackStore(database) }

    val secretStore: SecretStore by lazy { KeystoreSecretStore(appContext) }

    val settings: DataStoreSettingsRepository by lazy {
        DataStoreSettingsRepository(appContext, secretStore)
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(store = store, settings = settings, onWarning = ::warn)
    }

    /** Reads and writes user-picked backup documents through the Storage Access Framework. */
    val backupFiles: BackupFiles by lazy { BackupFiles(appContext) }

    /**
     * Work that has to happen once per process but must not block `Application.onCreate`.
     *
     * The seed vehicle keeps the very first launch from showing an empty overview, and the legacy
     * client did the same thing from `database-native.ts`.
     */
    fun initialize() {
        scope.launch {
            runCatching { store.ensureSeedVehicle() }
                .onFailure { Log.e(TAG, "无法初始化默认车辆", it) }

            // Only keep a periodic worker around when there is actually something to sync; an
            // unconfigured worker would just wake up every few hours to do nothing.
            runCatching { settings.config() }
                .onSuccess { config ->
                    if (config.url.isNotBlank()) SyncScheduler.schedulePeriodic(appContext)
                }
                .onFailure { Log.w(TAG, "无法读取同步配置", it) }
        }
    }

    /**
     * A foreground sync on behalf of the UI: runs the engine and moves the "last error" marker in
     * step with the outcome, so the settings screen reflects the latest attempt. Rethrows so the
     * caller can surface the failure.
     */
    suspend fun syncNow(): Instant {
        val config = settings.config()
        return try {
            syncEngine.sync(config).also { settings.clearLastError() }
        } catch (error: Throwable) {
            settings.recordLastError(error)
            throw error
        }
    }

    suspend fun testConnection() {
        syncEngine.testConnection(settings.config())
    }

    private fun warn(message: String, error: Throwable) {
        Log.w(TAG, message, error)
    }

    private companion object {
        const val TAG = "FuelTrack"
    }
}

/** Reaches the container from a [Context]; the cast is safe for every context this app creates. */
val Context.appContainer: AppContainer
    get() = (applicationContext as FuelTrackApplication).container
