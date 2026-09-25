package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_company_settings")
data class LocalCompanySettings(
    @PrimaryKey val id: Int = 1,
    var companyName: String = "",
    var addressLine1: String = "",
    var cityStatePincode: String = "",
    var salaryCalculationMethod: String = "Days in Month",
    var officeLatitude: Double = 0.0,
    var officeLongitude: Double = 0.0,
    var geoRadiusMeters: Int = 1000,
    var zktecoIP: String? = null,
    var zktecoPort: Int = 4370,
    var zktecoMachineNumber: Int = 1,
    var workDayCutoffHour: Int = 22,
    var endTimeGraceMinutes: Int = 0,
    var lateGraceMinutes: Int = 0,
    var enablePfEsiSystem: Boolean = false,
    var esiWageLimit: Double = 21000.0,
    var basicSalaryPercentage: Double = 40.0,
    var employeePfPercentage: Double = 12.0,
    var employeeEsiPercentage: Double = 0.75,
    var employerPfPercentage: Double = 13.0,
    var employerEsiPercentage: Double = 3.25,
    var enableProfessionalTax: Boolean = false,
    var enableEmailNotifications: Boolean = false,
    var smtpHost: String? = null,
    var smtpPort: Int = 587,
    var smtpUser: String? = null,
    var smtpPass: String? = null,
    var smtpFromEmail: String? = null,
    var enableSsl: Boolean = true,
    var enableShiftAllowance: Boolean = false,
    var enableLeaveAccrual: Boolean = false,
    var leaveAccrualRate: Double = 1.5,
    var enableSandwichRule: Boolean = false,
    var enableLeaveManagement: Boolean = false,
    var enableTdsDeduction: Boolean = false,
    var autoBackupIntervalHours: Int = 24,
    val syncState: Int = 1
)
