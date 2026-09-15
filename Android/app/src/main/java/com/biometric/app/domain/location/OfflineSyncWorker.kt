package com.biometric.app.domain.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.biometric.app.api.GpsSessionRequest
import com.biometric.app.api.GpsUpdateRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.LocalLocation
import com.biometric.app.sync.FirebaseSyncManager
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
    private val sessionStore: MobileSessionStore,
    private val monitor: OfflineTrackingMonitor,
    private val firebaseSync: FirebaseSyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Firebase is the Render-independent transport. Do not require the
        // legacy Payroll.Web bearer token before draining the local GPS queue.
        // A Render outage must never prevent queued GPS evidence from reaching
        // Firebase. The Neon/Payroll.Web upload below remains best-effort.
        val token = sessionStore.token()
        if (!monitor.isOnline()) return Result.retry()

        withContext(Dispatchers.IO) {
            locationDao.recoverStaleInFlight(System.currentTimeMillis() - 2 * 60 * 1000L)
        }
        val queued = withContext(Dispatchers.IO) { locationDao.getPendingForSync() }
        if (queued.isEmpty()) {
            monitor.pruneRetention()
            return Result.success()
        }

        monitor.record(
            OfflineTrackingMonitor.SYNC_STARTED,
            OfflineTrackingMonitor.INFO,
            "Starting ordered offline GPS sync for ${queued.size} pending fixes"
        )

        var synced = 0
        var hadRetryableFailure = false

        // Strict timestamp order prevents an offline route from arriving out of order.
        for (loc in queued) {
            if (!monitor.isOnline()) {
                hadRetryableFailure = true
                break
            }

            val attempt = loc.attemptCount + 1
            locationDao.markAttempt(
                loc.id,
                LocalState.IN_FLIGHT,
                attempt,
                System.currentTimeMillis(),
                null
            )
            monitor.record(
                OfflineTrackingMonitor.UPLOAD_STARTED,
                OfflineTrackingMonitor.INFO,
                "Uploading GPS fix ${loc.sequence} captured at ${formatTime(loc.timestamp)} (attempt $attempt)",
                sessionId = loc.sessionId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = loc.accuracy,
                correlationId = loc.clientEventId
            )

            var firebaseUploaded = loc.syncState == "FIREBASE_SYNCED"
            try {
                // Do not repeat the Firebase write when this exact local row was
                // already acknowledged there. This is especially important when
                // Render is down for a long period: Firebase should receive each
                // immutable client event once, while the Neon upload can keep
                // retrying independently.
                if (!firebaseUploaded) {
                    firebaseUploaded = firebaseSync.pushLiveLocation(
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
                }

                if (firebaseUploaded) {
                    locationDao.markAttempt(
                        loc.id,
                        LocalLocation.FIREBASE_SYNCED,
                        attempt,
                        System.currentTimeMillis(),
                        null
                    )
                }

                var sessionId = loc.sessionId
                if (token.isNullOrBlank()) {
                    // Firebase has accepted this point, but there is currently
                    // no legacy server token. Preserve FIREBASE_SYNCED state and
                    // retry the Neon persistence path on a later run.
                    locationDao.markAttempt(
                        loc.id,
                        if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalState.SYNC_FAILED,
                        attempt,
                        System.currentTimeMillis(),
                        "SERVER_TOKEN_UNAVAILABLE"
                    )
                    hadRetryableFailure = true
                    if (firebaseUploaded) continue
                    break
                }

                var response = mobileApi.updateGps(
                    "Bearer $token",
                    GpsUpdateRequest(
                        sessionId = sessionId,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy.toDouble(),
                        speed = loc.speed.toDouble(),
                        timestamp = loc.timestamp,
                        batteryLevel = loc.batteryLevel,
                        clientEventId = loc.clientEventId,
                        sequence = loc.sequence
                    )
                )

                // A server-side session can expire while the phone is offline.
                // Rebind the pending point to the new authoritative session, but
                // preserve its original GPS timestamp and client event identity.
                if (response.code() == 409) {
                    sessionStore.clearGpsSession()
                    val newSession = sessionStore.gpsSessionId()
                    val start = mobileApi.startGps("Bearer $token", GpsSessionRequest(newSession))
                    if (start.isSuccessful && start.body()?.success == true) {
                        sessionId = newSession
                        locationDao.rebindSession(loc.id, newSession)
                        monitor.record(
                            OfflineTrackingMonitor.SESSION_REBOUND,
                            OfflineTrackingMonitor.WARNING,
                            "Server GPS session expired; pending fix rebound to a fresh authoritative session",
                            sessionId = newSession,
                            correlationId = loc.clientEventId
                        )
                        response = mobileApi.updateGps(
                            "Bearer $token",
                            GpsUpdateRequest(
                                sessionId = sessionId,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                accuracy = loc.accuracy.toDouble(),
                                speed = loc.speed.toDouble(),
                                timestamp = loc.timestamp,
                                batteryLevel = loc.batteryLevel,
                                clientEventId = loc.clientEventId,
                                sequence = loc.sequence
                            )
                        )
                    }
                }

                if (response.isSuccessful) {
                    locationDao.markSynced(loc.id, System.currentTimeMillis())
                    synced++
                    monitor.record(
                        OfflineTrackingMonitor.UPLOAD_SUCCESS,
                        OfflineTrackingMonitor.INFO,
                        "GPS fix ${loc.sequence} acknowledged by server",
                        sessionId = sessionId,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        correlationId = loc.clientEventId
                    )
                } else if (response.code() == 401 || response.code() == 403) {
                    locationDao.markAttempt(
                        loc.id,
                        LocalState.SYNC_FAILED,
                        attempt,
                        System.currentTimeMillis(),
                        "AUTH_${response.code()}"
                    )
                    monitor.record(
                        OfflineTrackingMonitor.UPLOAD_FAILED,
                        OfflineTrackingMonitor.ERROR,
                        "GPS sync authorization failed (${response.code()}); preserving local evidence",
                        sessionId = loc.sessionId,
                        correlationId = loc.clientEventId
                    )
                    return Result.retry()
                } else {
                    locationDao.markAttempt(
                        loc.id,
                        if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalState.SYNC_FAILED,
                        attempt,
                        System.currentTimeMillis(),
                        "HTTP_${response.code()}"
                    )
                    hadRetryableFailure = true
                    monitor.record(
                        OfflineTrackingMonitor.UPLOAD_FAILED,
                        OfflineTrackingMonitor.WARNING,
                        "GPS sync failed with HTTP ${response.code()}; point retained for retry",
                        sessionId = loc.sessionId,
                        correlationId = loc.clientEventId
                    )
                    if (firebaseUploaded) {
                        // Firebase already has this point. Keep draining the
                        // offline queue even while Render/Neon is unavailable.
                        continue
                    }
                    break
                }
            } catch (t: Throwable) {
                locationDao.markAttempt(
                    loc.id,
                    if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalState.SYNC_FAILED,
                    attempt,
                    System.currentTimeMillis(),
                    t.message?.take(500)
                )
                hadRetryableFailure = true
                monitor.record(
                    OfflineTrackingMonitor.UPLOAD_FAILED,
                    OfflineTrackingMonitor.WARNING,
                    "GPS sync network failure; point retained locally: ${t.message ?: "unknown error"}",
                    sessionId = loc.sessionId,
                    correlationId = loc.clientEventId
                )
                if (firebaseUploaded) {
                    continue
                }
                break
            }
        }

        val remaining = locationDao.getPendingCount()
        monitor.record(
            if (hadRetryableFailure) OfflineTrackingMonitor.SYNC_RETRY else OfflineTrackingMonitor.SYNC_COMPLETED,
            if (hadRetryableFailure) OfflineTrackingMonitor.WARNING else OfflineTrackingMonitor.INFO,
            "Offline GPS sync finished: $synced sent, $remaining pending"
        )
        monitor.pruneRetention()

        return if (remaining == 0 && !hadRetryableFailure) Result.success() else Result.retry()
    }

    private fun formatTime(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date(timestamp))

    private object LocalState {
        const val IN_FLIGHT = "IN_FLIGHT"
        const val SYNC_FAILED = "FAILED"
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
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
