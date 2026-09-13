package com.biometric.app.domain.location

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single scheduling facade for GPS synchronization.
 * The durable OfflineSyncWorker owns the actual ordered sync transaction.
 */
@Singleton
class LocationSyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun scheduleImmediateSync() {
        OfflineSyncWorker.schedule(context)
    }

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("offline_location_sync_periodic")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "offline_location_sync_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    suspend fun syncNow(): androidx.work.ListenableWorker.Result {
        // WorkManager owns retry/backoff and network constraints. Callers should
        // enqueue the worker rather than performing a second competing sync loop.
        scheduleImmediateSync()
        return androidx.work.ListenableWorker.Result.success()
    }
}
