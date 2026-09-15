package com.biometric.app.domain.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.biometric.app.data.LocationDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.OfflineTrackingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise-grade local observability for mobile tracking.
 *
 * IMPORTANT:
 * This class only records operational events.
 * It does NOT decide attendance, geofence state, punch priority,
 * login/logout state, or GPS acceptance/rejection.
 */
@Singleton
class OfflineTrackingMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val eventDao: OfflineTrackingEventDao,
    private val locationDao: LocationDao
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val monitorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var networkAvailable = false

    private var callback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val NETWORK_OFFLINE = "NETWORK_OFFLINE"
        const val NETWORK_ONLINE = "NETWORK_ONLINE"

        const val GPS_FIX_CAPTURED = "GPS_FIX_CAPTURED"
        const val QUEUE_ENQUEUED = "QUEUE_ENQUEUED"

        const val UPLOAD_STARTED = "UPLOAD_STARTED"
        const val UPLOAD_SUCCESS = "UPLOAD_SUCCESS"
        const val UPLOAD_FAILED = "UPLOAD_FAILED"

        const val SYNC_STARTED = "SYNC_STARTED"
        const val SYNC_COMPLETED = "SYNC_COMPLETED"
        const val SYNC_RETRY = "SYNC_RETRY"

        const val SESSION_STARTED = "SESSION_STARTED"
        const val SESSION_REBOUND = "SESSION_REBOUND"

        const val SERVICE_RECOVERED = "SERVICE_RECOVERED"
        const val TRACKING_STOPPED = "TRACKING_STOPPED"
        const val APP_RESTARTED = "APP_RESTARTED"

        const val STORAGE_WARNING = "STORAGE_WARNING"
        const val DATA_INTEGRITY_WARNING = "DATA_INTEGRITY_WARNING"

        const val INFO = "INFO"
        const val WARNING = "WARNING"
        const val ERROR = "ERROR"
        const val CRITICAL = "CRITICAL"
    }

    fun start() {
        if (started) return

        started = true
        networkAvailable = isNetworkAvailable()

        val cb = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val changed = !networkAvailable

                networkAvailable = true

                if (changed) {
                    record(
                        eventType = NETWORK_ONLINE,
                        severity = INFO,
                        message = "Internet connectivity restored; queued GPS and pending local synchronization can resume"
                    )

                    // Trigger the durable GPS queue immediately instead of
                    // waiting for the next 15-minute periodic window.
                    runCatching { OfflineSyncWorker.schedule(context) }

                }
            }

            override fun onLost(network: Network) {
                if (!isNetworkAvailable()) {
                    networkAvailable = false

                    record(
                        eventType = NETWORK_OFFLINE,
                        severity = WARNING,
                        message = "Internet connectivity lost; GPS continues locally"
                    )
                }
            }

            override fun onUnavailable() {
                networkAvailable = false

                record(
                    eventType = NETWORK_OFFLINE,
                    severity = WARNING,
                    message = "Network unavailable; GPS continues locally"
                )
            }
        }

        callback = cb

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(cb)
        }.onFailure {
            Log.w(
                "OfflineTrackingMonitor",
                "Unable to register network callback: ${it.message}"
            )
        }

        record(
            eventType = if (networkAvailable) NETWORK_ONLINE else NETWORK_OFFLINE,
            severity = INFO,
            message = if (networkAvailable) {
                "Offline tracking monitor started online"
            } else {
                "Offline tracking monitor started offline"
            }
        )
    }

    fun isOnline(): Boolean {
        return networkAvailable || isNetworkAvailable()
    }

    /**
     * Non-blocking durable event recording.
     *
     * The caller can safely call this from:
     * - Location callbacks
     * - WorkManager
     * - Connectivity callbacks
     * - Activity/ViewModel code
     */
    fun record(
        eventType: String,
        severity: String = INFO,
        message: String,
        sessionId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        correlationId: String? = null
    ) {
        monitorScope.launch {
            try {
                val queueDepth = locationDao.getPendingCount()

                eventDao.insert(
                    OfflineTrackingEvent(
                        eventId = UUID.randomUUID().toString(),
                        eventTime = System.currentTimeMillis(),
                        eventType = eventType,
                        severity = severity,
                        message = message,
                        sessionId = sessionId,
                        networkAvailable = isOnline(),
                        queueDepth = queueDepth,
                        latitude = latitude,
                        longitude = longitude,
                        accuracy = accuracy,
                        correlationId = correlationId
                    )
                )
            } catch (e: Exception) {
                Log.w(
                    "OfflineTrackingMonitor",
                    "Event persistence failed: ${e.message}"
                )
            }
        }
    }

    suspend fun recentEvents(
        limit: Int = 200
    ): List<OfflineTrackingEvent> =
        withContext(Dispatchers.IO) {
            eventDao.recent(limit)
        }

    suspend fun pendingCount(): Int =
        withContext(Dispatchers.IO) {
            locationDao.getPendingCount()
        }

    suspend fun totalCount(): Int =
        withContext(Dispatchers.IO) {
            locationDao.getTotalCount()
        }

    suspend fun pruneRetention() =
        withContext(Dispatchers.IO) {

            val now = System.currentTimeMillis()

            val cutoff =
                now - 30L * 24 * 60 * 60 * 1000

            eventDao.pruneBefore(cutoff)
            locationDao.pruneSyncedBefore(cutoff)
        }

    fun stop() {
        callback?.let {
            runCatching {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }

        callback = null
        started = false
    }

    fun shutdown() {
        stop()
        monitorScope.cancel()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {

            val network =
                connectivityManager.activeNetwork
                    ?: return false

            val caps =
                connectivityManager.getNetworkCapabilities(network)
                    ?: return false

            caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) &&
                    caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )

        } catch (_: Exception) {
            false
        }
    }
}