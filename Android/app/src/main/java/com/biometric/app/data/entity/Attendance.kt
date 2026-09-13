package com.biometric.app.data.entity

import java.io.Serializable

data class Attendance(
    var attendanceId: String = "",
    var employeeId: String = "",
    var shopId: String = "",
    var checkInTime: Long = 0,
    var checkOutTime: Long? = null,
    var type: String = "WORK", // WORK, GAP
    var hoursWorked: Double = 0.0,
    var shiftStart: String = "10:00",
    var shiftEnd: String = "22:00",
    var shift2Start: String? = null,
    var shift2End: String? = null,
    var breakHours: Double = 0.0,
    var salaryType: String = "MONTHLY_FIXED",
    var salaryRate: Double = 0.0,
    var note: String? = null,
    var synced: Boolean = false,
    var lateDeduction: Double = 0.0,
    var otHours: Double = 0.0,
    var createdAt: Long = System.currentTimeMillis(),
    var syncState: Int = 0,
    var lastModified: Long = System.currentTimeMillis()
) : Serializable
