package com.biometric.app.data.entity

import java.math.BigDecimal

data class SalaryBreakdown(
    val basicSalary: BigDecimal = BigDecimal.ZERO,
    val hra: BigDecimal = BigDecimal.ZERO,
    val specialAllowance: BigDecimal = BigDecimal.ZERO,
    val grossSalary: BigDecimal = BigDecimal.ZERO,
    val pfEmployee: BigDecimal = BigDecimal.ZERO,
    val pfEmployer: BigDecimal = BigDecimal.ZERO,
    val esiEmployee: BigDecimal = BigDecimal.ZERO,
    val esiEmployer: BigDecimal = BigDecimal.ZERO,
    val professionalTax: BigDecimal = BigDecimal.ZERO,
    val otherDeductions: BigDecimal = BigDecimal.ZERO,
    val netPayable: BigDecimal = BigDecimal.ZERO
)
