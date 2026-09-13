package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class LocationVisit(
    val visitId: Long = 0,
    val staffId: String,
    val locationId: String?,
    val arrivalTime: Long,
    val departureTime: Long,
    val duration: Long, // in minutes
    val latitude: Double,
    val longitude: Double,
    val shopName: String? = null
)
