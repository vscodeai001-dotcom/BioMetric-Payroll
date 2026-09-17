package com.biometric.app.domain.attendance

/**
 * Pure mirror of the Web Employee mobile attendance rules.
 *
 * The Web application remains the authoritative business-calculation engine
 * for GPS-session automatic attendance. Android uses this class for identical
 * visibility/eligibility/state decisions and sends the GPS session to the Web
 * compatibility bridge through Firebase.
 */
object EmployeeAttendanceStateMachine {

    data class FeatureState(
        val geoFencingEnabled: Boolean,
        val dualAttendanceEnabled: Boolean,
        val automaticGeofencePunchingEnabled: Boolean
    ) {
        // Web semantics:
        // features != null && (DualAttendance || !GeoFencing)
        val biometricAttendanceActive: Boolean
            get() = !geoFencingEnabled || dualAttendanceEnabled

        // Web semantics:
        // GeoFencing && AutomaticGeofencePunching
        val automaticGeofenceAttendanceActive: Boolean
            get() = geoFencingEnabled && automaticGeofencePunchingEnabled

        // Web MobilePunchWidget renders the manual button only when neither
        // higher-priority attendance source is active.
        val manualPunchVisible: Boolean
            get() = !biometricAttendanceActive && !automaticGeofenceAttendanceActive
    }

    data class LocationState(
        val hasLocation: Boolean,
        val distanceMeters: Double,
        val allowedRadiusMeters: Int,
        val refreshing: Boolean = false,
        val punching: Boolean = false
    ) {
        // Web GeoLocationService / GeoDistanceResult uses the configured
        // radius directly. No hidden tolerance or Android-only +1m buffer.
        val withinRadius: Boolean
            get() = hasLocation &&
                allowedRadiusMeters > 0 &&
                distanceMeters.isFinite() &&
                distanceMeters <= allowedRadiusMeters
    }

    data class ManualPunchState(
        val feature: FeatureState,
        val location: LocationState
    ) {
        // Mirrors Web CanPunch:
        // hasLocation && isWithinRadius && !isPunching &&
        // !isRefreshingLocation && !isBiometric && !isAutomaticGeofence
        val canPunch: Boolean
            get() = location.hasLocation &&
                location.withinRadius &&
                !location.punching &&
                !location.refreshing &&
                !feature.biometricAttendanceActive &&
                !feature.automaticGeofenceAttendanceActive
    }

    /**
     * Mirrors the Web automatic fallback reconciliation decision.
     *
     * GPS INSIDE + attendance OUT => IN
     * GPS OUTSIDE + attendance IN => OUT
     * Same state => no duplicate punch
     */
    fun requiredAutomaticPunchType(
        currentLocationInside: Boolean,
        attendanceCurrentlyOpen: Boolean
    ): String? {
        if (currentLocationInside == attendanceCurrentlyOpen) return null
        return if (currentLocationInside) "IN" else "OUT"
    }
}
