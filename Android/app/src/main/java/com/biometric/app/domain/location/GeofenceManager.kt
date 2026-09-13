package com.biometric.app.domain.location

import com.biometric.app.data.entity.GeofenceLocation
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class GeofenceManager @Inject constructor() {

    companion object {
        private const val EARTH_RADIUS = 6371000.0 // in meters
        private const val MIN_ACCURACY = 50.0 // 50 meters
    }

    data class ValidationResult(
        val isInside: Boolean,
        val nearestGeofence: GeofenceLocation? = null,
        val distance: Double = Double.MAX_VALUE
    )

    fun validatePunchLocation(
        currentLat: Double, currentLon: Double,
        accuracy: Float, geofences: List<GeofenceLocation>
    ): ValidationResult {
        if (accuracy > MIN_ACCURACY) return ValidationResult(false)

        var nearest: GeofenceLocation? = null
        var minDistance = Double.MAX_VALUE

        for (g in geofences) {
            val distance = calculateDistance(currentLat, currentLon, g.latitude, g.longitude)
            if (distance < minDistance) {
                minDistance = distance
                nearest = g
            }
        }

        val isInside = nearest != null && minDistance <= nearest.radius
        return ValidationResult(isInside, nearest, minDistance)
    }

    fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }
}
