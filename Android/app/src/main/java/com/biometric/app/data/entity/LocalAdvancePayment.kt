package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_advance_payments")
data class LocalAdvancePayment(
    @PrimaryKey val advanceId: String,
    val employeeId: String,
    val shopId: String,
    val amount: Double,
    val date: Long,
    val isRecovered: Boolean,
    val recoveryPaymentId: String?,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
