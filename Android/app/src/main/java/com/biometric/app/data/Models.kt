package com.biometric.app.data

import com.google.firebase.database.PropertyName
import com.google.gson.annotations.SerializedName
import com.biometric.app.data.entity.*
import java.io.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

data class DayData(
    val calendar: Calendar,
    val displayDate: String,
    var totalIn: Double = 0.0,
    var totalOut: Double = 0.0,
    var salaryEarned: Double = 0.0
) : Serializable

data class EmployeeStats(
    val employee: Employee? = null,
    val monthStart: Long = 0L,
    val monthEnd: Long = 0L,
    val pendingAdvance: Double = 0.0,
    var monthlyAdvance: Double = 0.0,
    val versionCount: Int = 0,

    // Monthly Totals & Counters
    var daysPresent: Int = 0,
    var daysAbsent: Int = 0,
    var closedShopDays: Int = 0,

    // Earned Values (Money)
    var totalNormalWorkedSalaryMonth: BigDecimal = BigDecimal.ZERO,
    var totalOTSalaryMonth: BigDecimal = BigDecimal.ZERO,
    var paidLeaveSalary: BigDecimal = BigDecimal.ZERO,
    var monthlyAllowance: BigDecimal = BigDecimal.ZERO,
    var bonusAmount: BigDecimal = BigDecimal.ZERO,
    var deductibleAmount: BigDecimal = BigDecimal.ZERO,
    var weekdayDeductibleHours: Double = 0.0,
    var weekdayDeductibleAmount: BigDecimal = BigDecimal.ZERO,
    var weekendDeductibleHours: Double = 0.0,
    var weekendDeductibleAmount: BigDecimal = BigDecimal.ZERO,

    // Payroll Breakdown
    var payrollBreakdown: SalaryBreakdown? = null,

    // Work Hours
    var requiredWorkHours: Double = 0.0,
    var totalNormalWorkedHoursMonth: Double = 0.0,
    var totalOTHoursMonth: Double = 0.0,
    var shortfallHours: Double = 0.0,
    var shortfallDays: Double = 0.0,
    var paidLeaveAppliedHours: Double = 0.0,

    // Precision & Breakdown Fields
    var lateCount: Int = 0,
    var earlyCount: Int = 0,
    var gapCount: Int = 0,
    var leaveCount: Int = 0,
    var totalLateHours: Double = 0.0,
    var totalEarlyHours: Double = 0.0,
    var totalGapHours: Double = 0.0,
    var totalLeaveHours: Double = 0.0,
    val totalLateAmount: BigDecimal = BigDecimal.ZERO,
    val totalEarlyAmount: BigDecimal = BigDecimal.ZERO,
    val totalGapAmount: BigDecimal = BigDecimal.ZERO,
    val totalLeaveAmount: BigDecimal = BigDecimal.ZERO,
    val netLateAmount: BigDecimal = BigDecimal.ZERO,
    val netEarlyAmount: BigDecimal = BigDecimal.ZERO,
    val netGapAmount: BigDecimal = BigDecimal.ZERO,
    val netLeaveAmount: BigDecimal = BigDecimal.ZERO,
    val otCount: Int = 0,
    val paidLeaveCount: Int = 0,
    val allowanceCount: Int = 0,
    val bonusCount: Int = 0,

    // Header Stats (Selected Day)
    var selectedDayWorkedHours: Double = 0.0,
    var selectedDayNormalWorkedHours: Double = 0.0,
    var selectedDayOTHours: Double = 0.0,
    var selectedDayNormalWorkedSalary: BigDecimal = BigDecimal.ZERO,
    var selectedDayOTSalary: BigDecimal = BigDecimal.ZERO,
    var selectedDayAllowance: BigDecimal = BigDecimal.ZERO,
    var selectedDayWorkedSalary: BigDecimal = BigDecimal.ZERO,

    // Status & Config
    var isPresentToday: Boolean = false,
    var isCheckedIn: Boolean = false,
    var fullMonthSalary: Double = 0.0,
    var selectedDaySalaryRate: Double = 0.0,
    var isBonusEligible: Boolean = false,
    var isPaidLeaveEligible: Boolean = false,
    var isBonusEligibleByRule: Boolean = false,
    var isPaidLeaveEligibleByRule: Boolean = false,

    // Versioned UI info
    var salaryType: String = "",
    var shiftStart: String = "",
    var shiftEnd: String = "",
    var shift2Start: String? = null,
    var shift2End: String? = null,
    var hourlyRate: Double = 0.0,
    var netShiftHours: Double = 0.0,

    // Detail Maps
    val dayWiseEarnings: Map<Long, BigDecimal> = emptyMap(),
    val dayWiseOTEarnings: Map<Long, BigDecimal> = emptyMap(),
    val dayWiseAllowances: Map<Long, BigDecimal> = emptyMap(),
    val dayWiseWorkedHours: Map<Long, Double> = emptyMap(),
    val dayWiseBonus: Map<Long, BigDecimal> = emptyMap(),
    val dayWisePaidLeave: Map<Long, BigDecimal> = emptyMap(),
    val dayWisePaidLeaveHours: Map<Long, Double> = emptyMap(),
    val dayWiseOTHours: Map<Long, Double> = emptyMap(),
    val dayShortfalls: Map<Long, Double> = emptyMap(),

    // Compatibility fields
    var monthlySalary: Double = 0.0,
    val todayHours: Double = 0.0,
    val todaySalary: Double = 0.0,
    val totalHoursMonth: Double = 0.0,
    val selectedDaySalary: Double = 0.0,
    val selectedDayHours: Double = 0.0,
    val selectedDayMidnight: Long = 0L,
    val activityLog: List<SalaryActivityItem> = emptyList()
) : Serializable {
    fun getNetPayableForStaffScreen(): Double {
        val raw = totalNormalWorkedSalaryMonth
            .add(totalOTSalaryMonth)
            .add(paidLeaveSalary)
            .add(bonusAmount)
        return raw.setScale(0, RoundingMode.HALF_UP).toDouble()
    }

    fun getTotalCost(): Double {
        return getNetPayableForStaffScreen() + monthlyAllowance.toDouble()
    }

    fun getRangeTotalEarnings(
        rangeStart: Long? = null,
        rangeEnd: Long? = null
    ): Double {
        val work = dayWiseEarnings.entries.filter { (rangeStart == null || it.key >= rangeStart) && (rangeEnd == null || it.key <= rangeEnd) }.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.value) }
        val ot = dayWiseOTEarnings.entries.filter { (rangeStart == null || it.key >= rangeStart) && (rangeEnd == null || it.key <= rangeEnd) }.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.value) }
        val pl = dayWisePaidLeave.entries.filter { (rangeStart == null || it.key >= rangeStart) && (rangeEnd == null || it.key <= rangeEnd) }.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.value) }
        val bonus = dayWiseBonus.entries.filter { (rangeStart == null || it.key >= rangeStart) && (rangeEnd == null || it.key <= rangeEnd) }.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.value) }
        val allowance = dayWiseAllowances.entries.filter { (rangeStart == null || it.key >= rangeStart) && (rangeEnd == null || it.key <= rangeEnd) }.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.value) }
        
        val netSalaryRounded = work.add(ot).add(pl).add(bonus).setScale(0, RoundingMode.HALF_UP)
        return netSalaryRounded.add(allowance).toDouble()
    }

    fun getRoundedPeriodLiability(
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        includeBonus: Boolean = true
    ): Double {
        val allDaysTs = (dayWiseEarnings.keys + dayWiseOTEarnings.keys + dayWiseBonus.keys + dayWisePaidLeave.keys + dayWiseAllowances.keys).distinct()
        
        var netSalaryRaw = BigDecimal.ZERO
        var totalAllowance = BigDecimal.ZERO
        
        allDaysTs.filter { (rangeStart == null || it >= rangeStart) && (rangeEnd == null || it <= rangeEnd) }.forEach { ts ->
            val work = dayWiseEarnings[ts] ?: BigDecimal.ZERO
            val ot = dayWiseOTEarnings[ts] ?: BigDecimal.ZERO
            val bonus = if (includeBonus) dayWiseBonus[ts] ?: BigDecimal.ZERO else BigDecimal.ZERO
            val pl = dayWisePaidLeave[ts] ?: BigDecimal.ZERO
            val allowance = dayWiseAllowances[ts] ?: BigDecimal.ZERO
            
            netSalaryRaw = netSalaryRaw.add(work).add(ot).add(bonus).add(pl)
            totalAllowance = totalAllowance.add(allowance)
        }
        
        val netSalaryRounded = netSalaryRaw.setScale(0, RoundingMode.HALF_UP)
        return netSalaryRounded.add(totalAllowance).toDouble()
    }

    fun getSelectedDayTotalCost(): Double {
        val ts = selectedDayMidnight
        val bonusOnDay = if (ts > 0) dayWiseBonus[ts] ?: BigDecimal.ZERO else BigDecimal.ZERO
        val plOnDay = if (ts > 0) dayWisePaidLeave[ts] ?: BigDecimal.ZERO else BigDecimal.ZERO
        
        val raw = selectedDayWorkedSalary.add(bonusOnDay).add(plOnDay)
        return raw.setScale(0, RoundingMode.HALF_UP).toDouble() + selectedDayAllowance.toDouble()
    }

    fun getNetPayable(): Double = getNetPayableForStaffScreen() - monthlyAdvance

    fun getFullMonthProjectedCost(): Double {
        return fullMonthSalary + monthlyAllowance.toDouble() + bonusAmount.toDouble() + totalOTSalaryMonth.toDouble()
    }
}

data class FullBackupData(
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("version") val version: Int = 1,
    @SerializedName("shops") val shops: List<Shop> = emptyList(),
    @SerializedName("employees") val employees: List<Employee> = emptyList(),
    @SerializedName("attendance") val attendance: List<Attendance> = emptyList(),
    @SerializedName("advances") val advances: List<AdvancePayment> = emptyList(),
    @SerializedName(value = "closed_days", alternate = ["closedDays"]) val closedDays: List<ShopClosedDay> = emptyList(),
    @SerializedName(value = "employee_history", alternate = ["employeeHistory"]) val employeeHistory: List<EmployeeHistory> = emptyList(),
    @SerializedName(value = "audit_logs", alternate = ["auditLogs"]) val auditLogs: List<AuditLog> = emptyList(),
    @SerializedName(value = "salary_payments", alternate = ["salaryPayments"]) val salaryPayments: List<SalaryPayment> = emptyList(),
    @SerializedName("reminders") val reminders: List<Reminder> = emptyList(),
    @SerializedName(value = "recycle_bin", alternate = ["recycleBin"]) val recycleBin: List<RecycleBinItem> = emptyList()
) : Serializable

data class SalaryActivityItem(
    val type: String,
    val title: String,
    val desc: String,
    val timestamp: Long,
    val totalHours: Double = 0.0,
    val netHours: Double = 0.0,
    val totalAmount: BigDecimal = BigDecimal.ZERO,
    val netAmount: BigDecimal = BigDecimal.ZERO
) : Serializable
