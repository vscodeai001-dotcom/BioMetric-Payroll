package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_attendance")
data class LocalAttendance(
    @PrimaryKey val attendanceId: String,
    val employeeId: String,
    val checkInTime: Long,
    val checkOutTime: Long?,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
