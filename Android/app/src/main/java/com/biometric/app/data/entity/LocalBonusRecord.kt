package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_bonus_records")
data class LocalBonusRecord(
    @PrimaryKey val bonusId: Int,
    val employeeId: Int,
    val bonusDate: Long,
    val amount: Double,
    val description: String?,
    val payrollIdPaid: Int?,
    val syncState: Int = 0
)
