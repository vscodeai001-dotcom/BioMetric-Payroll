package com.biometric.app.sync

import com.biometric.app.data.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single gate for Admin/SuperAdmin realtime invalidation.
 *
 * SignalR may deliver several domain events for one EF transaction.  The Web
 * side already treats ApplicationDataChanged as the central invalidation signal.
 * Android therefore coalesces the burst and performs at most one Neon pull at a
 * time. Room remains the UI cache while Neon/PostgreSQL remains authoritative.
 */
@Singleton
class AdminRealtimeCoordinator @Inject constructor(
    private val signalR: SignalRManager,
    private val repository: MainRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var collectJob: Job? = null
    private var pendingRefresh: Job? = null

    fun start(onLocalRefresh: () -> Unit = {}) {
        if (collectJob?.isActive == true) return

        signalR.start()
        collectJob = scope.launch {
            signalR.dataChangeEvents.collectLatest { event ->
                // Only database/application invalidation events enter the
                // authoritative Neon pull pipeline. High-frequency GPS and
                // session/geofence events have their own realtime consumers.
                if (!requiresAuthoritativeSync(event)) {
                    return@collectLatest
                }

                pendingRefresh?.cancel()
                pendingRefresh = launch {
                    delay(180)
                    syncFromAuthoritativeStore()
                    withContext(Dispatchers.Main.immediate) { onLocalRefresh() }
                }
            }
        }
    }

    fun requestRefresh(onLocalRefresh: () -> Unit = {}) {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(80)
            syncFromAuthoritativeStore()
            withContext(Dispatchers.Main.immediate) { onLocalRefresh() }
        }
    }

    private fun requiresAuthoritativeSync(event: SignalRManager.SyncEvent): Boolean =
        when (event) {
            is SignalRManager.SyncEvent.DataChanged,
            is SignalRManager.SyncEvent.AttendanceChanged,
            is SignalRManager.SyncEvent.PunchChanged,
            is SignalRManager.SyncEvent.LeaveChanged,
            is SignalRManager.SyncEvent.AdvanceChanged,
            is SignalRManager.SyncEvent.BonusChanged,
            is SignalRManager.SyncEvent.TaxDeclarationChanged,
            is SignalRManager.SyncEvent.EmployeeChanged,
            is SignalRManager.SyncEvent.GlobalRefresh,
            is SignalRManager.SyncEvent.RegularizationChanged,
            is SignalRManager.SyncEvent.ExitChanged,
            is SignalRManager.SyncEvent.SessionStarted -> true

            // These are low-latency/session-state channels and must never
            // trigger a complete Neon synchronization.
            is SignalRManager.SyncEvent.LocationChanged,
            is SignalRManager.SyncEvent.GeoSettingsChanged,
            is SignalRManager.SyncEvent.SessionEnded -> false

            // Keep future event types conservative: they should opt in
            // explicitly rather than accidentally creating a sync storm.
            else -> false
        }

    private suspend fun syncFromAuthoritativeStore() {
        syncMutex.withLock {
            runCatching { repository.startNeonSync() }
        }
    }

    fun stop() {
        pendingRefresh?.cancel()
        collectJob?.cancel()
        pendingRefresh = null
        collectJob = null
        signalR.stop()
    }
}
