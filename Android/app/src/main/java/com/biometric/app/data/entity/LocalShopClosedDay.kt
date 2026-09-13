package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.biometric.app.data.Converters

@Entity(tableName = "local_shop_closed_days")
@TypeConverters(Converters::class)
data class LocalShopClosedDay(
    @PrimaryKey val id: String,
    val shopId: String,
    val date: Long,
    val paySalary: Boolean,
    val reason: String?,
    val affectedEmployeeIds: List<String>,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
