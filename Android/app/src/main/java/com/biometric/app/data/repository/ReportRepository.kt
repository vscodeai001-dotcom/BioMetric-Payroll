package com.biometric.app.data.repository

import com.biometric.app.data.dao.LocalDailySummaryDao
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.data.dao.LocalPayrollHistoryDao
import com.biometric.app.data.entity.CompanyCumulativeSummary
import com.biometric.app.data.entity.ConsolidatedAttendanceRow
import com.biometric.app.data.entity.FinancialRegisterRow
import com.biometric.app.data.entity.PayrollVarianceRow
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import java.util.Locale

/**
 * Report data boundary.
 *
 * Room remains the offline source for attendance summaries and payroll
 * history. Employee master fields that are not present in LocalEmployee
 * (biometricId, email, salaryType and salaryRate) are read from the
 * Firebase employee projection and merged with the local employee cache.
 *
 * No payroll calculation is performed here. Existing payroll values are
 * consumed exactly as stored in LocalPayrollHistory.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val employeeDao: LocalEmployeeDao,
    private val summaryDao: LocalDailySummaryDao,
    private val payrollDao: LocalPayrollHistoryDao,
    private val firebaseSync: FirebaseSyncManager
) {

    private data class EmployeeReportProfile(
        val employeeId: Int,
        val name: String,
        val biometricId: String,
        val email: String?,
        val salaryType: String,
        val salaryRate: Double
    )

    suspend fun generateConsolidatedAttendance(
        startDate: String,
        endDate: String,
        employeeId: Int? = null
    ): List<ConsolidatedAttendanceRow> = withContext(Dispatchers.IO) {

        val summaries = summaryDao.getAllFlow()
            .first()
            .filter {
                it.shiftDate in startDate..endDate &&
                    (employeeId == null || it.employeeId == employeeId)
            }

        val employees = employeeDao.getAll()
            .associateBy { it.employeeId.toIntOrNull() ?: 0 }

        summaries
            .groupBy { it.employeeId }
            .map { (empId, empSummaries) ->

                val emp = employees[empId]

                ConsolidatedAttendanceRow(
                    employeeId = empId,
                    employeeName = emp?.name ?: "ID:$empId",
                    totalWorkedHours =
                        empSummaries.sumOf { it.earnedStandardHours } +
                            empSummaries.sumOf {
                                it.totalOvertimeMs.toDouble() /
                                    (1000.0 * 60.0 * 60.0)
                            },
                    totalOvertimeMs =
                        empSummaries.sumOf { it.totalOvertimeMs },
                    totalPenaltyMs =
                        empSummaries.sumOf { it.totalPenaltyMs },
                    totalLatenessMs =
                        empSummaries.sumOf { it.totalLatenessMs },
                    totalScheduledDurationMs =
                        empSummaries.sumOf { it.scheduledShiftDurationMs },
                    totalAbsentDays =
                        empSummaries.count { it.status == "Absent" }
                )
            }
    }

    suspend fun calculateCompanyCumulativeSummary(
        startDate: String,
        endDate: String,
        employeeId: Int? = null
    ): CompanyCumulativeSummary = withContext(Dispatchers.IO) {
        val summaries = summaryDao.getAllFlow()
            .first()
            .filter {
                it.shiftDate in startDate..endDate &&
                    (employeeId == null || it.employeeId == employeeId)
            }

        if (summaries.isEmpty()) return@withContext CompanyCumulativeSummary()

        CompanyCumulativeSummary(
            totalEmployeesProcessed = summaries.map { it.employeeId }.distinct().count(),
            totalScheduledDurationMs = summaries.sumOf { it.scheduledShiftDurationMs },
            totalWorkedHours = summaries.sumOf { it.earnedStandardHours + (it.totalOvertimeMs.toDouble() / 3600000.0) },
            totalOvertimeMs = summaries.sumOf { it.totalOvertimeMs },
            totalOverallPenaltyMs = summaries.sumOf { it.totalPenaltyMs },
            totalLatenessMs = summaries.sumOf { it.totalLatenessMs },
            totalBreakPenaltyMs = summaries.sumOf { it.totalBreakPenaltyMs }
        )
    }

    suspend fun generatePayrollVariance(
        year: Int,
        month: Int
    ): List<PayrollVarianceRow> = withContext(Dispatchers.IO) {

        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        cal.add(Calendar.MONTH, -1)

        val previousMonth = cal.get(Calendar.MONTH) + 1
        val previousYear = cal.get(Calendar.YEAR)

        val currentPayrolls = payrollDao.getAllFlow()
            .first()
            .filter {
                it.payMonth == month &&
                    it.payYear == year
            }

        val previousPayrolls = payrollDao.getAllFlow()
            .first()
            .filter {
                it.payMonth == previousMonth &&
                    it.payYear == previousYear
            }
            .associateBy { it.employeeId }

        val employees = employeeDao.getAll()
            .associateBy { it.employeeId.toIntOrNull() ?: 0 }

        currentPayrolls.map { current ->

            val previous = previousPayrolls[current.employeeId]
            val employee = employees[current.employeeId]

            val previousNet =
                previous?.netSalary ?: 0.0

            PayrollVarianceRow(
                employeeName =
                    employee?.name
                        ?: "ID:${current.employeeId}",
                currentNet = current.netSalary,
                previousNet = previousNet,
                difference =
                    current.netSalary - previousNet
            )
        }
    }

    suspend fun generateFinancialRegister(
        year: Int,
        month: Int
    ): List<FinancialRegisterRow> = withContext(Dispatchers.IO) {

        val payrolls = payrollDao.getAllFlow()
            .first()
            .filter {
                it.payMonth == month &&
                    it.payYear == year
            }

        val localEmployees = employeeDao.getAll()
            .associateBy {
                it.employeeId.toIntOrNull() ?: 0
            }

        val firebaseEmployees =
            loadFirebaseEmployeeProfiles()

        payrolls.mapNotNull { payroll ->

            val employeeId = payroll.employeeId

            val localEmployee =
                localEmployees[employeeId]

            val firebaseEmployee =
                firebaseEmployees[employeeId]

            /*
             * A payroll record without a corresponding employee is not
             * inventively reconstructed. Keep the existing report behavior
             * of skipping an unresolvable employee.
             */
            if (localEmployee == null &&
                firebaseEmployee == null
            ) {
                return@mapNotNull null
            }

            val employeeName =
                firebaseEmployee?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: localEmployee?.name
                    ?: "ID:$employeeId"

            val biometricId =
                firebaseEmployee?.biometricId
                    ?.takeIf { it.isNotBlank() }
                    ?: "N/A"

            val email =
                firebaseEmployee?.email

            val salaryType =
                firebaseEmployee?.salaryType
                    ?.takeIf { it.isNotBlank() }
                    ?: "MONTHLY_FIXED"

            val salaryRate =
                firebaseEmployee?.salaryRate
                    ?: 0.0

            val monthlySalary =
                if (salaryType.equals(
                        "MONTHLY_FIXED",
                        ignoreCase = true
                    )
                ) {
                    salaryRate
                } else {
                    0.0
                }

            val totalStatutory =
                payroll.pfDeduction +
                    payroll.esiDeduction +
                    payroll.ptDeduction +
                    payroll.tdsDeduction

            val totalOther =
                payroll.deductionsHours +
                    payroll.deductionsAdvance

            val totalAllDeductions =
                totalStatutory + totalOther

            FinancialRegisterRow(
                employeeId = employeeId,
                employeeName = employeeName,
                biometricId = biometricId,
                email = email,
                monthlySalary = monthlySalary,
                hourlyRate = payroll.hourlyRate,
                baseSalaryComp = payroll.basicComponent,
                payrollType =
                    if (salaryType.equals(
                            "MONTHLY_FIXED",
                            ignoreCase = true
                        )
                    ) {
                        "Monthly"
                    } else {
                        "Hourly"
                    },
                earnedHours = payroll.totalHoursWorked,
                totalOvertimeMs = payroll.totalOvertimeMs,
                totalOvertimePay = payroll.overtimePay,
                shiftAllowance = payroll.totalShiftAllowance,
                bonusPaid = payroll.bonus,
                grossPayable =
                    payroll.netSalary +
                        totalAllDeductions,
                absentDays = payroll.absentDays,
                leaveDays = payroll.manualLeaveDays,
                penaltyDeduction = payroll.deductionsHours,
                advanceDeduction = payroll.deductionsAdvance,
                pfDeduction = payroll.pfDeduction,
                esiDeduction = payroll.esiDeduction,
                ptDeduction = payroll.ptDeduction,
                tdsDeduction = payroll.tdsDeduction,
                totalDeductions = totalAllDeductions,
                netPayable = payroll.netSalary,
                payrollStatus =
                    if (payroll.netSalary > 0.0) {
                        "Paid"
                    } else {
                        "Zero Pay"
                    }
            )
        }
    }

    /**
     * Reads only the employee master projection needed by the report.
     *
     * Firebase Employee IDs have historically existed as either JSON numbers
     * or strings, so parsing is deliberately tolerant without using the
     * Firebase automatic mapper.
     */
    private suspend fun loadFirebaseEmployeeProfiles():
        Map<Int, EmployeeReportProfile> {

        val employeesRef =
            firebaseSync.getOwnerRef()
                ?.child("employees")
                ?: return emptyMap()

        val snapshot =
            runCatching {
                employeesRef.get().await()
            }.getOrNull()
                ?: return emptyMap()

        if (!snapshot.exists()) {
            return emptyMap()
        }

        val result =
            mutableMapOf<Int, EmployeeReportProfile>()

        for (child in snapshot.children) {

            val employeeId =
                intValue(child, "employeeId")
                    ?: intValue(child, "EmployeeID")
                    ?: child.key?.toIntOrNull()
                    ?: continue

            if (employeeId <= 0) {
                continue
            }

            val name =
                stringValue(child, "name")
                    ?: stringValue(child, "Name")
                    ?: "ID:$employeeId"

            val biometricId =
                stringValue(child, "biometricId")
                    ?: stringValue(child, "BiometricId")
                    ?: ""

            val email =
                stringValue(child, "email")
                    ?: stringValue(child, "Email")

            val salaryType =
                stringValue(child, "salaryType")
                    ?: stringValue(child, "SalaryType")
                    ?: "MONTHLY_FIXED"

            val salaryRate =
                doubleValue(child, "salaryRate")
                    ?: doubleValue(child, "SalaryRate")
                    ?: 0.0

            result[employeeId] =
                EmployeeReportProfile(
                    employeeId = employeeId,
                    name = name,
                    biometricId = biometricId,
                    email = email,
                    salaryType = salaryType,
                    salaryRate = salaryRate
                )
        }

        return result
    }

    private fun stringValue(
        snapshot: DataSnapshot,
        name: String
    ): String? {
        val value =
            snapshot.child(name).value
                ?: snapshot.child(
                    name.replaceFirstChar {
                        it.uppercase(Locale.US)
                    }
                ).value

        return value
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun intValue(
        snapshot: DataSnapshot,
        name: String
    ): Int? {
        val value =
            snapshot.child(name).value
                ?: snapshot.child(
                    name.replaceFirstChar {
                        it.uppercase(Locale.US)
                    }
                ).value

        return when (value) {
            is Number -> value.toInt()
            else -> value
                ?.toString()
                ?.toIntOrNull()
        }
    }

    private fun doubleValue(
        snapshot: DataSnapshot,
        name: String
    ): Double? {
        val value =
            snapshot.child(name).value
                ?: snapshot.child(
                    name.replaceFirstChar {
                        it.uppercase(Locale.US)
                    }
                ).value

        return when (value) {
            is Number -> value.toDouble()
            else -> value
                ?.toString()
                ?.toDoubleOrNull()
        }
    }
}
