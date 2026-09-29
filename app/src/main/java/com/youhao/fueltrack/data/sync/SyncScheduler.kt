package com.youhao.fueltrack.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Books the background sync with WorkManager.
 *
 * Six hours is a deliberate compromise: fuel records change a handful of times a week, so anything
 * more frequent would only spend radio time re-uploading an identical file. The job is unique by
 * name, so calling [schedulePeriodic] again updates the existing schedule instead of stacking a
 * second one up.
 */
object SyncScheduler {

    private val REPEAT_INTERVAL: Duration = Duration.ofHours(6)
    private val FLEX_INTERVAL: Duration = Duration.ofMinutes(30)
    private val BACKOFF: Duration = Duration.ofMinutes(1)

    private val networkRequired: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(REPEAT_INTERVAL, FLEX_INTERVAL)
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Used when the user clears the WebDAV configuration: nothing left to sync. */
    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK)
    }
}
