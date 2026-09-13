package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.io.Serializable

@Keep
data class ShopClosedDay(
    var id: String = "",
    var shopId: String = "",
    var date: Long = 0,
    var paySalary: Boolean = false,
    var reason: String? = null,
    var affectedEmployeeIds: List<String> = emptyList()
) : Serializable
