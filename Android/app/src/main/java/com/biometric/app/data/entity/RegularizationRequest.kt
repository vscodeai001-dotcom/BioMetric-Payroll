package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class RegularizationRequest(
    var id: String = "",
    var staffId: String = "",
    var employeeId: String = "",
    var staffName: String = "",
    var date: String = "", // yyyy-MM-dd
    var punchType: String = "IN", // IN or OUT
    var originalTime: Long? = null,
    var requestedTime: Long = System.currentTimeMillis(),
    var reason: String = "",
    var status: String = "Pending", // Pending, Approved, Rejected
    var adminRemarks: String? = null,
    var submittedAt: Long = System.currentTimeMillis()
)
