package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class LeaveRequest(
    var id: String = "",
    var staffId: String = "",
    var employeeId: String = "",
    var staffName: String = "",
    var leaveType: String = "Casual Leave", // Casual, Sick, Earned
    var startDate: Long = System.currentTimeMillis(),
    var endDate: Long = System.currentTimeMillis(),
    var reason: String = "",
    var status: String = "Pending", // Pending, Approved, Rejected
    var adminNotes: String? = null,
    var isHalfDay: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)
