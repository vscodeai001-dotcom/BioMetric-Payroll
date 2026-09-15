package com.biometric.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single gate for Admin/SuperAdmin realtime invalidation.
 *
 * SignalR may deliver several domain events for one EF transaction.  The Web
 * side already treats ApplicationDataChanged as the central invalidation signal.
 * Android coalesces bursts and only invalidates the currently visible UI.
 * Firebase is the realtime transport for the mobile application.
 */
@Singleton
class AdminRealtimeCoordinator @Inject constructor(
    private val signalR: SignalRManager,
    private val firebaseSync: FirebaseSyncManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var pendingRefresh: Job? = null

    fun start(onLocalRefresh: () -> Unit = {}) {
        if (collectJob?.isActive == true) return

        signalR.start()
        collectJob = scope.launch {
            launch {
                firebaseSync.applicationEventsFlow().collectLatest { event ->
                    if (event.changes.isEmpty()) return@collectLatest
                    pendingRefresh?.cancel()
                    pendingRefresh = launch {
                        delay(120)
                        withContext(Dispatchers.Main.immediate) { onLocalRefresh() }
                    }
                }
            }

            launch {
                signalR.dataChangeEvents.collectLatest { event ->
                // Only database/application invalidation events enter the
                // realtime UI invalidation pipeline. High-frequency GPS and
                // session/geofence events have their own realtime consumers.
                if (!requiresAuthoritativeSync(event)) {
                    return@collectLatest
                }

                pendingRefresh?.cancel()
                pendingRefresh = launch {
                    delay(180)
                    withContext(Dispatchers.Main.immediate) { onLocalRefresh() }
                }
                }
            }
        }
    }

    fun requestRefresh(onLocalRefresh: () -> Unit = {}) {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(80)
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
            // trigger a complete database synchronization.
            is SignalRManager.SyncEvent.LocationChanged,
            is SignalRManager.SyncEvent.GeoSettingsChanged,
            is SignalRManager.SyncEvent.SessionEnded -> false

            // Keep future event types conservative: they should opt in
            // explicitly rather than accidentally creating a sync storm.
            else -> false
        }


    fun stop() {
        pendingRefresh?.cancel()
        collectJob?.cancel()
        pendingRefresh = null
        collectJob = null
        signalR.stop()
    }
}
