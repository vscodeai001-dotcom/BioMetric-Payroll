package com.biometric.app.domain.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.biometric.app.api.GpsUpdateRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val locationDao: LocationDao,
    private val mobileApi: MobileApiService,
    private val sessionStore: MobileSessionStore
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val token = sessionStore.token() ?: return Result.success()
        
        val queued = withContext(Dispatchers.IO) {
            locationDao.getAllQueued()
        }
        
        if (queued.isEmpty()) return Result.success()

        Log.i("OfflineSync", "Syncing ${queued.size} offline locations...")

        var successCount = 0
        queued.forEach { loc ->
            try {
                val response = mobileApi.updateGps(
                    "Bearer $token",
                    GpsUpdateRequest(
                        sessionId = loc.sessionId,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy.toDouble(),
                        speed = loc.speed.toDouble(),
                        timestamp = loc.timestamp,
                        batteryLevel = loc.batteryLevel
                    )
                )
                if (response.isSuccessful) {
                    successCount++
                }
            } catch (_: Exception) {}
        }

        if (successCount > 0) {
            val idsToDelete = queued.take(successCount).map { it.id }
            withContext(Dispatchers.IO) {
                locationDao.deleteByIds(idsToDelete)
            }
        }

        return if (successCount == queued.size) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("offline_location_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "offline_location_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
