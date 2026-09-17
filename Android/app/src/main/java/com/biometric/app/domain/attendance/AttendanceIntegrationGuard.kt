package com.biometric.app.domain.attendance

/**
 * Final cross-check between tracking state and employee punch eligibility.
 *
 * This class contains no payroll calculation and performs no database writes.
 * It exists to prevent an Android UI race from bypassing the same feature
 * hierarchy used by the Web MobilePunchWidget.
 */
object AttendanceIntegrationGuard {
    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val punchType: String? = null
    )

    fun manualPunchDecision(
        feature: EmployeeAttendanceStateMachine.FeatureState,
        location: EmployeeAttendanceStateMachine.LocationState,
        currentAttendanceOpen: Boolean,
        isClosedDay: Boolean = false
    ): Decision {
        if (isClosedDay) return Decision(false, "Attendance is disabled because today is a shop closed day")
        val state = EmployeeAttendanceStateMachine.ManualPunchState(feature, location)
        if (!feature.manualPunchVisible) {
            return Decision(false, "Manual punching is disabled by the current attendance policy")
        }
        if (!location.hasLocation) return Decision(false, "Current location is unavailable")
        if (!location.withinRadius) return Decision(false, "Device is outside the allowed geofence")
        if (location.refreshing) return Decision(false, "Location is still refreshing")
        if (location.punching) return Decision(false, "Another punch is already being processed")
        if (!state.canPunch) return Decision(false, "Punch is not currently eligible")
        return Decision(
            allowed = true,
            reason = "Manual punch allowed",
            punchType = if (currentAttendanceOpen) "OUT" else "IN"
        )
    }

    /** Automatic geofence punching is a Web-authoritative operation. */
    fun automaticGeofenceDecision(
        feature: EmployeeAttendanceStateMachine.FeatureState,
        inside: Boolean,
        attendanceCurrentlyOpen: Boolean,
        isClosedDay: Boolean = false
    ): Decision {
        if (isClosedDay) return Decision(false, "Automatic attendance is disabled because today is a shop closed day")
        if (!feature.automaticGeofenceAttendanceActive) {
            return Decision(false, "Automatic geofence attendance is disabled")
        }
        return Decision(
            allowed = true,
            reason = "Web-authoritative automatic reconciliation",
            punchType = EmployeeAttendanceStateMachine.requiredAutomaticPunchType(
                inside, attendanceCurrentlyOpen
            )
        )
    }
}
