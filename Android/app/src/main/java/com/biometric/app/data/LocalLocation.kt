package com.biometric.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_locations")
data class LocalLocation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val batteryLevel: Int,
    val timestamp: Long = System.currentTimeMillis()
)
