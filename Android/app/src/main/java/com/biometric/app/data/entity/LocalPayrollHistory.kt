package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "payroll_history")
data class LocalPayrollHistory(
    @PrimaryKey val payrollId: Int,
    val employeeId: Int,
    val payMonth: Int,
    val payYear: Int,
    val baseSalary: Double,
    val totalHoursWorked: Double,
    val overtimePay: Double,
    val deductionsHours: Double,
    val deductionsAdvance: Double,
    val bonus: Double,
    val netSalary: Double,
    val manualLeaveDays: Int,
    val absentDays: Int,
    val totalPenaltyMs: Long,
    val totalOvertimeMs: Long,
    val hourlyRate: Double,
    val basicComponent: Double,
    val pfDeduction: Double,
    val esiDeduction: Double,
    val employerPfContribution: Double,
    val employerEsiContribution: Double,
    val ptDeduction: Double,
    val tdsDeduction: Double,
    val totalShiftAllowance: Double,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
