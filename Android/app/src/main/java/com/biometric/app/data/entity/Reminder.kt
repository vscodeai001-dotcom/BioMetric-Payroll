package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class Reminder(
    var reminderId: String = "",
    var shopId: String = "",
    var title: String = "",
    var amount: Double = 0.0,
    var dueDay: Int = 1,
    var category: String = "",
    var lastPaidMonth: Int? = null,
    var lastPaidYear: Int? = null,
    var isActive: Boolean = true
)
