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
    // Firebase Auth can restore before the persisted owner UID is available.
    // Retry startup briefly so Admin realtime does not require logout/login.
    private var startRetryJob: Job? = null
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
        // Authoritative tenant check: Render GPS nodes that belong to the
        // current owner. We prioritized the employee master directory before,
        // but now we trust all positive IDs from the owner's live tracking node.
        val filtered = raw
            .filterKeys { it > 0 }
            .filterValues { it.sessionId.isNotBlank() }

        _liveLocations.value = filtered
        _dataChangeEvents.tryEmit(SyncEvent.LocationChanged)
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
        if (applicationJob?.isActive == true && activeOwnerUid == ownerUid) return
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

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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

                // LIVE is authoritative only when the corresponding durable
                // Firebase GPS session is ACTIVE. A stale live node can survive
                // briefly after logout/session termination, so never let the map
                // infer LIVE from a GPS point alone. Validate every live marker
                // against owners/{ownerUid}/tracking/sessions/{employeeId}/{sessionId}.
                val validationOwnerUid = ownerUid
                val generation = ++liveSnapshotGeneration
                val previousKnown = lastOwnerLiveLocations

                managerScope.launch {
                    val activeLocations = mutableMapOf<Int, LiveLocation>()

                    for ((employeeId, location) in locations) {
                        if (!isActive) break

                        val stateResult = runCatching {
                            firebaseSync.getGlobalRef()
                                .child("owners")
                                .child(validationOwnerUid)
                                .child("tracking")
                                .child("sessions")
                                .child(employeeId.toString())
                                .child(location.sessionId)
                                .get()
                                .await()
                                .child("State")
                                .getValue(String::class.java)
                        }

                        val state = stateResult.getOrNull()

                        when {
                            state.equals("ACTIVE", ignoreCase = true) -> {
                                activeLocations[employeeId] = location
                            }

                            state.equals("ENDED", ignoreCase = true) ||
                                state.equals("OFFLINE", ignoreCase = true) -> {
                                // Confirmed terminal state. Remove from LIVE.
                            }

                            else -> {
                                // Transient read/auth/network failure. Preserve
                                // the last known-good ACTIVE session when the
                                // employee/session still match.
                                val previous = previousKnown[employeeId]

                                if (previous != null &&
                                    previous.sessionId.equals(
                                        location.sessionId,
                                        ignoreCase = true
                                    )
                                ) {
                                    activeLocations[employeeId] = previous
                                }
                            }
                        }
                    }

                    if (activeOwnerUid == validationOwnerUid &&
                        generation == liveSnapshotGeneration) {
                        lastOwnerLiveLocations = activeLocations.toMap()
                        withContext(Dispatchers.Main.immediate) {
                            publishOwnerScopedLocations(lastOwnerLiveLocations)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("SignalRManager", "Firebase live-location listener cancelled: ${error.message}")
                }
            }
        }

        locationListener = listener
        liveRef.addValueEventListener(listener)

        // Primary channel: Firebase ValueEventListener.
        // Safety net: periodically read the exact same owner-scoped Firebase
        // node so the Android Admin map converges automatically after any
        // listener/process/network edge case. No manual Refresh is required.
        reconciliationJob?.cancel()
        reconciliationJob = managerScope.launch {
            while (isActive && activeOwnerUid == ownerUid) {
                delay(5000L)
                if (!isActive || activeOwnerUid != ownerUid) break

                runCatching {
                    val snapshot = liveRef.get().await()
                    withContext(Dispatchers.Main.immediate) {
                        listener.onDataChange(snapshot)
                    }
                }.onFailure { error ->
                    Log.d(
                        "SignalRManager",
                        "Live-location reconciliation skipped: ${error.message}"
                    )
                }
            }
        }

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
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("SignalRManager", "Owner employee binding listener cancelled: ${error.message}")
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
    fun reconcileLiveLocationsNow() {
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
            // Employee Android changes are invalidation events. Reconcile the
            // canonical owner-scoped live node immediately so an already-open
            // Admin dashboard converges without logout/login.
            reconcileLiveLocationsNow()
            _dataChangeEvents.tryEmit(SyncEvent.GlobalRefresh)
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

        return FirebaseLiveLocation(
            EmployeeId = int("EmployeeId", "employeeId"),
            SessionId = string("SessionId", "sessionId"),
            Latitude = double("Latitude", "latitude"),
            Longitude = double("Longitude", "longitude"),
            AccuracyMeters = double("AccuracyMeters", "accuracyMeters"),
            DistanceMeters = double("DistanceMeters", "distanceMeters"),
            AllowedRadiusMeters = int("AllowedRadiusMeters", "allowedRadiusMeters"),
            IsWithinAllowedRadius = bool("IsWithinAllowedRadius", "isWithinAllowedRadius"),
            Timestamp = string("Timestamp", "timestamp"),
            SpeedMps = double("SpeedMps", "speedMps"),
            Bearing = double("Bearing", "bearing"),
            MovementState = string("MovementState", "movementState")
        )
    }

    private fun parseTrackingTimestamp(value: String?): Long = runCatching {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")
        patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    if (!pattern.endsWith("X")) timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value ?: "")?.time
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
        startRetryJob?.cancel()
        startRetryJob = null
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
