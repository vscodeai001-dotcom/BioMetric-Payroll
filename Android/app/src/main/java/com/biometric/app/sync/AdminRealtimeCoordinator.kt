package com.biometric.app.sync

import com.biometric.app.data.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.biometric.app.sync.SignalRManager.SyncEvent
import kotlinx.coroutines.launch
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
            // Hydrate the local cache once when the realtime channel starts.
            // This removes the "only current after manual refresh" gap without
            // changing any screen flow or business logic.
            syncFromAuthoritativeStore()

            signalR.dataChangeEvents.collectLatest { event ->
                // GPS telemetry already has its own low-latency live-location
                // channel. Never run the full Neon CRUD sync for every GPS fix.
                if (event is SyncEvent.LocationChanged) return@collectLatest

                pendingRefresh?.cancel()
                pendingRefresh = launch {
                    delay(120)
                    onLocalRefresh()
                    syncFromAuthoritativeStore()
                }
            }
        }
    }

    fun requestRefresh(onLocalRefresh: () -> Unit = {}) {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(80)
            onLocalRefresh()
            syncFromAuthoritativeStore()
        }
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
