package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class GeofenceLocation(
    var id: String = "",
    var name: String = "",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var radius: Float = 100f, // Default 100 meters
    var type: String = "SHOP", // SHOP, CUSTOMER, etc.
    var isActive: Boolean = true,
    var createdAt: Long = System.currentTimeMillis()
)
