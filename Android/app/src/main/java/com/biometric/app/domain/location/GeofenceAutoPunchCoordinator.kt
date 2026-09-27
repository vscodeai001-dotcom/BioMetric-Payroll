package com.biometric.app.domain.location

import android.location.Location
import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.LocalShopClosedDayDao
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.LocalAttendancePunch
import com.biometric.app.domain.attendance.AttendancePolicyRepository
import com.biometric.app.sync.FirebaseSyncManager
import kotlinx.coroutines.flow.firstOrNull
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
 * - Respects company_settings (officeLat, officeLon, radius) and feature_settings (enableGeoFencing, enableAutomaticGeofencePunching)
 * - Respects shop_closed_days
 * - Writes to both attendance_punches (SSOT) and attendance (sessions) in Firebase RTDB
 */
@Singleton
class GeofenceAutoPunchCoordinator @Inject constructor(
    private val sessionStore: MobileSessionStore,
    private val attendancePolicy: AttendancePolicyRepository,
    private val localAttendancePunchDao: LocalAttendancePunchDao,
    private val closedDayDao: LocalShopClosedDayDao,
    private val firebaseSync: FirebaseSyncManager
) {
    private val evalMutex = Mutex()
    private var lastEvaluatedInside: Boolean? = null
    private var lastPunchTimeMs: Long = 0L

    companion object {
        private const val TAG = "GeofenceAutoPunch"
        private const val DEBOUNCE_MS = 60_000L
        private val istTimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    suspend fun evaluateAutoPunch(location: Location) {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return

        val policy = attendancePolicy.observe().firstOrNull()?.normalized() ?: return
        if (!policy.geoFencingEnabled || !policy.automaticGeofencePunchingEnabled) return
        if (policy.geoRadiusMeters <= 0 || policy.officeLatitude == 0.0 || policy.officeLongitude == 0.0) return

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

            // Load existing punches for today from Room SSOT
            val staffIdStr = employeeId.toString()
            val allPunches = runCatching { localAttendancePunchDao.getAll() }.getOrDefault(emptyList())
            val todaysPunches = allPunches.filter { p ->
                (p.staffId == staffIdStr || p.staffId.toIntOrNull() == employeeId) &&
                (p.date == todayStr || (p.timestamp > 0 && sdf.format(Date(p.timestamp)) == todayStr))
            }.sortedBy { it.timestamp }

            val lastPunch = todaysPunches.lastOrNull()
            val attendanceCurrentlyOpen = lastPunch?.type.equals("IN", ignoreCase = true)

            // INSIDE -> INSIDE rollover:
            // If employee stayed inside across midnight (12:00 AM) into a new calendar day,
            // today's business day has ZERO punches recorded.
            // If today already has punches, INSIDE -> INSIDE is a no-op.
            if (!isTransition && !isInitialInside) {
                if (todaysPunches.isNotEmpty()) {
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
