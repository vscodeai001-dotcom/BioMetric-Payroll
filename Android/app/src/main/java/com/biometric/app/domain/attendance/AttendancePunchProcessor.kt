package com.biometric.app.domain.attendance

import java.util.Calendar

enum class PunchSourceTier(val priority: Int) {
    PhysicalMachine(1),
    ManualAdmin(2),
    GeofenceAuto(3)
}

data class ProcessedPunchItem(
    val id: String = "",
    val staffId: String = "",
    val timestamp: Long = 0L,
    val type: String = "IN",
    val deviceId: String = "",
    val biometricId: String = "",
    val source: String = "",
    val tier: PunchSourceTier = PunchSourceTier.GeofenceAuto
)

data class ProcessedPunchesResult(
    val ordered: List<ProcessedPunchItem>,
    val firstIn: Long?,
    val lastOut: Long?
)

/**
 * 3-Tier Hybrid Attendance Punch Processor for Android.
 * Exact 100% mirror of C# AttendancePunchProcessor.cs in Web.
 *
 * Tier 1: Physical Machine Biometric (ZKTeco / hardware) -> Highest Priority
 * Tier 2: Manual Admin Overrides & Corrections -> Authoritative Override
 * Tier 3: Geofence Auto Punches (Android / Server) -> Dynamic Fallback Only
 */
object AttendancePunchProcessor {

    fun getPunchTier(
        deviceId: String?,
        biometricId: String?,
        logType: String?,
        source: String? = null
    ): PunchSourceTier {
        val dev = deviceId?.trim() ?: ""
        val bio = biometricId?.trim() ?: ""
        val type = logType?.trim() ?: ""
        val src = source?.trim() ?: ""

        // Tier 2: Manual Admin Override or Approved Correction
        if (dev.equals("ManualCorrection", ignoreCase = true) ||
            dev.equals("Admin", ignoreCase = true) ||
            bio.startsWith("MANUAL_", ignoreCase = true) ||
            src.equals("MANUAL", ignoreCase = true) ||
            src.equals("ADMIN", ignoreCase = true) ||
            type.equals("Manual Correction", ignoreCase = true)
        ) {
            return PunchSourceTier.ManualAdmin
        }

        // Tier 3: Geofence Auto (Server GeofenceAuto, AndroidGeofenceAuto, AUTO_* keys)
        if (dev.equals("GeofenceAuto", ignoreCase = true) ||
            dev.equals("AndroidGeofenceAuto", ignoreCase = true) ||
            dev.contains("Geofence", ignoreCase = true) ||
            bio.equals("GEOFENCE_AUTO", ignoreCase = true) ||
            bio.startsWith("AUTO_", ignoreCase = true) ||
            src.equals("GEOFENCE", ignoreCase = true) ||
            type.startsWith("AUTO_", ignoreCase = true)
        ) {
            return PunchSourceTier.GeofenceAuto
        }

        // Tier 1: Physical Biometric Machine
        if (dev.startsWith("ZKTeco", ignoreCase = true) ||
            dev.startsWith("Machine", ignoreCase = true) ||
            dev.toIntOrNull() != null ||
            (dev.isNotBlank() && !dev.equals("MobileWeb", ignoreCase = true) && !dev.equals("Android", ignoreCase = true))
        ) {
            return PunchSourceTier.PhysicalMachine
        }

        // Explicit human button punch on mobile or web app
        return PunchSourceTier.ManualAdmin
    }

    fun isExplicitOutPunch(logType: String?): Boolean {
        val t = logType?.trim()?.uppercase() ?: return false
        return t.contains("OUT") || t == "CHECKOUT" || t == "CHECK_OUT" || t.startsWith("AUTO_OUT")
    }

    fun isExplicitInPunch(logType: String?): Boolean {
        val t = logType?.trim()?.uppercase() ?: return false
        return t == "IN" || t == "CHECKIN" || t == "CHECK_IN" || t.startsWith("AUTO_IN")
    }

    fun processPunches(
        punches: List<ProcessedPunchItem>,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null
    ): ProcessedPunchesResult {
        if (punches.isEmpty()) {
            return ProcessedPunchesResult(emptyList(), null, null)
        }

        // 1. Truncate timestamps to minute precision
        val normalized = punches.map { p ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = p.timestamp
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            p.copy(
                timestamp = cal.timeInMillis,
                tier = getPunchTier(p.deviceId, p.biometricId, p.type, p.source)
            )
        }.sortedWith(
            compareBy<ProcessedPunchItem> { it.timestamp }
                .thenBy { it.tier.priority }
                .thenBy { it.id }
        )

        // 2. Deduplicate punches by BiometricID and same-minute collisions (higher tier wins)
        val deduplicated = mutableListOf<ProcessedPunchItem>()
        for (p in normalized) {
            var isDup = false
            if (p.id.isNotBlank() && deduplicated.any { it.id == p.id }) {
                isDup = true
            }

            if (!isDup && p.biometricId.isNotBlank() &&
                (p.biometricId.startsWith("AUTO_", ignoreCase = true) ||
                 p.biometricId.startsWith("MANUAL_", ignoreCase = true) ||
                 try { java.util.UUID.fromString(p.biometricId); true } catch (_: Exception) { false })) {
                if (deduplicated.any { it.staffId == p.staffId && it.biometricId.equals(p.biometricId, ignoreCase = true) }) {
                    isDup = true
                }
            }

            if (!isDup) {
                val matchIdx = deduplicated.indexOfFirst {
                    it.staffId == p.staffId &&
                    (
                        // 1. Same-minute collision of same direction
                        (it.timestamp == p.timestamp && isExplicitOutPunch(it.type) == isExplicitOutPunch(p.type)) ||
                        // 2. Same-minute collision between different tiers (higher tier wins)
                        (it.timestamp == p.timestamp && it.tier != p.tier) ||
                        // 3. Geofence rapid oscillation / jitter within 3 minutes (180s) for SAME direction punches
                        (Math.abs(it.timestamp - p.timestamp) <= 180_000L &&
                         it.tier == PunchSourceTier.GeofenceAuto &&
                         p.tier == PunchSourceTier.GeofenceAuto &&
                         isExplicitOutPunch(it.type) == isExplicitOutPunch(p.type))
                    )
                }
                if (matchIdx >= 0) {
                    val match = deduplicated[matchIdx]
                    if (p.tier.priority < match.tier.priority) {
                        deduplicated[matchIdx] = p
                    }
                    isDup = true
                }
            }

            if (!isDup) {
                deduplicated.add(p)
            }
        }

        if (deduplicated.isEmpty()) {
            return ProcessedPunchesResult(emptyList(), null, null)
        }

        // 3. Shift Zone & Manual Override Filtering:
        val suppressedIds = mutableSetOf<String>()
        val suppressedBioIds = mutableSetOf<String>()

        // A. Manual Admin Override:
        // When an administrative manual correction pair [manualIn, manualOut] exists,
        // it is an explicit authoritative override of that employee's shift.
        // All intermediate punches (erratic biometric scans, partial breaks, GPS drift)
        // strictly inside (manualIn.timestamp, manualOut.timestamp) are suppressed and invalidated.
        val manualIns = deduplicated.filter { it.tier == PunchSourceTier.ManualAdmin && isExplicitInPunch(it.type) }.sortedBy { it.timestamp }
        val manualOuts = deduplicated.filter { it.tier == PunchSourceTier.ManualAdmin && isExplicitOutPunch(it.type) }.sortedBy { it.timestamp }

        if (manualIns.isNotEmpty() && manualOuts.isNotEmpty()) {
            val mStart = manualIns.first().timestamp
            val mEnd = manualOuts.last().timestamp
            if (mEnd > mStart) {
                for (p in deduplicated) {
                    if (p.tier != PunchSourceTier.ManualAdmin && p.timestamp > mStart && p.timestamp < mEnd) {
                        if (p.id.isNotBlank()) suppressedIds.add(p.id)
                        if (p.biometricId.isNotBlank()) suppressedBioIds.add(p.biometricId.lowercase())
                    }
                }
            }
        }

        // B. Authoritative Session (Tier 1 Machine or Tier 2 Manual):
        // When an authoritative session covers the shift, intermediate GeofenceAuto punches
        // inside [authIn, authOut] are suppressed to prevent GPS drift from fragmenting working hours.
        val authIn = deduplicated.firstOrNull { it.tier.priority <= PunchSourceTier.ManualAdmin.priority && isExplicitInPunch(it.type) }
        val authOut = deduplicated.lastOrNull { it.tier.priority <= PunchSourceTier.ManualAdmin.priority && isExplicitOutPunch(it.type) }

        if (authIn != null && authOut != null && authOut.timestamp > authIn.timestamp) {
            val wStart = authIn.timestamp
            val wEnd = authOut.timestamp

            for (p in deduplicated) {
                if (p.tier == PunchSourceTier.GeofenceAuto) {
                    if (p.timestamp > wStart && p.timestamp < wEnd) {
                        if (p.id.isNotBlank()) suppressedIds.add(p.id)
                        if (p.biometricId.isNotBlank()) suppressedBioIds.add(p.biometricId.lowercase())
                    }
                }
            }
        }

        val survivingPunches = deduplicated.filter { p ->
            !suppressedIds.contains(p.id) &&
            (p.biometricId.isBlank() || !suppressedBioIds.contains(p.biometricId.lowercase()))
        }.sortedWith(
            compareBy<ProcessedPunchItem> { it.timestamp }
                .thenBy { it.tier.priority }
                .thenBy { it.id }
        ).ifEmpty { deduplicated }

        // 4. Dynamic State Machine & Priority Pairing across all zones
        val ordered = mutableListOf<ProcessedPunchItem>()
        var pendingIn: ProcessedPunchItem? = null

        for (p in survivingPunches) {
            val isOut = isExplicitOutPunch(p.type)
            val isIn = isExplicitInPunch(p.type)

            if (pendingIn == null) {
                if (isOut) {
                    // Stray / Redundant OUT:
                    // Only valid if no prior punches have occurred today (e.g. overnight completion).
                    // If an IN->OUT session has completed, employee is ALREADY clocked out. Redundant OUTs are discarded.
                    if (ordered.isEmpty()) {
                        ordered.add(p)
                    } else if (ordered.size % 2 == 0) {
                        val lastOutTier = ordered.last().tier
                        val incomingTier = p.tier
                        if (incomingTier.priority < lastOutTier.priority && p.timestamp >= ordered[ordered.size - 2].timestamp) {
                            ordered[ordered.size - 1] = p
                        }
                    }
                } else {
                    pendingIn = p // Valid IN
                }
            } else {
                if (isIn) {
                    // Consecutive IN:
                    // If pendingIn was an early arrival (pre-shift OT) and p is shift start, keep pendingIn
                    if (shiftStartMs != null && pendingIn.timestamp < shiftStartMs &&
                        p.timestamp >= shiftStartMs && p.timestamp <= shiftStartMs + 30 * 60 * 1000L
                    ) {
                        // Keep pendingIn early arrival
                    } else {
                        // Higher priority tier wins
                        if (p.tier.priority < pendingIn.tier.priority) {
                            pendingIn = p
                        }
                    }
                } else if (isOut) {
                    // Explicit OUT -> forms an attendance pair!
                    ordered.add(pendingIn)
                    ordered.add(p)
                    pendingIn = null
                }
            }
        }

        if (pendingIn != null) {
            ordered.add(pendingIn)
        }

        if (ordered.isEmpty()) {
            return ProcessedPunchesResult(emptyList(), null, null)
        }

        val firstIn = ordered.firstOrNull()?.timestamp
        val lastOut = if (ordered.size >= 2 && ordered.size % 2 == 0) {
            ordered.lastOrNull()?.timestamp
        } else {
            null
        }

        return ProcessedPunchesResult(ordered, firstIn, lastOut)
    }
}
