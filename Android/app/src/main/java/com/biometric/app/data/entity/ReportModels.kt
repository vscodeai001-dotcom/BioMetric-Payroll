package com.biometric.app.data.entity

import java.io.Serializable

data class ConsolidatedAttendanceRow(
    val employeeId: Int,
    val employeeName: String,
    val totalWorkedHours: Double,
    val totalOvertimeMs: Long,
    val totalPenaltyMs: Long,
    val totalAbsentDays: Int
) : Serializable

data class PayrollVarianceRow(
    val employeeName: String,
    val currentNet: Double,
    val previousNet: Double,
    val difference: Double
) : Serializable

data class FinancialRegisterRow(
    val employeeId: Int,
    val employeeName: String,
    val biometricId: String,
    val email: String?,
    val monthlySalary: Double,
    val hourlyRate: Double,
    val baseSalaryComp: Double,
    val payrollType: String,
    val earnedHours: Double,
    val totalOvertimeMs: Long,
    val totalOvertimePay: Double,
    val shiftAllowance: Double,
    val bonusPaid: Double,
    val grossPayable: Double,
    val absentDays: Int,
    val leaveDays: Int,
    val penaltyDeduction: Double,
    val advanceDeduction: Double,
    val pfDeduction: Double,
    val esiDeduction: Double,
    val ptDeduction: Double,
    val tdsDeduction: Double,
    val totalDeductions: Double,
    val netPayable: Double,
    val payrollStatus: String
) : Serializable
