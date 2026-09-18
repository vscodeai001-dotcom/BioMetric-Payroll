package com.biometric.app.sync

import android.util.Log
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
 * Central Admin/SuperAdmin realtime invalidation gate.
 *
 * Firebase is the realtime transport for the native application.
 * SignalR is deliberately NOT used here, so Admin/SuperAdmin screens do not
 * depend on Payroll.Web/Render staying alive for realtime CRUD updates.
 *
 * The existing UI loaders are invoked unchanged. This class only decides
 * when a visible screen should refresh after Firebase reports a committed
 * application-data change.
 */
@Singleton
class AdminRealtimeCoordinator @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val hydrator: FirebaseRoomHydrator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var pendingRefresh: Job? = null
    @Volatile private var activeOwnerUid: String? = null

    @Synchronized
    fun start(onLocalRefresh: () -> Unit = {}) {
        if (!firebaseSync.isAuthenticated()) return
        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() } ?: return

        if (collectJob?.isActive == true && activeOwnerUid == ownerUid) return

        if (activeOwnerUid != null && activeOwnerUid != ownerUid) {
            stop()
        }

        // MainActivity owns the data/hydration start. The coordinator is only
        // an invalidation listener, preventing duplicate Firebase/Room listeners.
        activeOwnerUid = ownerUid

        collectJob = scope.launch {
            firebaseSync.applicationEventsFlow().collectLatest { event ->
                if (event.changes.isEmpty()) return@collectLatest

                pendingRefresh?.cancel()
                pendingRefresh = launch {
                    delay(120L)
                    withContext(Dispatchers.Main.immediate) {
                        runCatching {
                            onLocalRefresh()
                        }.onFailure {
                            Log.d(
                                "AdminRealtimeCoordinator",
                                "Visible admin refresh skipped",
                                it
                            )
                        }
                    }
                }
            }
        }
    }

    fun requestRefresh(onLocalRefresh: () -> Unit = {}) {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(80L)
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    onLocalRefresh()
                }.onFailure {
                    Log.d(
                        "AdminRealtimeCoordinator",
                        "Requested admin refresh skipped",
                        it
                    )
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        pendingRefresh?.cancel()
        collectJob?.cancel()
        pendingRefresh = null
        collectJob = null
        activeOwnerUid = null
    }
}
