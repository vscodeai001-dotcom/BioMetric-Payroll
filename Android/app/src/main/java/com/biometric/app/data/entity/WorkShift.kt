package com.biometric.app.data.entity

import androidx.annotation.Keep

@Keep
data class WorkShift(
    var id: String = "",
    var name: String = "",
    var startTime: String = "09:00",
    var endTime: String = "18:00",
    var graceMinutes: Int = 15,
    var trackingMode: String = "SHIFT", // SHIFT, 24/7, CUSTOM
    var isActive: Boolean = true
)
