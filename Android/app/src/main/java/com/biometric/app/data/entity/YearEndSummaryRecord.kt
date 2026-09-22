package com.biometric.app.data.entity

/** Firebase projection of the Web Year-End Summary. Web payroll remains the calculation authority. */
data class YearEndSummaryRecord(
    val summaryId: Int = 0,
    val employeeId: Int = 0,
    val taxYear: Int = 0,
    val grossTaxableSalary: Double = 0.0,
    val totalTdsDeducted: Double = 0.0,
    val totalPfContributionEmployee: Double = 0.0,
    val totalAnnualAbsentDays: Int = 0,
    val totalAnnualOtPay: Double = 0.0,
    val state: String = "",
    val calculationSource: String = ""
)
