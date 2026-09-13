package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class SalaryPayment(
    var paymentId: String = "",
    var employeeId: String = "",
    var shopId: String = "",
    var totalEarnings: Double = 0.0,
    var deductions: Double = 0.0,
    var netPaid: Double = 0.0,
    var periodStart: Long = 0,
    var periodEnd: Long = 0,
    var paymentDate: Long = System.currentTimeMillis()
)
