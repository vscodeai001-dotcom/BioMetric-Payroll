package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class AdvancePayment(
    var advanceId: String = "",
    var employeeId: String = "",
    var shopId: String = "",
    var amount: Double = 0.0,
    var date: Long = System.currentTimeMillis(),
    var isRecovered: Boolean = false,
    var recoveryPaymentId: String? = null
)
