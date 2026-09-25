package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_employees")
data class LocalEmployee(
    @PrimaryKey val employeeId: String,
    val shopId: String,
    val name: String,
    val role: String,
    val isActive: Boolean,
    val salaryType: String = "MONTHLY_FIXED",
    val salaryRate: Double = 0.0,
    val basicSalaryComponent: Double = 0.0,
    val hraComponent: Double = 0.0,
    val daComponent: Double = 0.0,
    val standardHours: Int = 8,
    val otRule: String = "No Overtime",
    val otFlatRate: Double = 0.0,
    val otRateMultiplier: Double = 1.0,
    val salaryCalculationMethod: String = "Days in Month",
    val compOffDayOfWeek: Int? = null,
    val shiftMode: String = "SINGLE_DAY",
    val trackingMode: String = "24/7",
    val enablePf: Boolean = false,
    val enableEsi: Boolean = false,
    val tdsRatePercent: Double = 0.0,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)

