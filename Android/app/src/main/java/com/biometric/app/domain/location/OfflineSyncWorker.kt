package com.biometric.app.domain.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.LocalLocation
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.OfflineTrackingEvent
import com.biometric.app.sync.FirebaseSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Drains locally captured GPS evidence into Firebase.
 *
 * Payroll.Web/Render/Neon is intentionally not required.
 */
@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val locationDao: LocationDao,
    private val eventDao: OfflineTrackingEventDao,
    private val sessionStore: MobileSessionStore,
    private val monitor: OfflineTrackingMonitor,
    private val firebaseSync: FirebaseSyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!firebaseSync.isAuthenticated()) {
            Log.w(
                "OfflineSyncWorker",
                "Firebase authentication unavailable; preserving local GPS queue"
            )
            return Result.retry()
        }

        withContext(Dispatchers.IO) {
            locationDao.recoverStaleInFlight(
                System.currentTimeMillis() - 2 * 60 * 1000L
            )
        }

        syncTrackingEvents()

        val queued = withContext(Dispatchers.IO) {
            locationDao.getPendingForSync()
        }

        if (queued.isEmpty()) {
            monitor.pruneRetention()
            return Result.success()
        }

        var failed = false
        var synced = 0

        for (loc in queued) {
            val attempt = loc.attemptCount + 1

            locationDao.markAttempt(
                loc.id,
                LocalLocation.SYNC_IN_FLIGHT,
                attempt,
                System.currentTimeMillis(),
                null
            )

            try {
                val uploaded = firebaseSync.pushLiveLocation(
                    employeeId = sessionStore.employeeId(),
                    sessionId = loc.sessionId,
                    clientEventId = loc.clientEventId,
                    sequence = loc.sequence,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy.toDouble(),
                    speed = loc.speed.toDouble(),
                    batteryLevel = loc.batteryLevel,
                    timestamp = loc.timestamp
                )

                if (uploaded) {
                    locationDao.markSynced(
                        loc.id,
                        System.currentTimeMillis()
                    )
                    synced++
                } else {
                    failed = true

                    locationDao.markAttempt(
                        loc.id,
                        LocalLocation.SYNC_FAILED,
                        attempt,
                        System.currentTimeMillis(),
                        "FIREBASE_UNAVAILABLE"
                    )
                    break
                }
            } catch (e: Exception) {
                failed = true

                locationDao.markAttempt(
                    loc.id,
                    LocalLocation.SYNC_FAILED,
                    attempt,
                    System.currentTimeMillis(),
                    e.message?.take(500)
                )

                Log.w(
                    "OfflineSyncWorker",
                    "Firebase GPS sync deferred",
                    e
                )
                break
            }
        }

        monitor.record(
            if (failed) {
                OfflineTrackingMonitor.SYNC_RETRY
            } else {
                OfflineTrackingMonitor.SYNC_COMPLETED
            },
            if (failed) {
                OfflineTrackingMonitor.WARNING
            } else {
                OfflineTrackingMonitor.INFO
            },
            "Firebase offline GPS sync finished: $synced sent"
        )

        monitor.pruneRetention()

        val remaining = locationDao.getPendingCount() + eventDao.getPendingCount()

        return if (!failed && remaining == 0) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private suspend fun syncTrackingEvents() {
        val pendingEvents = withContext(Dispatchers.IO) {
            eventDao.getPendingSync()
        }

        for (event in pendingEvents) {
            try {
                // Mark as in-flight
                eventDao.markSynced(event.eventId, OfflineTrackingEvent.SYNC_IN_FLIGHT, 0)
                
                val uploaded = firebaseSync.pushTrackingEvent(event)
                if (uploaded) {
                    eventDao.markSynced(event.eventId, OfflineTrackingEvent.SYNCED, System.currentTimeMillis())
                } else {
                    eventDao.markSynced(event.eventId, OfflineTrackingEvent.SYNC_FAILED, 0)
                    break // Stop if one fails
                }
            } catch (e: Exception) {
                eventDao.markSynced(event.eventId, OfflineTrackingEvent.SYNC_FAILED, 0)
                Log.w("OfflineSyncWorker", "Tracking event sync failed", e)
                break
            }
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .addTag("offline_location_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline_location_sync",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
