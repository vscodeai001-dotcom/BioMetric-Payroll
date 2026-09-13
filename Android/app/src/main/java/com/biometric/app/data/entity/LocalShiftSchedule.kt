package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shift_schedules")
data class LocalShiftSchedule(
    @PrimaryKey val scheduleId: Int,
    val employeeId: Int,
    val shiftDate: String, // yyyy-MM-dd
    val startTime: String, // HH:mm:ss
    val endTime: String,   // HH:mm:ss
    val isRecurringPattern: Boolean,
    val patternDurationDays: Int,
    val appliesToDayOfWeek: Int, // 0=Sunday, 6=Saturday
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
