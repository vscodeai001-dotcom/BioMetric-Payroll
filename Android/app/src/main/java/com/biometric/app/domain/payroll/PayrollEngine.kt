package com.biometric.app.domain.payroll

import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.SalaryBreakdown
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * PayrollEngine: Ports logic from C# SalaryStructureService.
 * Handles PF, ESI, Professional Tax, and Salary Component breakdown.
 */
object PayrollEngine {
    private const val PF_WAGE_CEILING = 15000.0
    private const val DEFAULT_BASIC_PERCENTAGE = 0.40 // From C# CompanySetting

    /**
     * Ports logic from C# SalaryStructureService.
     * Calculates the full salary breakdown including PF, ESI, and Professional Tax.
     */
    fun calculateBreakdown(
        grossEarned: Double,
        employee: Employee? = null,
        enablePfEsi: Boolean = true,
        enablePt: Boolean = true,
        ptSlabs: List<Pair<DoubleRange, Double>> = listOf(
            DoubleRange(0.0, 7500.0) to 0.0,
            DoubleRange(7500.0, 10000.0) to 175.0,
            DoubleRange(10000.0, Double.MAX_VALUE) to 200.0
        )
    ): SalaryBreakdown {
        var basicSalary = BigDecimal.ZERO
        var pfDeduction = BigDecimal.ZERO
        var esiDeduction = BigDecimal.ZERO
        var ptDeduction = BigDecimal.ZERO
        var employerPf = BigDecimal.ZERO
        var employerEsi = BigDecimal.ZERO

        if (enablePfEsi) {
            // Determine Basic component basis
            val basicBasis = if (employee != null && employee.salaryRate > 0) {
                // If we have a structured basic from employee profile (simulated)
                grossEarned * DEFAULT_BASIC_PERCENTAGE 
            } else {
                grossEarned * DEFAULT_BASIC_PERCENTAGE
            }
            basicSalary = BigDecimal.valueOf(Math.round(basicBasis).toDouble()).setScale(2, RoundingMode.HALF_UP)

            // PF Logic: 12% of Basic, capped at 15000
            if (employee == null || true /* isPfEnabled */) {
                val pfBasis = Math.min(basicSalary.toDouble(), PF_WAGE_CEILING)
                pfDeduction = BigDecimal.valueOf(Math.round(pfBasis * 0.12)).setScale(2, RoundingMode.HALF_UP)
                employerPf = BigDecimal.valueOf(Math.round(pfBasis * 0.13)).setScale(2, RoundingMode.HALF_UP)
            }

            // ESI Logic: 0.75% / 3.25% if gross <= 21000
            if (grossEarned <= 21000.0) {
                esiDeduction = BigDecimal.valueOf(Math.ceil(grossEarned * 0.0075)).setScale(2, RoundingMode.HALF_UP)
                employerEsi = BigDecimal.valueOf(Math.ceil(grossEarned * 0.0325)).setScale(2, RoundingMode.HALF_UP)
            }
        }

        if (enablePt) {
            val slab = ptSlabs.find { grossEarned in it.first }
            ptDeduction = BigDecimal.valueOf(slab?.second ?: 0.0).setScale(2, RoundingMode.HALF_UP)
        }

        val totalDeductions = pfDeduction.add(esiDeduction).add(ptDeduction)
        val netPayable = BigDecimal.valueOf(grossEarned).subtract(totalDeductions).setScale(2, RoundingMode.HALF_UP)

        return SalaryBreakdown(
            basicSalary = basicSalary,
            grossSalary = BigDecimal.valueOf(grossEarned).setScale(2, RoundingMode.HALF_UP),
            pfEmployee = pfDeduction,
            pfEmployer = employerPf,
            esiEmployee = esiDeduction,
            esiEmployer = employerEsi,
            professionalTax = ptDeduction,
            netPayable = netPayable
        )
    }

    class DoubleRange(val start: Double, val end: Double) {
        operator fun contains(value: Double) = value >= start && value <= end
    }
}
