package com.biometric.app.data.repository

import com.biometric.app.data.dao.*
import com.biometric.app.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepository @Inject constructor(
    private val employeeDao: LocalEmployeeDao,
    private val summaryDao: LocalDailySummaryDao,
    private val payrollDao: LocalPayrollHistoryDao
) {
    suspend fun generateConsolidatedAttendance(startDate: String, endDate: String): List<ConsolidatedAttendanceRow> = withContext(Dispatchers.IO) {
        val summaries = summaryDao.getAllFlow().first()
            .filter { it.shiftDate in startDate..endDate }
        
        val employees = employeeDao.getAll().associateBy { it.employeeId.toInt() }
        
        summaries.groupBy { it.employeeId }
            .map { (empId, empSummaries) ->
                val emp = employees[empId]
                ConsolidatedAttendanceRow(
                    employeeId = empId,
                    employeeName = emp?.name ?: "ID:$empId",
                    totalWorkedHours = empSummaries.sumOf { it.earnedStandardHours } + empSummaries.sumOf { it.totalOvertimeMs.toDouble() / (1000 * 60 * 60) },
                    totalOvertimeMs = empSummaries.sumOf { it.totalOvertimeMs },
                    totalPenaltyMs = empSummaries.sumOf { it.totalPenaltyMs },
                    totalAbsentDays = empSummaries.count { it.status == "Absent" }
                )
            }
    }

    suspend fun generatePayrollVariance(year: Int, month: Int): List<PayrollVarianceRow> = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        cal.add(Calendar.MONTH, -1)
        val prevMonth = cal.get(Calendar.MONTH) + 1
        val prevYear = cal.get(Calendar.YEAR)

        val currentPayrolls = payrollDao.getAllFlow().first()
            .filter { it.payMonth == month && it.payYear == year }
        
        val previousPayrolls = payrollDao.getAllFlow().first()
            .filter { it.payMonth == prevMonth && it.payYear == prevYear }
            .associateBy { it.employeeId }
            
        val employees = employeeDao.getAll().associateBy { it.employeeId.toInt() }

        currentPayrolls.map { current ->
            val prev = previousPayrolls[current.employeeId]
            val emp = employees[current.employeeId]
            PayrollVarianceRow(
                employeeName = emp?.name ?: "ID:${current.employeeId}",
                currentNet = current.netSalary,
                previousNet = prev?.netSalary ?: 0.0,
                difference = current.netSalary - (prev?.netSalary ?: 0.0)
            )
        }
    }

    suspend fun generateFinancialRegister(year: Int, month: Int): List<FinancialRegisterRow> = withContext(Dispatchers.IO) {
        val payrolls = payrollDao.getAllFlow().first()
            .filter { it.payMonth == month && it.payYear == year }
        
        val employees = employeeDao.getAll().associateBy { it.employeeId.toInt() }

        payrolls.mapNotNull { ph ->
            val emp = employees[ph.employeeId] ?: return@mapNotNull null
            
            val totalStatutory = ph.pfDeduction + ph.esiDeduction + ph.ptDeduction + ph.tdsDeduction
            val totalOther = ph.deductionsHours + ph.deductionsAdvance
            val totalAllDeductions = totalStatutory + totalOther

            FinancialRegisterRow(
                employeeId = ph.employeeId,
                employeeName = emp.name,
                biometricId = "N/A", // Not in local employee entity yet
                email = null, 
                monthlySalary = 0.0, // Not in local employee entity yet
                hourlyRate = ph.hourlyRate,
                baseSalaryComp = ph.basicComponent,
                payrollType = "Monthly",
                earnedHours = ph.totalHoursWorked,
                totalOvertimeMs = ph.totalOvertimeMs,
                totalOvertimePay = ph.overtimePay,
                shiftAllowance = ph.totalShiftAllowance,
                bonusPaid = ph.bonus,
                grossPayable = ph.netSalary + totalAllDeductions,
                absentDays = ph.absentDays,
                leaveDays = ph.manualLeaveDays,
                penaltyDeduction = ph.deductionsHours,
                advanceDeduction = ph.deductionsAdvance,
                pfDeduction = ph.pfDeduction,
                esiDeduction = ph.esiDeduction,
                ptDeduction = ph.ptDeduction,
                tdsDeduction = ph.tdsDeduction,
                totalDeductions = totalAllDeductions,
                netPayable = ph.netSalary,
                payrollStatus = if (ph.netSalary > 0) "Paid" else "Zero Pay"
            )
        }
    }
}
