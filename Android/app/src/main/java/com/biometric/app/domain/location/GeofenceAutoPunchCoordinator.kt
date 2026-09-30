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

    companion object {
        private const val TAG = "GeofenceAutoPunch"
        private const val DEBOUNCE_MS = 60_000L
        private const val DUAL_IN_GUARD_MS = 5 * 60 * 1_000L // 5 minutes
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
            val prevInside = lastEvaluatedInside
            val isTransition = prevInside != null && prevInside != isInside
            val isInitialInside = prevInside == null && isInside

            // OUTSIDE -> OUTSIDE is an immediate no-op
            if (!isTransition && !isInitialInside && !isInside) {
                lastEvaluatedInside = false
                return
            }

            // -----------------------------------------------------------------------
            // Load existing punches for today from BOTH Room tables.
            //
            // attendance_punches — individual raw punch records (primary SSOT)
            // attendance         — session records (checkInTime / checkOutTime)
            //
            // Combining both prevents a false "no open session" diagnosis when a
            // punch originated on Web or machine and was only written to one table.
            // -----------------------------------------------------------------------
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

            // INSIDE -> INSIDE rollover:
            // If employee stayed inside across midnight (12:00 AM) into a new calendar day,
            // today's business day has ZERO punches recorded.
            // If today already has punches, INSIDE -> INSIDE is a no-op.
            if (!isTransition && !isInitialInside) {
                if (todaysPunches.isNotEmpty() || todaySessions.isNotEmpty()) {
                    lastEvaluatedInside = true
                    return
                }
            }

            // Initial OUT fix never creates an OUT punch
            if (!isInside && prevInside == null) {
                lastEvaluatedInside = false
                return
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
            // This is the cross-device dual-IN fence:
            //   Web creates IN → Android sees state as open (parity check above
            //   normally catches this), but if types differed (e.g. "Punch" vs "IN"),
            //   we still block here.
            // -------------------------------------------------------------------
            if (isInside) {
                val lastInTime = todaysPunches
                    .filter { isCheckInType(it.type) }
                    .maxOfOrNull { it.timestamp }
                    ?: todaySessions
                        .maxOfOrNull { it.checkInTime }
                        ?: 0L
                if (lastInTime > 0 && (nowMs - lastInTime) < DUAL_IN_GUARD_MS) {
                    Log.d(TAG, "Dual-IN guard: last IN was ${(nowMs - lastInTime) / 1000}s ago (< 5 min), skipping auto IN for employee $staffIdStr")
                    lastEvaluatedInside = true
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
}
