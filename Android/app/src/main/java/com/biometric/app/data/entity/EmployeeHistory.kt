package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.io.Serializable
import java.util.UUID

@Keep
data class EmployeeHistory(
    var historyId: String = UUID.randomUUID().toString(),
    var employeeId: String = "",
    var version: Int = 0,
    var type: String = "SALARY", // SALARY, ALLOWANCE, or SHIFT
    var salaryType: String = "",
    var oldValue: Double = 0.0,
    var newValue: Double = 0.0,
    var shiftStart: String = "",
    var shiftEnd: String = "",
    var breakHours: Double = 0.0,
    var shift2Start: String? = null,
    var shift2End: String? = null,
    var weekendShiftStart: String? = null,
    var weekendShiftEnd: String? = null,
    var weekendBreakHours: Double? = null,
    var weekendShift2Start: String? = null,
    var weekendShift2End: String? = null,
    var isBonusEligible: Boolean = true,
    var isPaidLeaveEligible: Boolean = true,
    var paidLeaveOnWeekdays: Boolean = true,
    var paidLeaveOnWeekends: Boolean = false,
    var rulesOverrideJson: String? = null,
    var changeDate: Long = System.currentTimeMillis(),
    var effectiveDate: Long = System.currentTimeMillis(),
    var endDate: Long? = null,
    var changeReason: String? = null,
    var salaryRate: Double = 0.0
) : Serializable
