package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.io.Serializable

@Keep
data class SalarySnapshot(
    var snapshotId: String = "", // Format: employeeId_periodEndTs
    var employeeId: String = "",
    var shopId: String = "",
    var periodStart: Long = 0L,
    var periodEnd: Long = 0L,
    var totalNormalWorkedHours: Double = 0.0,
    var totalOTHours: Double = 0.0,
    var presentDaysCount: Int = 0,
    var closedShopDaysCount: Int = 0,
    var totalAllowanceMoney: Double = 0.0,
    var paidClosedDaysSalary: Double = 0.0,
    var paidClosedDaysHours: Double = 0.0,
    var upToDateRequiredHrs: Double = 0.0,
    var dayWiseEarningsJson: String = "{}",
    var dayWiseOTEarningsJson: String = "{}",
    var dayWiseAllowancesJson: String = "{}",
    var dayWiseWorkedHoursJson: String = "{}",
    var dayWiseBonusJson: String = "{}",
    var dayWisePaidLeaveJson: String = "{}",
    var dayShortfallsJson: String = "{}",
    var personalAbsenceDaysJson: String = "[]",
    var createdAt: Long = System.currentTimeMillis()
) : Serializable
