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
            signalR.dataChangeEvents.collectLatest {
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
    }
}
