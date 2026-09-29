package com.youhao.fueltrack.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException

/**
 * Runs a WebDAV sync without the UI.
 *
 * Background sync needs two things to survive process death, and both are opt-in in Settings:
 *
 * 1. The WebDAV password. Unless the user turned on "记住密码以便后台同步", the password only lives
 *    in memory (the legacy app kept it in `sessionStorage`), so a worker started after the process
 *    died has nothing to authenticate with. That case is not reported as a failure — there is
 *    nothing the user could act on in a background run — so it returns success and the next sync
 *    the user triggers does the work.
 * 2. A remembered encryption passphrase, without which an encrypted remote file cannot be read.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer

        val config = try {
            container.settings.config()
        } catch (error: Throwable) {
            // A broken DataStore is not something a retry will fix.
            return Result.failure()
        }

        if (config.url.isBlank()) return Result.success()
        if (config.password.isBlank()) return Result.success()

        return try {
            container.syncNow()
            Result.success()
        } catch (error: AppException) {
            when (error.code) {
                AppErrorCode.NETWORK_FAILED, AppErrorCode.SYNC_TIMEOUT -> Result.retry()
                // Authentication and format problems need the user; retrying would only burn
                // battery and, for a format error, risk overwriting a good remote file.
                else -> Result.failure()
            }
        } catch (error: Throwable) {
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "fuel-track-periodic-sync"
    }
}
