package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_attendance_punches")
data class LocalAttendancePunch(
    @PrimaryKey val punchId: String,
    val staffId: String,
    val date: String,
    val type: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val source: String,
    val status: String,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
