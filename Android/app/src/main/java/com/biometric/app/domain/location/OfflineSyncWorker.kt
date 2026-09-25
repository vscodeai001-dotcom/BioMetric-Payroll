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
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.sync.FirebaseSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val punchDao: LocalAttendancePunchDao,
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

        // Synchronize any offline punches made while internet was disconnected
        syncOfflinePunches()

        val deferredEndEvents = syncTrackingEvents()
        if (deferredEndEvents == null) {
            monitor.pruneRetention()
            return Result.retry()
        }

        val queued = withContext(Dispatchers.IO) {
            locationDao.getPendingForSync()
        }

        if (queued.isEmpty()) {
            if (deferredEndEvents.isNotEmpty() && !syncEventBatch(deferredEndEvents)) {
                monitor.pruneRetention()
                return Result.retry()
            }
            monitor.pruneRetention()
            return if (eventDao.getPendingCount() == 0) Result.success() else Result.retry()
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
                // A queue retry must also have a bounded Firebase attempt.
                // A reachable network does not guarantee that Firebase itself is
                // responsive. Timeout simply leaves this same stable event in
                // the queue for WorkManager's next retry.
                val uploaded = withTimeoutOrNull(15_000L) {
                    firebaseSync.pushLiveLocation(
                        employeeId = sessionStore.employeeId(),
                        sessionId = loc.sessionId,
                        clientEventId = loc.clientEventId,
                        sequence = loc.sequence,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy.toDouble(),
                        speed = loc.speed.toDouble(),
                        bearing = loc.bearing.toDouble(),
                        batteryLevel = loc.batteryLevel,
                        timestamp = loc.timestamp,
                        // Queued records are historical evidence. They must
                        // never overwrite the current live marker.
                        isOffline = true
                    )
                } ?: false

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

        // Close deferred sessions only after all currently queued GPS points
        // have had a chance to reach Firebase. If the GPS drain failed, keep
        // SESSION_ENDED pending so the next retry preserves the same ordering.
        if (!failed && deferredEndEvents.isNotEmpty()) {
            if (!syncEventBatch(deferredEndEvents)) {
                failed = true
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

    private suspend fun syncTrackingEvents(): List<OfflineTrackingEvent>? {
        val pendingEvents = withContext(Dispatchers.IO) {
            eventDao.getPendingSync()
        }

        if (pendingEvents.isEmpty()) return emptyList()

        // Lifecycle ordering is critical after a long offline period. A queued
        // SESSION_ENDED event can have an older eventTime than a later GPS fix,
        // but blindly uploading all events before GPS would end the Web session
        // before its queued locations are replayed. Start events therefore run
        // first, non-lifecycle events remain in their original order, and end
        // events are returned to doWork() for upload only after the GPS ledger
        // has been drained.
        val startEvents = pendingEvents.filter {
            it.eventType.equals(OfflineTrackingEvent.SESSION_STARTED, ignoreCase = true)
        }
        val endEvents = pendingEvents.filter {
            it.eventType.equals(OfflineTrackingEvent.SESSION_ENDED, ignoreCase = true)
        }
        val otherEvents = pendingEvents.filter { event ->
            !event.eventType.equals(OfflineTrackingEvent.SESSION_STARTED, ignoreCase = true) &&
                !event.eventType.equals(OfflineTrackingEvent.SESSION_ENDED, ignoreCase = true)
        }

        // SESSION_STARTED must be durable before the first GPS point is sent.
        if (!syncEventBatch(startEvents)) return null

        // Operational telemetry is independent of GPS session boundaries.
        if (!syncEventBatch(otherEvents)) return null

        return endEvents
    }

    private suspend fun syncEventBatch(events: List<OfflineTrackingEvent>): Boolean {
        for (event in events) {
            try {
                eventDao.markSynced(
                    event.eventId,
                    OfflineTrackingEvent.SYNC_IN_FLIGHT,
                    0
                )

                val uploaded = firebaseSync.pushTrackingEvent(event)
                if (uploaded) {
                    eventDao.markSynced(
                        event.eventId,
                        OfflineTrackingEvent.SYNCED,
                        System.currentTimeMillis()
                    )
                } else {
                    // A queued SESSION_STARTED can belong to a previous local
                    // session that another platform already ended. Do not let
                    // that stale lifecycle event block the entire offline GPS
                    // queue forever. It is safe to acknowledge it because an
                    // ended session must never be resurrected.
                    val staleStartedSession =
                        event.eventType.equals(
                            OfflineTrackingEvent.SESSION_STARTED,
                            ignoreCase = true
                        ) &&
                        event.sessionId?.let { sessionId ->
                            firebaseSync.getTrackingSessionState(
                                sessionStore.employeeId(),
                                sessionId
                            )?.equals("ENDED", ignoreCase = true) == true
                        } == true

                    if (staleStartedSession) {
                        eventDao.markSynced(
                            event.eventId,
                            OfflineTrackingEvent.SYNCED,
                            System.currentTimeMillis()
                        )
                        continue
                    }

                    eventDao.markSynced(
                        event.eventId,
                        OfflineTrackingEvent.SYNC_FAILED,
                        0
                    )
                    return false
                }
            } catch (e: Exception) {
                eventDao.markSynced(
                    event.eventId,
                    OfflineTrackingEvent.SYNC_FAILED,
                    0
                )
                Log.w("OfflineSyncWorker", "Tracking event sync failed", e)
                return false
            }
        }
        return true
    }

    private suspend fun syncOfflinePunches() {
        runCatching {
            val unsynced = withContext(Dispatchers.IO) {
                punchDao.getUnsynced()
            }
            for (local in unsynced) {
                val punch = AttendancePunch(
                    punchId = local.punchId,
                    staffId = local.staffId,
                    date = local.date,
                    type = local.type,
                    timestamp = local.timestamp,
                    latitude = local.latitude,
                    longitude = local.longitude,
                    accuracy = local.accuracy,
                    source = local.source,
                    status = local.status
                )
                firebaseSync.pushAttendancePunch(punch)
                withContext(Dispatchers.IO) {
                    punchDao.upsert(local.copy(syncState = 1))
                }
                Log.i("OfflineSyncWorker", "Reconciled offline punch ${local.punchId} to Firebase")
            }
        }.onFailure {
            Log.w("OfflineSyncWorker", "Failed syncing offline punches", it)
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
