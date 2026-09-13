package com.biometric.app.data.entity

import java.math.BigDecimal

data class Payslip(
    val employeeId: String,
    val employeeName: String,
    val monthYear: String,
    val workedDays: Int,
    val totalHours: Double,
    val otHours: Double,
    val latenessDeduction: BigDecimal,
    val baseSalary: BigDecimal,
    val breakdown: SalaryBreakdown,
    val generatedAt: Long = System.currentTimeMillis()
)
