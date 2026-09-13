package com.biometric.app.data.entity

data class LocationTrack(
    val locationId: Long = 0,
    val staffId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val batteryLevel: Int,
    val isSynced: Boolean = false
)
