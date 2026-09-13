package com.biometric.app.domain.location

import android.location.Location
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationQualityManager @Inject constructor() {

    /**
     * Detects if the location is spoofed or has poor accuracy.
     */
    fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    /**
     * Checks for suspicious jumps in location (impossible speed).
     */
    fun isSuspiciousMovement(lastLoc: Location?, currentLoc: Location): Boolean {
        if (lastLoc == null) return false
        
        val distance = lastLoc.distanceTo(currentLoc)
        val timeDeltaSeconds = (currentLoc.time - lastLoc.time) / 1000.0
        
        if (timeDeltaSeconds <= 0) return false
        
        val speedKmh = (distance / timeDeltaSeconds) * 3.6
        // If speed > 300 km/h, it's likely a spoof or a bug
        return speedKmh > 300.0
    }
}
