package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.firebase.database.*
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
    private var locationListener: ValueEventListener? = null

    private val _dataChangeEvents = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 64)
    val dataChangeEvents = _dataChangeEvents.asSharedFlow()

    private val _liveLocations = MutableStateFlow<Map<Int, LiveLocation>>(emptyMap())
    val liveLocations = _liveLocations.asStateFlow()

    @Synchronized
    fun start() {
        if (!sessionStore.isLoggedIn() && !firebaseSync.isAuthenticated()) return
        if (applicationJob?.isActive == true) return

        firebaseSync.startSync()

        applicationJob = managerScope.launch {
            firebaseSync.applicationEventsFlow().collect { event ->
                if (event.changes.isNotEmpty()) {
                    _dataChangeEvents.emit(SyncEvent.GlobalRefresh)
                }
            }
        }

        val ownerUid = firebaseSync.getOwnerUid()
        if (ownerUid.isNullOrBlank()) return
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

                val children = if (snapshot.hasChildren() && snapshot.getValue(FirebaseLiveLocation::class.java) == null) snapshot.children.toList() else listOf(snapshot)
                for (child in children) {
                    val employeeId = (child.key?.toIntOrNull() ?: child.getValue(FirebaseLiveLocation::class.java)?.EmployeeId) ?: continue
                    val value = child.getValue(FirebaseLiveLocation::class.java) ?: continue

                    locations[employeeId] = LiveLocation(
                        employeeId = employeeId,
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

                _liveLocations.value = locations
                _dataChangeEvents.tryEmit(SyncEvent.LocationChanged)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(
                    "SignalRManager",
                    "Firebase live-location listener cancelled",
                    error.toException()
                )
            }
        }

        locationListener = listener
        liveRef.addValueEventListener(listener)

        connectionJob = managerScope.launch {
            firebaseSync.syncStatus.collect { connected ->
                if (connected) {
                    _dataChangeEvents.emit(SyncEvent.GlobalRefresh)
                }
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
                    employeeId = eid, latitude = lat, longitude = lon,
                    accuracyMeters = (map["AccuracyMeters"] as? Number)?.toDouble() ?: 0.0,
                    distanceMeters = (map["DistanceMeters"] as? Number)?.toDouble() ?: 0.0,
                    allowedRadiusMeters = (map["AllowedRadiusMeters"] as? Number)?.toInt() ?: 100,
                    isWithinAllowedRadius = (map["IsWithinAllowedRadius"] as? Boolean) ?: true,
                    timestamp = timestamp,
                    speedMps = (map["SpeedMps"] as? Number)?.toDouble() ?: 0.0,
                    movementState = map["MovementState"]?.toString() ?: "Stopped"
                )
            }
            byKey.values.toList().sortedBy { parseTrackingTimestamp(it.timestamp) }
        }.getOrElse {
            Log.w("SignalRManager", "Historical tracking read failed", it)
            emptyList()
        }
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

    fun stop() {
        val ownerUid = firebaseSync.getOwnerUid()
        val role = sessionStore.userRole().orEmpty()
        val employeeId = sessionStore.employeeId()
        val liveRef = ownerUid?.let {
            firebaseSync.getGlobalRef()
                .child("owners").child(it).child("tracking").child("live")
                .let { ref -> if (role.equals("STAFF", true) || role.equals("EMPLOYEE", true)) ref.child(employeeId.toString()) else ref }
        }

        if (liveRef != null) locationListener?.let { liveRef.removeEventListener(it) }
        locationListener = null

        applicationJob?.cancel()
        applicationJob = null

        connectionJob?.cancel()
        connectionJob = null
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

    data class LiveLocation(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("accuracyMeters") val accuracyMeters: Double,
        @SerializedName("distanceMeters") val distanceMeters: Double,
        @SerializedName("allowedRadiusMeters") val allowedRadiusMeters: Int = 100,
        @SerializedName("isWithinAllowedRadius") val isWithinAllowedRadius: Boolean,
        @SerializedName("timestamp") val timestamp: String? = null,
        @SerializedName("speedMps") val speedMps: Double = 0.0,
        @SerializedName("movementState") val movementState: String = "Stopped"
    )

    private data class FirebaseLiveLocation(
        val EmployeeId: Int = 0,
        val SessionId: String = "",
        val Latitude: Double = 0.0,
        val Longitude: Double = 0.0,
        val AccuracyMeters: Double = 0.0,
        val SpeedMps: Double = 0.0,
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
