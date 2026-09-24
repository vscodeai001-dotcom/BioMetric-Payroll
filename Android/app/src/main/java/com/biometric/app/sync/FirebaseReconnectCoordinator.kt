package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.domain.location.OfflineSyncWorker
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 1015 Offline / Reconnect recovery coordinator.
 *
 * Firebase Realtime Database already persists queued writes locally. This
 * coordinator adds the missing application-level recovery trigger: when the
 * Firebase connection returns, the normal Firebase -> Room listeners are
 * started again and the durable GPS queue is explicitly scheduled.
 *
 * No Web/Render/Neon dependency is introduced and no screen is changed.
 */
@Singleton
class FirebaseReconnectCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firebaseSync: FirebaseSyncManager,
    private val hydrator: FirebaseRoomHydrator,
    private val sessionStore: MobileSessionStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ValueEventListener? = null
    private var connectedRef: DatabaseReference? = null
    private var running = false

    @Synchronized
    fun start() {
        if (running || !sessionStore.isLoggedIn() || !firebaseSync.isAuthenticated()) return
        running = true

        firebaseSync.startSync()
        hydrator.start()

        val ref = firebaseSync.getGlobalRef().child(".info").child("connected")
        connectedRef = ref
        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) == true
                if (!connected) return

                // Firebase has resumed transport. Force a full listener rebind so
                // stale ChildEventListeners (which silently stop delivering updates
                // after a long background/idle period) are detached and reattached.
                // Using forceRebind() bypasses the listeners.isNotEmpty() guard that
                // made hydrator.start() a no-op after a long idle, which was the root
                // cause of the "no live emp / stale data" bug after hours of inactivity.
                scope.launch {
                    runCatching {
                        firebaseSync.startSync()
                        hydrator.forceRebind("Firebase transport reconnected after idle")
                        OfflineSyncWorker.schedule(context)
                    }.onFailure {
                        Log.w("FirebaseReconnect", "Reconnect recovery scheduling failed", it)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseReconnect", "Connection monitor cancelled", error.toException())
            }
        }
        listener = valueListener
        ref.addValueEventListener(valueListener)
    }

    @Synchronized
    fun stop() {
        listener?.let { connectedRef?.removeEventListener(it) }
        listener = null
        connectedRef = null
        running = false
    }
}
