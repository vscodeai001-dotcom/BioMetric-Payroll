package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_employee_history")
data class LocalEmployeeHistory(
    @PrimaryKey val historyId: String,
    val employeeId: String,
    val version: Int,
    val type: String,
    val salaryType: String,
    val oldValue: Double,
    val newValue: Double,
    val shiftStart: String,
    val shiftEnd: String,
    val breakHours: Double,
    val shift2Start: String?,
    val shift2End: String?,
    val weekendShiftStart: String?,
    val weekendShiftEnd: String?,
    val weekendBreakHours: Double?,
    val weekendShift2Start: String?,
    val weekendShift2End: String?,
    val isBonusEligible: Boolean,
    val isPaidLeaveEligible: Boolean,
    val paidLeaveOnWeekdays: Boolean,
    val paidLeaveOnWeekends: Boolean,
    val rulesOverrideJson: String?,
    val changeDate: Long,
    val effectiveDate: Long,
    val endDate: Long?,
    val changeReason: String?,
    val salaryRate: Double,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
