package com.biometric.app.domain.payroll

import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.Payslip
import com.biometric.app.domain.attendance.AttendanceResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * PayrollCalculator: Aggregates daily attendance records to generate a monthly Payslip.
 */
object PayrollCalculator {

    /**
     * Aggregates monthly totals and applies PayrollEngine logic.
     * @param employee The employee record.
     * @param attendanceResults List of processed attendance for each day in the month.
     * @param monthYear The month and year being processed.
     */
    fun calculateMonthlyPayroll(
        employee: Employee,
        attendanceResults: List<AttendanceResult>,
        monthYear: YearMonth
    ): Payslip {
        var workedDays = 0
        var totalMinutes = 0L
        var otMinutes = 0L
        var totalLatenessMin = 0L
        var totalEarlyLeaveMin = 0L

        attendanceResults.forEach { result ->
            if (result.status != "ABSENT") {
                workedDays++
            }
            totalMinutes += result.totalWorkDurationMinutes
            otMinutes += result.overtimeMinutes
            totalLatenessMin += result.lateArrivalMinutes
            totalEarlyLeaveMin += result.earlyLeaveMinutes
        }

        val totalHours = totalMinutes / 60.0
        val otHours = otMinutes / 60.0
        val daysInMonth = monthYear.lengthOfMonth()
        
        // Derive hourly rate for deductions and OT calculation.
        // Standard shift duration used for calculation if not dynamically provided.
        val standardShiftHours = 8.0 
        val hourlyRate = employee.salaryRate / (daysInMonth * standardShiftHours)
        
        // Calculate deductions for lateness and early leaves.
        val totalDeductibleMinutes = totalLatenessMin + totalEarlyLeaveMin
        val latenessDeduction = BigDecimal.valueOf((totalDeductibleMinutes / 60.0) * hourlyRate)
            .setScale(2, RoundingMode.HALF_UP)

        // OT Earnings calculation.
        val otEarnings = otHours * (hourlyRate * employee.otRateMultiplier)

        // Aggregated daily allowance.
        val totalAllowance = workedDays * employee.dailyAllowance
        
        // Final Gross Earned for the month.
        val grossEarned = (employee.salaryRate - latenessDeduction.toDouble() + otEarnings + totalAllowance)
            .coerceAtLeast(0.0)

        // Apply PayrollEngine to get PF, ESI, and Tax breakdown.
        val breakdown = PayrollEngine.calculateBreakdown(grossEarned)

        val monthDisplay = "${monthYear.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${monthYear.year}"

        return Payslip(
            employeeId = employee.employeeId,
            employeeName = employee.name,
            monthYear = monthDisplay,
            workedDays = workedDays,
            totalHours = totalHours,
            otHours = otHours,
            latenessDeduction = latenessDeduction,
            baseSalary = BigDecimal.valueOf(employee.salaryRate),
            breakdown = breakdown
        )
    }
}
