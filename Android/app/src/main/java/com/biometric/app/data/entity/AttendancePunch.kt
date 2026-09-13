package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.io.Serializable

@Keep
data class AttendancePunch(
    var punchId: String = "",
    var staffId: String = "",
    var date: String = "", // yyyy-MM-dd
    var type: String = "IN", // IN, OUT, BREAK_IN, BREAK_OUT
    var timestamp: Long = 0,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var accuracy: Float = 0f,
    var geofenceId: String? = null,
    var distanceFromGeofence: Double = 0.0,
    var photoId: String? = null,
    var deviceId: String = "",
    var source: String = "GEOFENCE", // GEOFENCE, MANUAL, ADMIN, SYSTEM
    var status: String = "PENDING" // PENDING, APPROVED, REJECTED
) : Serializable
