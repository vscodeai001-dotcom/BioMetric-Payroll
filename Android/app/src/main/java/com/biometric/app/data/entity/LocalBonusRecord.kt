package com.biometric.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.database.IgnoreExtraProperties

@Keep
@IgnoreExtraProperties
@Entity(tableName = "local_bonus_records")
data class LocalBonusRecord(
    @PrimaryKey val bonusId: Int = 0,
    val employeeId: Int = 0,
    val bonusDate: Long = 0L,
    val amount: Double = 0.0,
    val description: String? = null,
    val payrollIdPaid: Int? = null,
    val syncState: Int = 0,
    val firebaseKey: String? = null
)
