package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_salary_snapshots")
data class LocalSalarySnapshot(
    @PrimaryKey val snapshotId: String,
    val employeeId: String,
    val shopId: String,
    val periodStart: Long,
    val periodEnd: Long,
    val totalNormalWorkedHours: Double,
    val totalOTHours: Double,
    val presentDaysCount: Int,
    val closedShopDaysCount: Int,
    val totalAllowanceMoney: Double,
    val dayWiseEarningsJson: String,
    val dayWiseWorkedHoursJson: String,
    val createdAt: Long,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
