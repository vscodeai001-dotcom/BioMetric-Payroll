package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class LocalDailySummary(
    @PrimaryKey val summaryId: Int,
    val employeeId: Int,
    val shiftDate: String, // yyyy-MM-dd
    val status: String,
    val earnedStandardHours: Double,
    val totalOvertimeMs: Long,
    val totalPenaltyMs: Long,
    val totalLatenessMs: Long,
    val totalBreakPenaltyMs: Long,
    val scheduledShiftDurationMs: Long,
    val shiftAllowanceEarned: Double,
    val isManualOverride: Boolean,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
