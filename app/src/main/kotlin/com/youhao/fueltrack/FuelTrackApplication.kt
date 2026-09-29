package com.youhao.fueltrack

import android.app.Application

/**
 * Owns the single [AppContainer] for the process.
 *
 * The legacy app had no equivalent: a WebView app cannot run anything that outlives its page, so
 * background sync is genuinely new behaviour rather than a port. See [SyncScheduler].
 */
class FuelTrackApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
    }
}
