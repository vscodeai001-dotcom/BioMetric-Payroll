package com.biometric.app.domain.location

import android.location.Location
import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalAttendanceDao
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.LocalShopClosedDayDao
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.LocalAttendancePunch
import com.biometric.app.domain.attendance.AttendancePolicyRepository
import com.biometric.app.sync.FirebaseSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates and executes automatic geofence attendance punches independently on Android.
 *
 * Ensures 100% standalone reliability:
 * - OUTSIDE -> INSIDE = IN punch (if attendance parity is OUT)
 * - INSIDE  -> OUTSIDE = OUT punch (if attendance parity is IN)
 * - INSIDE  -> INSIDE  = IN punch if today has 0 punches (midnight rollover for single-day shift)
 * - Debounce protection (minimum 60s between auto punches)
 * - 5-minute IN guard: never creates a second IN within 5 minutes of any existing IN
 * - Respects company_settings (officeLat, officeLon, radius) and feature_settings
 *   (enableGeoFencing, enableAutomaticGeofencePunching)
 * - Respects shop_closed_days
 * - Queries BOTH attendance_punches AND attendance (sessions) tables in Room
 * - Normalises all punch-type aliases (IN/CHECKIN/CHECK_IN/PUNCH/AUTO_IN → IN, etc.)
 * - Writes to both attendance_punches (SSOT) and attendance (sessions) in Firebase RTDB
 */
@Singleton
class GeofenceAutoPunchCoordinator @Inject constructor(
    private val sessionStore: MobileSessionStore,
    private val attendancePolicy: AttendancePolicyRepository,
    private val localAttendancePunchDao: LocalAttendancePunchDao,
    private val localAttendanceDao: LocalAttendanceDao,
    private val closedDayDao: LocalShopClosedDayDao,
    private val firebaseSync: FirebaseSyncManager
) {
    private val evalMutex = Mutex()
    private var lastEvaluatedInside: Boolean? = null
    private var lastPunchTimeMs: Long = 0L
    private var lastLocation: Location? = null

    /**
     * Track last known radius to detect admin geofence radius changes.
     * When the radius changes, [lastEvaluatedInside] is reset so the next GPS
     * fix re-evaluates the employee's position against the new boundary and
     * fires an auto punch if the inside/outside status changed.
     */
    private var lastKnownRadiusMeters: Int = 0

    /**
     * Hot StateFlow of the current attendance policy — avoids attaching a new
     * Firebase ValueEventListener on every GPS fix. The flow stays active for
     * the lifetime of the singleton (eagerly started).
     *
     * Because [AttendancePolicyRepository.observe] now emits the Room-cached
     * policy immediately even when ownerRef() is null and never closes the
     * channel, this StateFlow will always have a non-null initial value once
     * Room has been hydrated from Firebase at least once.
     */
    private val policyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedPolicy: StateFlow<AttendancePolicyRepository.Policy?> =
        attendancePolicy.observe()
            .stateIn(
                scope = policyScope,
                started = SharingStarted.Eagerly,
                initialValue = null
            )

    init {
        policyScope.launch {
            cachedPolicy.collect { policy ->
                val p = policy?.normalized()
                if (p != null && p.geoRadiusMeters > 0 && p.officeLatitude != 0.0) {
                    val currentRadius = p.geoRadiusMeters
                    if (lastKnownRadiusMeters != 0 && lastKnownRadiusMeters != currentRadius) {
                        Log.i(TAG, "Policy radius changed ($lastKnownRadiusMeters -> $currentRadius m). Triggering immediate re-evaluation.")
                        evalMutex.withLock { lastEvaluatedInside = null }
                        lastKnownRadiusMeters = currentRadius
                        lastLocation?.let { loc ->
                            evaluateAutoPunch(loc)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceAutoPunch"
        private const val DEBOUNCE_MS = 60_000L
        // 10-minute guard (increased from 5 min) to handle app/service restarts
        // where lastPunchTimeMs resets to 0. Covers worst-case sync delay scenario.
        private const val DUAL_PUNCH_GUARD_MS = 10 * 60 * 1_000L
        private val istTimeZone = TimeZone.getTimeZone("Asia/Kolkata")

        // ---------------------------------------------------------------------------
        // Punch-type normalisation helpers
        // Covers all aliases used by Web, Android, biometric machine, and Firebase
        // sync to prevent "dual IN" from a type-mismatch false negative.
        // ---------------------------------------------------------------------------
        /**
         * Returns true when [type] represents any form of check-IN punch.
         * Handles: IN, CHECKIN, CHECK_IN, PUNCH (default from Web manual punch),
         * AUTO_IN (our own geofence auto-punch type prefix).
         */
        fun isCheckInType(type: String?): Boolean {
            val t = type?.trim()?.uppercase() ?: return false
            return t == "IN" || t == "CHECKIN" || t == "CHECK_IN" ||
                t == "PUNCH" || t.startsWith("AUTO_IN")
        }

        /**
         * Returns true when [type] represents any form of check-OUT punch.
         */
        fun isCheckOutType(type: String?): Boolean {
            val t = type?.trim()?.uppercase() ?: return false
            return t == "OUT" || t == "CHECKOUT" || t == "CHECK_OUT" ||
                t.startsWith("AUTO_OUT")
        }
    }

    suspend fun evaluateAutoPunch(location: Location) {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return
        lastLocation = location

        // Resolve policy — prefer the hot StateFlow cache; fall back to Room if the
        // cached value is still null (race between Eagerly stateIn and cold-start)
        // or has zeroed-out coordinates (Firebase not yet responded).
        val policy = run {
            val cached = cachedPolicy.value?.normalized()
            if (cached != null && cached.geoRadiusMeters > 0 && cached.officeLatitude != 0.0) {
                cached
            } else {
                val local = runCatching { attendancePolicy.readFromLocal() }.getOrNull()
                if (local != null && local.geoRadiusMeters > 0 && local.officeLatitude != 0.0) {
                    local
                } else {
                    cached ?: return // truly nothing available yet
                }
            }
        }

        if (!policy.geoFencingEnabled || !policy.automaticGeofencePunchingEnabled) return
        if (policy.geoRadiusMeters <= 0 || policy.officeLatitude == 0.0 || policy.officeLongitude == 0.0) return

        // If the admin changed the geofence radius, reset inside/outside state so
        // the next GPS fix re-evaluates against the new boundary and fires a punch
        // if the effective side changed (e.g. 430m→1430m moves employee inside).
        val currentRadius = policy.geoRadiusMeters
        if (lastKnownRadiusMeters != 0 && lastKnownRadiusMeters != currentRadius) {
            Log.i(TAG, "Geofence radius changed ($lastKnownRadiusMeters → $currentRadius m). Resetting inside/outside state.")
            evalMutex.withLock { lastEvaluatedInside = null }
        }
        lastKnownRadiusMeters = currentRadius

        val nowMs = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istTimeZone }
        val todayStr = sdf.format(Date(nowMs))

        // Check shop closed days
        val closedDays = runCatching { closedDayDao.getAll() }.getOrDefault(emptyList())
        val isClosed = closedDays.any { cd ->
            val cdDateStr = sdf.format(Date(cd.date))
            cdDateStr == todayStr && (cd.affectedEmployeeIds.isEmpty() || cd.affectedEmployeeIds.contains(employeeId.toString()))
        }
        if (isClosed) {
            Log.d(TAG, "Skipping auto-punch: today ($todayStr) is a shop closed day")
            return
        }

        // Calculate distance to office
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            policy.officeLatitude,
            policy.officeLongitude,
            results
        )
        val distanceMeters = results[0]
        val isInside = distanceMeters <= policy.geoRadiusMeters

        evalMutex.withLock {
            val staffIdStr = employeeId.toString()

            // 1. Raw punches from attendance_punches
            val allPunches = runCatching { localAttendancePunchDao.getAll() }.getOrDefault(emptyList())
            val todaysPunches = allPunches.filter { p ->
                (p.staffId == staffIdStr || p.staffId.toIntOrNull() == employeeId) &&
                (p.date == todayStr || (p.timestamp > 0 && sdf.format(Date(p.timestamp)) == todayStr))
            }.sortedBy { it.timestamp }

            // 2. Session records from attendance (open = checkOutTime is null/0)
            val allSessions = runCatching { localAttendanceDao.getAll() }.getOrDefault(emptyList())
            val todaySessions = allSessions.filter { s ->
                (s.employeeId == staffIdStr || s.employeeId.toIntOrNull() == employeeId) &&
                s.checkInTime > 0 && sdf.format(Date(s.checkInTime)) == todayStr
            }.sortedBy { s -> s.checkInTime }

            // Determine the most recent IN/OUT state using normalised types.
            // Priority: last raw punch from attendance_punches; then infer from sessions.
            val lastPunch = todaysPunches.lastOrNull()
            val attendanceCurrentlyOpen: Boolean = when {
                lastPunch != null -> isCheckInType(lastPunch.type)
                todaySessions.isNotEmpty() -> {
                    // A session without a check-out time means attendance is still open
                    val lastSession = todaySessions.last()
                    (lastSession.checkOutTime == null || lastSession.checkOutTime == 0L)
                }
                else -> false
            }

            // Parity check: geofence state must not match current attendance state
            // (e.g. inside and already IN -> no-op; outside and already OUT -> no-op)
            if (isInside == attendanceCurrentlyOpen) {
                lastEvaluatedInside = isInside
                return
            }

            // -------------------------------------------------------------------
            // 5-minute IN guard — prevent a second IN punch within 5 minutes of
            // any existing IN, regardless of which device created it.
            //
            // Uses BOTH Room-persisted punches AND the in-memory lastPunchTimeMs
            // to handle the race where Room hasn't committed the first punch yet
            // when a second GPS fix arrives (e.g. 11:33 → 11:34 scenario).
            // -------------------------------------------------------------------
            if (isInside) {
                val lastInTimeRoom = todaysPunches
                    .filter { isCheckInType(it.type) }
                    .maxOfOrNull { it.timestamp }
                    ?: todaySessions
                        .maxOfOrNull { it.checkInTime }
                        ?: 0L
                // In-memory guard: lastPunchTimeMs is set immediately on punch,
                // before Room async write completes.
                val lastInTime = maxOf(lastInTimeRoom, lastPunchTimeMs)
                if (lastInTime > 0 && (nowMs - lastInTime) < DUAL_PUNCH_GUARD_MS) {
                    Log.d(TAG, "Dual-IN guard: last IN was ${(nowMs - lastInTime) / 1000}s ago (< 10 min), skipping auto IN for employee $staffIdStr")
                    lastEvaluatedInside = true
                    return
                }
            } else {
                // -------------------------------------------------------------------
                // Dual-OUT guard: cross-device safety net.
                // Prevent duplicate OUT punch within 10 minutes of any existing OUT,
                // regardless of whether Android or Web server recorded it first.
                // Re-query Room fresh here so we catch any punch written after mutex
                // entry (async write lag from previous GPS fix).
                // -------------------------------------------------------------------
                val freshPunches = runCatching { localAttendancePunchDao.getAll() }.getOrDefault(emptyList())
                    .filter { p ->
                        (p.staffId == staffIdStr || p.staffId.toIntOrNull() == employeeId) &&
                        (p.date == todayStr || (p.timestamp > 0 && sdf.format(Date(p.timestamp)) == todayStr))
                    }
                val lastOutTimeRoom = freshPunches
                    .filter { isCheckOutType(it.type) }
                    .maxOfOrNull { it.timestamp }
                    ?: 0L
                val lastOutTime = maxOf(lastOutTimeRoom, lastPunchTimeMs)
                if (lastOutTime > 0 && (nowMs - lastOutTime) < DUAL_PUNCH_GUARD_MS) {
                    Log.d(TAG, "Dual-OUT guard: last OUT was ${(nowMs - lastOutTime) / 1000}s ago (< 10 min), skipping auto OUT for employee $staffIdStr")
                    lastEvaluatedInside = false
                    return
                }
            }

            // Debounce check: minimum 60s between punches
            val elapsedSinceLast = nowMs - (lastPunch?.timestamp?.takeIf { it > 0 } ?: lastPunchTimeMs)
            if (elapsedSinceLast < DEBOUNCE_MS) {
                Log.d(TAG, "Debounce: only ${elapsedSinceLast}ms since last punch, skipping auto punch")
                return
            }

            val punchType = if (isInside) "IN" else "OUT"
            val punchId = "AUTO_${employeeId}_${nowMs}"

            val punch = AttendancePunch(
                punchId = punchId,
                staffId = staffIdStr,
                date = todayStr,
                type = punchType,
                timestamp = nowMs,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                distanceFromGeofence = distanceMeters.toDouble(),
                deviceId = "AndroidGeofenceAuto",
                source = "GEOFENCE_AUTO",
                status = "APPROVED"
            )

            // 1. Immediately store in local Room
            val local = LocalAttendancePunch(
                punchId = punchId,
                staffId = staffIdStr,
                date = todayStr,
                type = punchType,
                timestamp = nowMs,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                source = "GEOFENCE_AUTO",
                status = "APPROVED",
                syncState = 0,
                lastModified = nowMs
            )
            localAttendancePunchDao.upsert(local)

            // 2. Publish directly to Firebase RTDB (attendance_punches & attendance nodes)
            firebaseSync.pushAttendancePunch(punch)

            lastEvaluatedInside = isInside
            lastPunchTimeMs = nowMs
            Log.i(TAG, "Automatic geofence $punchType punch recorded for employee $staffIdStr (dist: ${distanceMeters.toInt()}m)")
        }
    }

    /**
     * Admin Rebaseline: When admin changes geofence radius/coordinates on Android,
     * immediately evaluates all active employee live locations from Firebase SSOT.
     * If an employee is now outside the new radius and currently has an open IN punch,
     * an automatic OUT punch is recorded directly to Firebase and Room.
     * Guarantees 100% standalone reliability without needing Web admin to be logged in.
     */
    suspend fun rebaselineAllActiveEmployees(officeLat: Double, officeLon: Double, radiusMeters: Int) {
        if (radiusMeters <= 0 || officeLat == 0.0 || officeLon == 0.0) return
        val owner = firebaseSync.getOwnerRef() ?: return
        val nowMs = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istTimeZone }
        val todayStr = sdf.format(Date(nowMs))

        withContext(Dispatchers.IO) {
            runCatching {
                val liveSnap = owner.child("tracking").child("live").get().await()
                if (!liveSnap.exists()) return@runCatching

                for (child in liveSnap.children) {
                    val empId = child.child("EmployeeId").value?.toString()?.toIntOrNull()
                        ?: child.child("employeeId").value?.toString()?.toIntOrNull()
                        ?: child.key?.toIntOrNull()
                        ?: continue
                    val lat = child.child("Latitude").value?.toString()?.toDoubleOrNull()
                        ?: child.child("latitude").value?.toString()?.toDoubleOrNull()
                        ?: continue
                    val lon = child.child("Longitude").value?.toString()?.toDoubleOrNull()
                        ?: child.child("longitude").value?.toString()?.toDoubleOrNull()
                        ?: continue

                    val results = FloatArray(1)
                    Location.distanceBetween(lat, lon, officeLat, officeLon, results)
                    val distanceMeters = results[0]
                    val isInside = distanceMeters <= radiusMeters

                    if (!isInside) {
                        val staffIdStr = empId.toString()
                        val punchesSnap = owner.child("attendance_punches")
                            .orderByChild("staffId").equalTo(staffIdStr).get().await()

                        var lastPunchType: String? = null
                        var lastPunchTime: Long = 0L
                        if (punchesSnap.exists()) {
                            for (p in punchesSnap.children) {
                                val pDate = p.child("date").value?.toString() ?: ""
                                val pTime = p.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L
                                if (pDate == todayStr || (pTime > 0 && sdf.format(Date(pTime)) == todayStr)) {
                                    if (pTime >= lastPunchTime) {
                                        lastPunchTime = pTime
                                        lastPunchType = p.child("type").value?.toString()
                                    }
                                }
                            }
                        }

                        if (isCheckInType(lastPunchType)) {
                            if (nowMs - lastPunchTime < 30_000L) continue

                            val punchId = "AUTO_${empId}_${nowMs}"
                            val punch = AttendancePunch(
                                punchId = punchId,
                                staffId = staffIdStr,
                                date = todayStr,
                                type = "OUT",
                                timestamp = nowMs,
                                latitude = lat,
                                longitude = lon,
                                accuracy = 10f,
                                distanceFromGeofence = distanceMeters.toDouble(),
                                deviceId = "AdminGeofenceRebaseline",
                                source = "GEOFENCE_AUTO",
                                status = "APPROVED"
                            )
                            val local = LocalAttendancePunch(
                                punchId = punchId,
                                staffId = staffIdStr,
                                date = todayStr,
                                type = "OUT",
                                timestamp = nowMs,
                                latitude = lat,
                                longitude = lon,
                                accuracy = 10f,
                                source = "GEOFENCE_AUTO",
                                status = "APPROVED",
                                syncState = 0,
                                lastModified = nowMs
                            )
                            localAttendancePunchDao.upsert(local)
                            firebaseSync.pushAttendancePunch(punch)
                            Log.i(TAG, "Rebaseline: Created auto OUT punch for employee $empId (dist: ${distanceMeters.toInt()}m > $radiusMeters m)")
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "Rebaseline active employees failed: ${it.message}", it)
            }
        }
    }
}
