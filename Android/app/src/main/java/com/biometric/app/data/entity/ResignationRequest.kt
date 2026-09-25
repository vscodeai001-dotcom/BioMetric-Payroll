package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class ResignationRequest(
    var requestId: String = "",
    var employeeId: String = "",
    var staffId: String = "",
    var submissionDate: Long = System.currentTimeMillis(),
    var desiredLastWorkingDay: Long = System.currentTimeMillis(),
    var reason: String? = null,
    var status: String = "Pending", // Pending, Approved, Rejected
    var approvedLastWorkingDay: Long? = null,
    var adminRemarks: String? = null,
    var isSettled: Boolean = false
)
