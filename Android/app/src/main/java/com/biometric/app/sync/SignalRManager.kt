package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.firebase.database.*
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility facade retained for existing screens.
 *
 * The implementation is Firebase-only. No SignalR hub or Payroll.Web endpoint
 * is opened, so realtime UI and live-location consumers remain functional
 * when the Web application/Render is unavailable.
 */
@Singleton
class SignalRManager @Inject constructor(
    private val sessionStore: MobileSessionStore,
    private val firebaseSync: FirebaseSyncManager
) {
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var applicationJob: Job? = null
    private var connectionJob: Job? = null
    private var reconciliationJob: Job? = null
    private var realtimeHealthJob: Job? = null

    // Firebase Realtime Database normally reconnects automatically. Keep an
    // application-level health watchdog as a second safety net because an
    // authenticated long-lived listener can occasionally remain connected at
    // the transport layer while no longer delivering fresh snapshots.
    @Volatile private var lastSuccessfulLiveReadAt: Long = 0L

    // Firebase Auth ID tokens are short-lived. Proactively refresh well before
    // expiry so an Admin dashboard does not need a logout/login cycle to recover
    // its live-location listener.
    private val AUTH_TOKEN_REFRESH_INTERVAL_MS = 20L * 60L * 1000L
    private val LIVE_READ_HEALTH_TIMEOUT_MS = 30L * 1000L

    // Firebase Auth can restore before the persisted owner UID is available.
    // Retry startup briefly so Admin realtime does not require logout/login.
    private var startRetryJob: Job? = null
    private var realtimeRecoveryJob: Job? = null
    private var locationListener: ValueEventListener? = null
    private var employeeListener: ValueEventListener? = null

    // Employee Android writes are published to client_events because employees
    // are not allowed to write owner_events. Admin/SuperAdmin clients must
    // consume that channel too, otherwise Android Admin can remain stale until
    // a fresh login.
    private var clientEventsRootListener: ChildEventListener? = null
    private val clientEventEmployeeListeners = mutableMapOf<String, Pair<Query, ChildEventListener>>()

    // Firebase Auth restoration can finish after MainActivity is created.
    // Keep a process-wide auth bridge so realtime starts automatically when the
    // persisted Firebase user becomes available.
    private val authStateListener = FirebaseAuth.AuthStateListener {
        val firebaseUserUid = FirebaseAuth.getInstance().currentUser?.uid
        if (!firebaseUserUid.isNullOrBlank() && sessionStore.isLoggedIn()) {
            managerScope.launch { start() }
        } else {
            stop()
        }
    }
    private var authStateRegistered = false
    private val ownerEmployeeIds = mutableSetOf<Int>()
    private var lastOwnerLiveLocations: Map<Int, LiveLocation> = emptyMap()
    @Volatile private var activeOwnerUid: String? = null
    @Volatile private var liveSnapshotGeneration: Long = 0L

    private val _dataChangeEvents = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 64)
    val dataChangeEvents = _dataChangeEvents.asSharedFlow()

    private val _liveLocations = MutableStateFlow<Map<Int, LiveLocation>>(emptyMap())
    val liveLocations = _liveLocations.asStateFlow()

    // Authoritative owner-scoped employee directory used by Admin map binding.
    // This is intentionally sourced from the same Firebase tenant as tracking/live
    // so a Room-cache failure cannot make a real live employee disappear.
    private val _ownerEmployees = MutableStateFlow<Map<Int, OwnerEmployee>>(emptyMap())
    val ownerEmployees = _ownerEmployees.asStateFlow()

    private fun publishOwnerScopedLocations(raw: Map<Int, LiveLocation>) {
        // Authoritative tenant check: render only valid owner-scoped GPS records.
        val filtered = raw
            .filterKeys { it > 0 }
            .filterValues { it.sessionId.isNotBlank() }

        val previous = _liveLocations.value
        if (liveLocationMapsEquivalent(previous, filtered)) {
            // Do not emit another UI event for an identical snapshot. This is
            // important for smooth Admin dashboards because Firebase can replay
            // the same parent snapshot during reconnects.
            return
        }

        _liveLocations.value = filtered
        _dataChangeEvents.tryEmit(SyncEvent.LocationChanged)
    }

    private fun liveLocationMapsEquivalent(
        left: Map<Int, LiveLocation>,
        right: Map<Int, LiveLocation>
    ): Boolean {
        if (left.size != right.size) return false
        if (left.keys != right.keys) return false

        return left.all { (id, a) ->
            val b = right[id] ?: return@all false
            a.sessionId == b.sessionId &&
                a.latitude == b.latitude &&
                a.longitude == b.longitude &&
                a.accuracyMeters == b.accuracyMeters &&
                a.distanceMeters == b.distanceMeters &&
                a.allowedRadiusMeters == b.allowedRadiusMeters &&
                a.isWithinAllowedRadius == b.isWithinAllowedRadius &&
                a.timestamp == b.timestamp &&
                a.speedMps == b.speedMps &&
                a.movementState == b.movementState
        }
    }

    @Synchronized
    fun start() {
        if (!authStateRegistered) {
            FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
            authStateRegistered = true
        }

        // Firebase Auth restoration may still be in progress. The auth listener
        // above will call start() again as soon as the user is available.
        if (!sessionStore.isLoggedIn() && !firebaseSync.isAuthenticated()) return

        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() }
        if (ownerUid == null) {
            scheduleStartRetry()
            return
        }

        startRetryJob?.cancel()
        startRetryJob = null
        if (applicationJob?.isActive == true && activeOwnerUid == ownerUid) {
            // Main/Admin Activity can remain alive for hours. Calling start()
            // again must still force a canonical Firebase read; otherwise a
            // long-lived UI can keep showing an old live snapshot until the
            // user logs out/in and recreates the manager.
            reconcileLiveLocationsNow()
            return
        }
        if (activeOwnerUid != null && activeOwnerUid != ownerUid) stop()

        firebaseSync.startSync()
        activeOwnerUid = ownerUid

        applicationJob = managerScope.launch {
            firebaseSync.applicationEventsFlow().collect { event ->
                if (event.changes.isNotEmpty()) {
                    _dataChangeEvents.emit(SyncEvent.GlobalRefresh)
                }
            }
        }

        val role = sessionStore.userRole().orEmpty()
        val employeeId = sessionStore.employeeId()
        val liveRef = firebaseSync.getGlobalRef()
            .child("owners")
            .child(ownerUid)
            .child("tracking")
            .child("live")
            .let { ref ->
                if (role.equals("STAFF", true) || role.equals("EMPLOYEE", true)) {
                    ref.child(employeeId.toString())
                } else ref
            }

        // Spark Mode Optimization (Option B1): Restrict keepSynced(true) to individual employee node only.
        // For Admin multi-employee views, do not force offline disk caching of the entire tree to save ~50GB/month bandwidth.
        val isEmployeeRole = role.equals("STAFF", true) || role.equals("EMPLOYEE", true)
        if (isEmployeeRole) {
            liveRef.keepSynced(true)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSuccessfulLiveReadAt = System.currentTimeMillis()
                val locations = mutableMapOf<Int, LiveLocation>()

                // Never call getValue(FirebaseLiveLocation) on the parent
                // tracking/live node. A collection is decoded by Firebase as an
                // ArrayList in some data shapes, which caused the fatal crash:
                // "Can't convert object of type java.util.ArrayList ...".
                //
                // The canonical schema is tracking/live/{EmployeeId}/{fields}.
                // A single employee node is supported for Employee/Staff sessions.
                val isSingleEmployeeNode =
                    snapshot.hasChild("EmployeeId") ||
                            snapshot.hasChild("employeeId") ||
                            snapshot.hasChild("Latitude") ||
                            snapshot.hasChild("latitude")

                val children = if (isSingleEmployeeNode) {
                    listOf(snapshot)
                } else {
                    snapshot.children.toList()
                }

                for (child in children) {
                    val state = sequenceOf(
                        child.child("State").value?.toString(),
                        child.child("state").value?.toString()
                    ).filterNotNull().firstOrNull { it.isNotBlank() }

                    // Match Web Admin lifecycle filtering. Firebase can retain
                    // a terminal compatibility node briefly after logout/end.
                    if (state.equals("ENDED", true) ||
                        state.equals("OFFLINE", true)) {
                        continue
                    }

                    val keyEmployeeId = child.key?.toIntOrNull()

                    val value = runCatching {
                        // Never rely on Firebase bean conversion for live GPS.
                        // Realtime Database may expose mixed historical shapes
                        // (object/array and numeric values stored as strings).
                        // Read the scalar fields explicitly so one malformed
                        // historical node can never crash the Admin process.
                        readLiveLocation(child)
                    }.onFailure { error ->
                        Log.w(
                            "SignalRManager",
                            "Ignoring malformed live-location node ${child.key}",
                            error
                        )
                    }.getOrNull() ?: continue

                    val payloadEmployeeId = value.EmployeeId

                    // tracking/live is keyed by the canonical EmployeeId. Reject
                    // malformed/scaffold nodes and payload/key mismatches.
                    val employeeId = when {
                        keyEmployeeId != null && keyEmployeeId > 0 -> {
                            if (payloadEmployeeId > 0 && payloadEmployeeId != keyEmployeeId) continue
                            keyEmployeeId
                        }
                        keyEmployeeId == null && payloadEmployeeId > 0 -> payloadEmployeeId
                        else -> continue
                    }

                    if (employeeId <= 0) continue

                    if (value.SessionId.isBlank()) {
                        // A live marker without a GPS session is never authoritative.
                        continue
                    }

                    // Web Parity: ignore unanchored / Null Island (0.0, 0.0) coordinates
                    // until authoritative GPS coordinates are received.
                    if (!value.Latitude.isFinite() || !value.Longitude.isFinite() ||
                        value.Latitude < -90.0 || value.Latitude > 90.0 ||
                        value.Longitude < -180.0 || value.Longitude > 180.0 ||
                        (value.Latitude == 0.0 && value.Longitude == 0.0)) {
                        continue
                    }

                    locations[employeeId] = LiveLocation(
                        employeeId = employeeId,
                        sessionId = value.SessionId,
                        latitude = value.Latitude,
                        longitude = value.Longitude,
                        accuracyMeters = value.AccuracyMeters,
                        distanceMeters = value.DistanceMeters,
                        allowedRadiusMeters = value.AllowedRadiusMeters,
                        isWithinAllowedRadius = value.IsWithinAllowedRadius,
                        timestamp = value.Timestamp,
                        speedMps = value.SpeedMps,
                        movementState = value.MovementState
                    )
                }

                // Web Parity: tracking/live snapshot is the real-time SSOT.
                // Publish active locations immediately without blocking secondary network loops.
                lastOwnerLiveLocations = locations.toMap()
                managerScope.launch(Dispatchers.Main.immediate) {
                    publishOwnerScopedLocations(lastOwnerLiveLocations)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // IMPORTANT: Firebase may cancel a long-lived listener with
                // PERMISSION_DENIED when the ID token/claims used by the
                // listener are no longer accepted. Do NOT permanently ignore
                // that callback. Previously this branch ignored
                // PERMISSION_DENIED, leaving Android Admin with an old
                // liveLocations StateFlow until logout/login recreated the
                // Firebase listener.
                Log.w(
                    "SignalRManager",
                    "Firebase live-location listener cancelled: code=${error.code}, message=${error.message}"
                )

                if (activeOwnerUid != ownerUid || !firebaseSync.isAuthenticated()) return

                realtimeRecoveryJob?.cancel()
                realtimeRecoveryJob = managerScope.launch {
                    delay(500L)

                    if (activeOwnerUid != ownerUid || !firebaseSync.isAuthenticated()) return@launch

                    // Refresh the Firebase ID token first. Firebase Database
                    // listeners created with the old credentials are then
                    // rebuilt so recovery does not require manual logout/login.
                    runCatching {
                        FirebaseAuth.getInstance()
                            .currentUser
                            ?.getIdToken(true)
                            ?.await()
                    }.onFailure { refreshError ->
                        Log.w(
                            "SignalRManager",
                            "Firebase Auth token refresh failed during live listener recovery.",
                            refreshError
                        )
                    }

                    if (activeOwnerUid == ownerUid && firebaseSync.isAuthenticated()) {
                        runCatching {
                            // stop() intentionally cancels any queued recovery
                            // job. Clear this reference first so the current
                            // recovery coroutine can perform the restart.
                            realtimeRecoveryJob = null
                            stop()
                            delay(250L)
                            start()
                        }.onFailure { restartError ->
                            Log.w(
                                "SignalRManager",
                                "Firebase live listener restart failed; automatic retry will continue.",
                                restartError
                            )
                        }
                    }
                }
            }
        }

        locationListener = listener
        liveRef.addValueEventListener(listener)

        // Authoritative employee binding for the current tenant. Admin maps
        // must render only employees that actually exist under this owner.
        val employeesRef = firebaseSync.getGlobalRef()
            .child("owners")
            .child(ownerUid)
            .child("employees")
        val employeesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val employees = mutableMapOf<Int, OwnerEmployee>()
                snapshot.children.forEach { child ->
                    val keyId = child.key?.toIntOrNull()
                    val rowId = child.child("employeeId").value?.toString()?.toIntOrNull()
                    val id = keyId ?: rowId ?: 0
                    if (id <= 0) return@forEach

                    val name = sequenceOf(
                        child.child("name").getValue(String::class.java),
                        child.child("Name").getValue(String::class.java)
                    ).filterNotNull().firstOrNull { it.isNotBlank() }
                        ?: "Employee #$id"

                    val email = sequenceOf(
                        child.child("email").getValue(String::class.java),
                        child.child("Email").getValue(String::class.java)
                    ).filterNotNull().firstOrNull { it.isNotBlank() }
                        ?: ""

                    employees[id] = OwnerEmployee(id, name, email)
                }

                synchronized(ownerEmployeeIds) {
                    ownerEmployeeIds.clear()
                    ownerEmployeeIds.addAll(employees.keys)
                }
                _ownerEmployees.value = employees

                // Important: a live GPS record can arrive before employee master
                // hydration. Re-apply the retained live snapshot immediately after
                // the authoritative employee directory becomes available.
                publishOwnerScopedLocations(lastOwnerLiveLocations)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("SignalRManager", "Owner employee binding listener cancelled: code=${error.code}, message=${error.message}")

                if (activeOwnerUid != ownerUid || !firebaseSync.isAuthenticated()) return

                realtimeRecoveryJob?.cancel()
                realtimeRecoveryJob = managerScope.launch {
                    delay(500L)
                    if (activeOwnerUid != ownerUid || !firebaseSync.isAuthenticated()) return@launch

                    runCatching {
                        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
                    }

                    if (activeOwnerUid == ownerUid && firebaseSync.isAuthenticated()) {
                        runCatching {
                            realtimeRecoveryJob = null
                            stop()
                            delay(250L)
                            start()
                        }
                    }
                }
            }
        }
        employeeListener = employeesListener
        employeesRef.addValueEventListener(employeesListener)

        if (role.equals("ADMIN", true) ||
            role.equals("SUPERADMIN", true) ||
            role.equals("SUPER_ADMIN", true) ||
            role.equals("Admin", true) ||
            role.equals("SuperAdmin", true)) {
            startClientEventBridge()
        }

        connectionJob = managerScope.launch {
            firebaseSync.syncStatus.collect { connected ->
                if (connected) {
                    // Network returned. Pull the canonical live node immediately
                    // instead of waiting for the periodic reconciliation or a
                    // new login. This is critical after Employee Android was
                    // offline while Admin Android remained open.
                    reconcileLiveLocationsNow()
                    _dataChangeEvents.emit(SyncEvent.GlobalRefresh)
                }
            }
        }

        reconciliationJob?.cancel()
        reconciliationJob = managerScope.launch {
            while (isActive) {
                delay(30_000L)
                if (activeOwnerUid == ownerUid && firebaseSync.isAuthenticated()) {
                    val now = System.currentTimeMillis()
                    // Spark Mode Optimization (Option B5): Only reconcile if last successful live read was >60s ago
                    if (now - lastSuccessfulLiveReadAt > 60_000L) {
                        reconcileLiveLocationsNow()
                    }
                }
            }
        }

        realtimeHealthJob?.cancel()
        realtimeHealthJob = managerScope.launch {
            var lastTokenRefresh = System.currentTimeMillis()
            while (isActive) {
                delay(30_000L)
                if (activeOwnerUid != ownerUid || !firebaseSync.isAuthenticated()) continue

                val now = System.currentTimeMillis()
                if (now - lastTokenRefresh >= AUTH_TOKEN_REFRESH_INTERVAL_MS) {
                    runCatching {
                        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()
                        lastTokenRefresh = now
                    }
                }

                val lastReadAge = now - lastSuccessfulLiveReadAt
                if (lastSuccessfulLiveReadAt > 0L && lastReadAge > LIVE_READ_HEALTH_TIMEOUT_MS) {
                    reconcileLiveLocationsNow()
                }
            }
        }
    }

    private fun scheduleStartRetry() {
        if (startRetryJob?.isActive == true) return

        startRetryJob = managerScope.launch {
            repeat(60) {
                delay(500L)

                if (!firebaseSync.isAuthenticated()) return@launch

                val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() }
                if (ownerUid != null) {
                    start()
                    return@launch
                }
            }
        }
    }

    /**
     * Immediately re-read the authoritative owner-scoped live-location node.
     * Firebase listeners normally reconnect automatically, but after a device
     * loses internet the visible Admin Dashboard must not wait for a fresh
     * Activity/login cycle. This method is deliberately idempotent and only
     * updates the existing liveLocations StateFlow.
     */
    @Volatile private var lastReconcileTime = 0L

    fun reconcileLiveLocationsNow() {
        val now = System.currentTimeMillis()
        if (now - lastReconcileTime < 5000L) return
        lastReconcileTime = now

        val ownerUid = activeOwnerUid?.takeIf { it.isNotBlank() } ?: return
        val role = sessionStore.userRole().orEmpty()
        val employeeId = sessionStore.employeeId()
        val listener = locationListener ?: return
        val liveRef = firebaseSync.getGlobalRef()
            .child("owners")
            .child(ownerUid)
            .child("tracking")
            .child("live")
            .let { ref ->
                if (role.equals("STAFF", true) || role.equals("EMPLOYEE", true)) {
                    ref.child(employeeId.toString())
                } else {
                    ref
                }
            }

        managerScope.launch {
            runCatching {
                val snapshot = liveRef.get().await()
                withContext(Dispatchers.Main.immediate) {
                    listener.onDataChange(snapshot)
                }
            }.onFailure { error ->
                Log.d(
                    "SignalRManager",
                    "Immediate live-location reconciliation skipped: ${error.message}"
                )
            }

            // Also pull fresh employee directory so live markers always map to valid employees
            employeeListener?.let { empListener ->
                val employeesRef = firebaseSync.getGlobalRef()
                    .child("owners")
                    .child(ownerUid)
                    .child("employees")
                runCatching {
                    val empSnapshot = employeesRef.get().await()
                    withContext(Dispatchers.Main.immediate) {
                        empListener.onDataChange(empSnapshot)
                    }
                }.onFailure { error ->
                    Log.d("SignalRManager", "Immediate employees directory reconciliation skipped: ${error.message}")
                }
            }
        }
    }

    /**
     * Android employees publish their realtime invalidation events under
     * client_events/{employeeId}/{eventId}. Firebase rules intentionally do
     * not allow employee clients to write owner_events directly.
     *
     * Admin Android therefore listens to both channels:
     *   Web/Admin -> owner_events
     *   Employee Android -> client_events
     *
     * The event is only an invalidation signal. Existing Room/Firebase
     * hydration remains the source for the actual record contents.
     */
    private fun startClientEventBridge() {
        if (clientEventsRootListener != null) return

        val rootRef = firebaseSync.getGlobalRef().child("client_events")
        val rootListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                attachClientEventEmployeeNode(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                attachClientEventEmployeeNode(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                detachClientEventEmployeeNode(snapshot.key.orEmpty())
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                attachClientEventEmployeeNode(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("SignalRManager", "Firebase client-events listener cancelled: ${error.message}")
                }
            }
        }

        clientEventsRootListener = rootListener
        rootRef.addChildEventListener(rootListener)
    }

    private fun attachClientEventEmployeeNode(employeeSnapshot: DataSnapshot) {
        val employeeKey = employeeSnapshot.key?.takeIf { it.isNotBlank() } ?: return
        if (clientEventEmployeeListeners.containsKey(employeeKey)) return

        // Only replay the newest invalidation for an existing employee node.
        // New events continue to arrive through the same child listener.
        val employeeRef = employeeSnapshot.ref.orderByChild("timestamp").limitToLast(1)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                emitClientRealtimeChange(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                emitClientRealtimeChange(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                emitClientRealtimeChange(snapshot)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                emitClientRealtimeChange(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("SignalRManager", "Firebase client-events employee listener cancelled: ${error.message}")
                }
            }
        }

        clientEventEmployeeListeners[employeeKey] = employeeRef to listener
        employeeRef.addChildEventListener(listener)
    }

    private fun detachClientEventEmployeeNode(employeeKey: String) {
        if (employeeKey.isBlank()) return
        clientEventEmployeeListeners.remove(employeeKey)?.let { (query, listener) ->
            query.removeEventListener(listener)
        }
    }

    private fun emitClientRealtimeChange(snapshot: DataSnapshot) {
        val changes = snapshot.child("changes").children.mapNotNull { change ->
            val entity = change.child("Entity").value?.toString()
                ?: change.child("entity").value?.toString()
            if (entity.isNullOrBlank()) return@mapNotNull null
            RealtimeChangedItem(
                entity = entity,
                action = change.child("Action").value?.toString()
                    ?: change.child("action").value?.toString()
                    ?: "MODIFIED"
            )
        }

        if (changes.isNotEmpty()) {
            val gpsOrSessionChange = changes.any { item ->
                val entity = item.entity.trim().lowercase()
                entity in setOf(
                    "location",
                    "livelocation",
                    "employee_location",
                    "gps",
                    "gpslocation",
                    "tracking",
                    "trackinglocation",
                    "employeegpssession",
                    "gpssession",
                    "session"
                ) || entity.contains("gps") || entity.contains("location")
            }

            if (gpsOrSessionChange) {
                // GPS/session invalidations update only the live-location StateFlow.
                // The liveRef listener already delivers the updated GPS payload.
                _dataChangeEvents.tryEmit(SyncEvent.LocationChanged)
            } else {
                _dataChangeEvents.tryEmit(SyncEvent.GlobalRefresh)
            }
        }
    }

    /** Loads owner-scoped historical GPS events for one employee.
     *  The history is read-only here; writes continue through the tracking/offline queue.
     *  A stable client event key is used for deduplication during reconnect reconciliation.
     */
    suspend fun loadTrackingHistory(employeeId: Int, limit: Int = 2000): List<LiveLocation> {
        if (employeeId <= 0 || !firebaseSync.isAuthenticated()) return emptyList()
        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val snapshot = firebaseSync.getGlobalRef()
                .child("owners").child(ownerUid).child("tracking")
                .child("history").child(employeeId.toString())
                .orderByChild("Timestamp").limitToLast(limit).get().await()
            val byKey = linkedMapOf<String, LiveLocation>()
            snapshot.children.forEach { child ->
                val map = child.value as? Map<*, *> ?: return@forEach
                val id = child.key ?: return@forEach
                val eid = (map["EmployeeId"] as? Number)?.toInt() ?: employeeId
                if (eid != employeeId) return@forEach
                val lat = (map["Latitude"] as? Number)?.toDouble() ?: return@forEach
                val lon = (map["Longitude"] as? Number)?.toDouble() ?: return@forEach
                val timestamp = map["Timestamp"]?.toString()
                val dedupeKey = "${id}|${timestamp.orEmpty()}|${lat}|${lon}"
                byKey[dedupeKey] = LiveLocation(
                    employeeId = eid,
                    sessionId = map["SessionId"]?.toString().orEmpty(),
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = (map["AccuracyMeters"] as? Number)?.toDouble() ?: 0.0,
                    distanceMeters = (map["DistanceMeters"] as? Number)?.toDouble() ?: 0.0,
                    allowedRadiusMeters = (map["AllowedRadiusMeters"] as? Number)?.toInt() ?: 100,
                    isWithinAllowedRadius = (map["IsWithinAllowedRadius"] as? Boolean) ?: true,
                    timestamp = timestamp,
                    speedMps = (map["SpeedMps"] as? Number)?.toDouble() ?: 0.0,
                    bearing = (map["Bearing"] as? Number)?.toDouble() ?: 0.0,
                    movementState = map["MovementState"]?.toString() ?: "Stopped"
                )
            }
            byKey.values.toList().sortedBy { parseTrackingTimestamp(it.timestamp) }
        }.getOrElse {
            Log.w("SignalRManager", "Historical tracking read failed", it)
            emptyList()
        }
    }

    private fun readLiveLocation(snapshot: DataSnapshot): FirebaseLiveLocation {
        fun any(vararg names: String): Any? = names.firstNotNullOfOrNull { name ->
            val node = snapshot.child(name)
            if (node.exists()) node.value else null
        }

        fun int(vararg names: String): Int = when (val v = any(*names)) {
            is Number -> v.toInt()
            else -> v?.toString()?.trim()?.toIntOrNull() ?: 0
        }

        fun long(vararg names: String): Long = when (val v = any(*names)) {
            is Number -> v.toLong()
            else -> v?.toString()?.trim()?.toLongOrNull() ?: 0L
        }

        fun double(vararg names: String): Double = when (val v = any(*names)) {
            is Number -> v.toDouble()
            else -> v?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        }

        fun bool(vararg names: String): Boolean = when (val v = any(*names)) {
            is Boolean -> v
            else -> v?.toString()?.trim()?.toBooleanStrictOrNull() ?: false
        }

        fun string(vararg names: String): String = any(*names)?.toString().orEmpty()

        val lastUpdatedStr = string("LastUpdatedUtc", "lastUpdatedUtc").takeIf { it.isNotBlank() }
        val timestampStr = string("Timestamp", "timestamp").takeIf { it.isNotBlank() }
        val effectiveTimestamp = when {
            lastUpdatedStr != null && timestampStr != null -> {
                val tLastUpdated = parseTrackingTimestamp(lastUpdatedStr)
                val tTimestamp = parseTrackingTimestamp(timestampStr)
                if (tLastUpdated >= tTimestamp) lastUpdatedStr else timestampStr
            }
            lastUpdatedStr != null -> lastUpdatedStr
            else -> timestampStr
        }

        return FirebaseLiveLocation(
            EmployeeId = int("EmployeeId", "employeeId"),
            SessionId = string("SessionId", "sessionId"),
            Latitude = double("Latitude", "latitude"),
            Longitude = double("Longitude", "longitude"),
            AccuracyMeters = double("AccuracyMeters", "accuracyMeters"),
            DistanceMeters = double("DistanceMeters", "distanceMeters"),
            AllowedRadiusMeters = int("AllowedRadiusMeters", "allowedRadiusMeters"),
            IsWithinAllowedRadius = bool("IsWithinAllowedRadius", "isWithinAllowedRadius"),
            Timestamp = effectiveTimestamp,
            SpeedMps = double("SpeedMps", "speedMps"),
            Bearing = double("Bearing", "bearing"),
            MovementState = string("MovementState", "movementState")
        )
    }

    fun parseTrackingTimestamp(value: String?): Long = runCatching {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let { return it }
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    if (!pattern.endsWith("X")) timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: 0L
    }.getOrDefault(0L)

    private fun employeesRefForStop(ownerUid: String): DatabaseReference =
        firebaseSync.getGlobalRef().child("owners").child(ownerUid).child("employees")

    @Synchronized
    fun stop() {
        val ownerUid = activeOwnerUid
        reconciliationJob?.cancel()
        reconciliationJob = null
        realtimeHealthJob?.cancel()
        realtimeHealthJob = null
        startRetryJob?.cancel()
        startRetryJob = null
        realtimeRecoveryJob?.cancel()
        realtimeRecoveryJob = null
        val role = sessionStore.userRole().orEmpty()
        val employeeId = sessionStore.employeeId()

        if (!ownerUid.isNullOrBlank()) {
            val liveRef = firebaseSync.getGlobalRef()
                .child("owners").child(ownerUid).child("tracking").child("live")
                .let { ref ->
                    if (role.equals("STAFF", true) || role.equals("EMPLOYEE", true)) {
                        ref.child(employeeId.toString())
                    } else ref
                }
            locationListener?.let { liveRef.removeEventListener(it) }
            // Release the Firebase SSOT live sync when Admin stops/logs out.
            // While the Admin session is active this path remains keepSynced(true).
            liveRef.keepSynced(false)
            employeeListener?.let { employeesRefForStop(ownerUid).removeEventListener(it) }

            clientEventsRootListener?.let {
                firebaseSync.getGlobalRef().child("client_events").removeEventListener(it)
            }
            clientEventEmployeeListeners.values.forEach { (query, listener) ->
                query.removeEventListener(listener)
            }
            clientEventEmployeeListeners.clear()
        }

        locationListener = null
        employeeListener = null
        clientEventsRootListener = null
        synchronized(ownerEmployeeIds) { ownerEmployeeIds.clear() }
        _ownerEmployees.value = emptyMap()
        lastOwnerLiveLocations = emptyMap()
        applicationJob?.cancel()
        applicationJob = null
        connectionJob?.cancel()
        connectionJob = null
        _liveLocations.value = emptyMap()
        lastSuccessfulLiveReadAt = 0L
        activeOwnerUid = null
    }

    data class SessionEndedEvent(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("sessionId") val sessionId: String?,
        @SerializedName("endReason") val endReason: String? = null
    )

    sealed class SyncEvent {
        object DataChanged : SyncEvent()
        object AttendanceChanged : SyncEvent()
        object PunchChanged : SyncEvent()
        object LocationChanged : SyncEvent()
        data class GeoSettingsChanged(val settings: GeoSettingsChangedEvent) : SyncEvent()
        object LeaveChanged : SyncEvent()
        object AdvanceChanged : SyncEvent()
        object BonusChanged : SyncEvent()
        object TaxDeclarationChanged : SyncEvent()
        object EmployeeChanged : SyncEvent()
        object GlobalRefresh : SyncEvent()
        object ApplicationDataChanged : SyncEvent()
        object RegularizationChanged : SyncEvent()
        object ExitChanged : SyncEvent()
        data class SessionStarted(val employeeId: Int, val sessionId: String) : SyncEvent()
        data class SessionEnded(val employeeId: Int, val sessionId: String?, val endReason: String? = null) : SyncEvent()
    }

    data class SessionStartedEvent(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("sessionId") val sessionId: String
    )

    data class GeoSettingsChangedEvent(
        @SerializedName("officeLatitude") val officeLatitude: Double = 0.0,
        @SerializedName("officeLongitude") val officeLongitude: Double = 0.0,
        @SerializedName("geoRadiusMeters") val geoRadiusMeters: Int = 0
    )

    data class OwnerEmployee(
        val employeeId: Int,
        val name: String,
        val email: String = ""
    )

    data class LiveLocation(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("sessionId") val sessionId: String = "",
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("accuracyMeters") val accuracyMeters: Double,
        @SerializedName("distanceMeters") val distanceMeters: Double,
        @SerializedName("allowedRadiusMeters") val allowedRadiusMeters: Int = 100,
        @SerializedName("isWithinAllowedRadius") val isWithinAllowedRadius: Boolean,
        @SerializedName("timestamp") val timestamp: String? = null,
        @SerializedName("speedMps") val speedMps: Double = 0.0,
        @SerializedName("bearing") val bearing: Double = 0.0,
        @SerializedName("movementState") val movementState: String = "Stopped"
    )

    private data class FirebaseLiveLocation(
        val EmployeeId: Int = 0,
        val SessionId: String = "",
        val Latitude: Double = 0.0,
        val Longitude: Double = 0.0,
        val AccuracyMeters: Double = 0.0,
        val SpeedMps: Double = 0.0,
        val Bearing: Double = 0.0,
        val BatteryLevel: Int = 0,
        val Sequence: Long = 0L,
        val Timestamp: String? = null,
        val LastUpdatedUtc: String? = null,
        val Source: String? = null,
        val DistanceMeters: Double = 0.0,
        val AllowedRadiusMeters: Int = 100,
        val IsWithinAllowedRadius: Boolean = true,
        val MovementState: String = "Stopped"
    )
}
