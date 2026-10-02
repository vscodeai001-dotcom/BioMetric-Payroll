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
        logType: String?
    ): PunchSourceTier {
        val dev = deviceId?.trim() ?: ""
        val bio = biometricId?.trim() ?: ""
        val type = logType?.trim() ?: ""

        // Tier 2: Manual Admin Override or Approved Correction
        if (dev.equals("ManualCorrection", ignoreCase = true) ||
            dev.equals("Admin", ignoreCase = true) ||
            bio.startsWith("MANUAL_", ignoreCase = true) ||
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
                tier = getPunchTier(p.deviceId, p.biometricId, p.type)
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
            if (p.biometricId.isNotBlank()) {
                if (deduplicated.any { it.staffId == p.staffId && it.biometricId.equals(p.biometricId, ignoreCase = true) }) {
                    isDup = true
                }
            }

            if (!isDup) {
                val matchIdx = deduplicated.indexOfFirst {
                    it.staffId == p.staffId && it.timestamp == p.timestamp
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

        // 3. Shift Zone Filtering:
        // When an authoritative session (Tier 1 Machine or Tier 2 Manual) covers the shift
        // (both authoritative IN and authoritative OUT exist), intermediate GeofenceAuto punches
        // inside [authIn, authOut] are suppressed to prevent GPS drift from fragmenting working hours.
        val suppressedIds = mutableSetOf<String>()
        val suppressedBioIds = mutableSetOf<String>()

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
                    ordered.add(p) // Stray OUT
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
                } else {
                    // Explicit OUT or neutral second punch -> forms an attendance pair!
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
