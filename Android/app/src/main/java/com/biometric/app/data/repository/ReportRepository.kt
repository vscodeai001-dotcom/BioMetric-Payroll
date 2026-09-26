package com.biometric.app.data.repository

import com.biometric.app.data.dao.LocalDailySummaryDao
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.data.dao.LocalPayrollHistoryDao
import com.biometric.app.data.entity.CompanyCumulativeSummary
import com.biometric.app.data.entity.ConsolidatedAttendanceRow
import com.biometric.app.data.entity.FinancialRegisterRow
import com.biometric.app.data.entity.LocalDailySummary
import com.biometric.app.data.entity.LocalPayrollHistory
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
 * Report data boundary (SSOT).
 *
 * Pulls from Room offline cache first. If Room is empty, falls back directly
 * to live Firebase Realtime Database projections and hydrates Room.
 * If payroll has not been finalized yet for the target month, derives live
 * analytical preview payroll from attendance daily summaries and employee profiles.
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
        val salaryRate: Double,
        val basicSalaryComponent: Double = 0.0,
        val salaryCalculationMethod: String? = null,
        val standardHours: Int = 8,
        val otRule: String = "1.5x",
        val otFlatRate: Double = 0.0,
        val otRateMultiplier: Double = 1.5,
        val dailyAllowance: Double = 0.0,
        val compOffDayOfWeek: Int? = null,
        val enablePf: Boolean = true,
        val enableEsi: Boolean = true,
        val tdsRatePercent: Double = 0.0
    )

    /**
     * Retrieves daily summaries from Room, or falls back to live Firebase if Room is not yet hydrated.
     */
    private suspend fun getOrFetchDailySummaries(
        startDate: String,
        endDate: String
    ): List<LocalDailySummary> {
        val local = runCatching {
            summaryDao.getAllFlow()
                .first()
                .filter { it.shiftDate in startDate..endDate }
        }.getOrDefault(emptyList())

        if (local.isNotEmpty()) {
            return local
        }

        // Fallback: Fetch directly from Firebase Realtime Database
        val ownerRef = firebaseSync.getOwnerRef() ?: return local
        val snapshot = runCatching { ownerRef.child("daily_summaries").get().await() }.getOrNull()
        if (snapshot == null || !snapshot.exists()) {
            return local
        }

        val fbSummaries = snapshot.children.mapNotNull { snap ->
            val shiftDate = stringValue(snap, "shiftDate") ?: stringValue(snap, "date") ?: ""
            if (shiftDate.isBlank()) return@mapNotNull null
            val eId = intValue(snap, "employeeId") ?: intValue(snap, "staffId") ?: 0
            val rawId = intValue(snap, "summaryId")?.takeIf { it > 0 } ?: snap.key?.toIntOrNull()?.takeIf { it > 0 }
            val sId = rawId ?: (eId.toString() + "_" + shiftDate).hashCode().let { if (it == 0) 1 else if (it < 0) Math.abs(it) else it }

            LocalDailySummary(
                summaryId = sId,
                employeeId = eId,
                shiftDate = shiftDate,
                status = stringValue(snap, "status") ?: "Absent",
                earnedStandardHours = doubleValue(snap, "earnedStandardHours") ?: 0.0,
                totalOvertimeMs = longValue(snap, "totalOvertimeMs") ?: 0L,
                totalPenaltyMs = longValue(snap, "totalPenaltyMs") ?: 0L,
                totalLatenessMs = longValue(snap, "totalLatenessMs") ?: 0L,
                totalBreakPenaltyMs = longValue(snap, "totalBreakPenaltyMs") ?: 0L,
                scheduledShiftDurationMs = longValue(snap, "scheduledShiftDurationMs") ?: 0L,
                shiftAllowanceEarned = doubleValue(snap, "shiftAllowanceEarned") ?: 0.0,
                isManualOverride = booleanValue(snap, "isManualOverride") ?: false,
                syncState = 1
            )
        }

        if (fbSummaries.isNotEmpty()) {
            runCatching { summaryDao.upsertAll(fbSummaries) }
            return fbSummaries.filter { it.shiftDate in startDate..endDate }
        }

        return local
    }

    suspend fun generateConsolidatedAttendance(
        startDate: String,
        endDate: String,
        employeeId: Int? = null
    ): List<ConsolidatedAttendanceRow> = withContext(Dispatchers.IO) {
        val summaries = getOrFetchDailySummaries(startDate, endDate)
            .filter { employeeId == null || employeeId == 0 || it.employeeId == employeeId }

        val employees = employeeDao.getAll().associateBy { it.employeeId.toIntOrNull() ?: 0 }
        val profiles = loadFirebaseEmployeeProfiles()

        summaries
            .groupBy { it.employeeId }
            .map { (empId, empSummaries) ->
                val name = profiles[empId]?.name ?: employees[empId]?.name ?: "ID:$empId"

                ConsolidatedAttendanceRow(
                    employeeId = empId,
                    employeeName = name,
                    totalWorkedHours =
                        empSummaries.sumOf { it.earnedStandardHours } +
                            empSummaries.sumOf {
                                it.totalOvertimeMs.toDouble() / 3600000.0
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
                        empSummaries.count { it.status.equals("Absent", ignoreCase = true) }
                )
            }.sortedBy { it.employeeName }
    }

    suspend fun generateEmployeeDailyAttendance(
        startDate: String,
        endDate: String,
        employeeId: Int
    ): List<LocalDailySummary> = withContext(Dispatchers.IO) {
        val summaries = getOrFetchDailySummaries(startDate, endDate)
        summaries.filter { it.employeeId == employeeId }.sortedByDescending { it.shiftDate }
    }

    suspend fun calculateCompanyCumulativeSummary(
        startDate: String,
        endDate: String,
        employeeId: Int? = null
    ): CompanyCumulativeSummary = withContext(Dispatchers.IO) {
        val summaries = getOrFetchDailySummaries(startDate, endDate)
            .filter { employeeId == null || employeeId == 0 || it.employeeId == employeeId }

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

    private suspend fun getOrLoadPayrollHistory(
        year: Int,
        month: Int
    ): List<LocalPayrollHistory> {
        val local = runCatching {
            payrollDao.getAllFlow()
                .first()
                .filter { it.payYear == year && it.payMonth == month }
        }.getOrDefault(emptyList())

        if (local.isNotEmpty()) {
            return local
        }

        // Try to fetch finalized records from Firebase
        val ownerRef = firebaseSync.getOwnerRef()
        if (ownerRef != null) {
            val snapshot = runCatching { ownerRef.child("payroll_history").get().await() }.getOrNull()
            if (snapshot != null && snapshot.exists()) {
                val fbPayrolls = snapshot.children.mapNotNull { snap ->
                    val y = intValue(snap, "payYear") ?: return@mapNotNull null
                    val m = intValue(snap, "payMonth") ?: return@mapNotNull null
                    if (y != year || m != month) return@mapNotNull null
                    val pId = intValue(snap, "payrollId") ?: snap.key?.toIntOrNull() ?: 0
                    val eId = intValue(snap, "employeeId") ?: 0
                    LocalPayrollHistory(
                        payrollId = pId,
                        employeeId = eId,
                        payMonth = m,
                        payYear = y,
                        baseSalary = doubleValue(snap, "baseSalary") ?: 0.0,
                        totalHoursWorked = doubleValue(snap, "totalHoursWorked") ?: 0.0,
                        overtimePay = doubleValue(snap, "overtimePay") ?: 0.0,
                        deductionsHours = doubleValue(snap, "deductionsHours") ?: 0.0,
                        deductionsAdvance = doubleValue(snap, "deductionsAdvance") ?: 0.0,
                        bonus = doubleValue(snap, "bonus") ?: 0.0,
                        netSalary = doubleValue(snap, "netSalary") ?: 0.0,
                        manualLeaveDays = intValue(snap, "manualLeaveDays") ?: 0,
                        absentDays = intValue(snap, "absentDays") ?: 0,
                        totalPenaltyMs = longValue(snap, "totalPenaltyMs") ?: 0L,
                        totalOvertimeMs = longValue(snap, "totalOvertimeMs") ?: 0L,
                        hourlyRate = doubleValue(snap, "hourlyRate") ?: 0.0,
                        basicComponent = doubleValue(snap, "basicComponent") ?: 0.0,
                        pfDeduction = doubleValue(snap, "pfDeduction") ?: 0.0,
                        esiDeduction = doubleValue(snap, "esiDeduction") ?: 0.0,
                        employerPfContribution = doubleValue(snap, "employerPfContribution") ?: 0.0,
                        employerEsiContribution = doubleValue(snap, "employerEsiContribution") ?: 0.0,
                        ptDeduction = doubleValue(snap, "ptDeduction") ?: 0.0,
                        tdsDeduction = doubleValue(snap, "tdsDeduction") ?: 0.0,
                        totalShiftAllowance = doubleValue(snap, "totalShiftAllowance") ?: 0.0,
                        syncState = 1
                    )
                }
                if (fbPayrolls.isNotEmpty()) {
                    runCatching { payrollDao.upsertAll(fbPayrolls) }
                    return fbPayrolls
                }
            }
        }

        // If not finalized yet: compute live analytical draft payroll for (year, month)
        return computeDraftPayrollForMonth(year, month)
    }

    private suspend fun computeDraftPayrollForMonth(
        year: Int,
        month: Int
    ): List<LocalPayrollHistory> {
        val cal = Calendar.getInstance().apply { set(year, month - 1, 1) }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthStr = if (month < 10) "0$month" else "$month"
        val startDate = "$year-$monthStr-01"
        val endDate = "$year-$monthStr-$daysInMonth"

        val summaries = getOrFetchDailySummaries(startDate, endDate)
        val profiles = loadFirebaseEmployeeProfiles()
        val localEmployees = employeeDao.getAll().associateBy { it.employeeId.toIntOrNull() ?: 0 }

        val allEmpIds = (profiles.keys + localEmployees.keys).filter { it > 0 }.distinct().sorted()
        if (allEmpIds.isEmpty()) return emptyList()

        val advances = fetchMonthAdvances(year, month)
        val bonuses = fetchMonthBonuses(year, month)

        return allEmpIds.mapNotNull { empId ->
            val profile = profiles[empId]
            val localEmp = localEmployees[empId]
            val empSummaries = summaries.filter { it.employeeId == empId }

            val baseSalary = profile?.salaryRate ?: (localEmp?.salaryRate ?: 0.0)
            val hourlyRate = if (baseSalary > 0) baseSalary / (daysInMonth * 8.0) else 0.0

            val earnedHours = empSummaries.sumOf { it.earnedStandardHours }
            val overtimeMs = empSummaries.sumOf { it.totalOvertimeMs }
            val penaltyMs = empSummaries.sumOf { it.totalPenaltyMs }
            val absentDays = empSummaries.count { it.status.equals("Absent", ignoreCase = true) }
            val leaveDays = empSummaries.count { it.status.contains("Leave", ignoreCase = true) }
            val shiftAllowance = empSummaries.sumOf { it.shiftAllowanceEarned }

            val otHours = overtimeMs.toDouble() / 3600000.0
            val overtimePay = otHours * hourlyRate * (profile?.otRateMultiplier ?: 1.5)

            val penaltyHours = penaltyMs.toDouble() / 3600000.0
            val penaltyDeduction = penaltyHours * hourlyRate

            val empAdvances = advances.filter { it.first == empId }.sumOf { it.second }
            val empBonus = bonuses.filter { it.first == empId }.sumOf { it.second }

            val presentDays = empSummaries.count { it.status.equals("Present", ignoreCase = true) || it.status.equals("Half Day", ignoreCase = true) }
            val earnedPay = if (baseSalary > 0) {
                (presentDays.toDouble() / daysInMonth.toDouble()) * baseSalary
            } else {
                earnedHours * hourlyRate
            }

            val gross = earnedPay + overtimePay + empBonus + shiftAllowance

            val basicComponent = profile?.basicSalaryComponent?.takeIf { it > 0 } ?: (baseSalary * 0.40)
            val pfDeduction = if (profile?.enablePf != false && basicComponent > 0) (basicComponent * 0.12).coerceAtMost(1800.0) else 0.0
            val esiDeduction = if (profile?.enableEsi != false && gross in 1.0..21000.0) gross * 0.0075 else 0.0
            val ptDeduction = if (gross > 15000.0) 200.0 else if (gross > 10000.0) 150.0 else 0.0
            val tdsDeduction = if ((profile?.tdsRatePercent ?: 0.0) > 0.0) gross * (profile!!.tdsRatePercent / 100.0) else 0.0

            val totalStatutory = pfDeduction + esiDeduction + ptDeduction + tdsDeduction
            val totalOtherDeductions = penaltyDeduction + empAdvances
            val totalDeductions = totalStatutory + totalOtherDeductions
            val netSalary = (gross - totalDeductions).coerceAtLeast(0.0)

            LocalPayrollHistory(
                payrollId = empId * 100000 + year * 100 + month,
                employeeId = empId,
                payMonth = month,
                payYear = year,
                baseSalary = baseSalary,
                totalHoursWorked = earnedHours,
                overtimePay = overtimePay,
                deductionsHours = penaltyDeduction,
                deductionsAdvance = empAdvances,
                bonus = empBonus,
                netSalary = netSalary,
                manualLeaveDays = leaveDays,
                absentDays = absentDays,
                totalPenaltyMs = penaltyMs,
                totalOvertimeMs = overtimeMs,
                hourlyRate = hourlyRate,
                basicComponent = basicComponent,
                pfDeduction = pfDeduction,
                esiDeduction = esiDeduction,
                employerPfContribution = pfDeduction,
                employerEsiContribution = if (esiDeduction > 0) gross * 0.0325 else 0.0,
                ptDeduction = ptDeduction,
                tdsDeduction = tdsDeduction,
                totalShiftAllowance = shiftAllowance,
                syncState = 0
            )
        }
    }

    private suspend fun fetchMonthAdvances(year: Int, month: Int): List<Pair<Int, Double>> {
        val ownerRef = firebaseSync.getOwnerRef() ?: return emptyList()
        val snapshot = runCatching { ownerRef.child("advance_payments").get().await() }.getOrNull() ?: return emptyList()
        val cal = Calendar.getInstance()
        return snapshot.children.mapNotNull { snap ->
            val recovered = snap.child("isRecovered").value?.toString()?.toBooleanStrictOrNull() ?: false
            if (recovered) return@mapNotNull null
            val empId = intValue(snap, "employeeId") ?: return@mapNotNull null
            val amt = doubleValue(snap, "amount") ?: 0.0
            val dateMs = longValue(snap, "date") ?: 0L
            if (dateMs > 0) {
                cal.timeInMillis = dateMs
                if (cal.get(Calendar.YEAR) == year && (cal.get(Calendar.MONTH) + 1) == month) {
                    return@mapNotNull empId to amt
                }
            }
            empId to amt
        }
    }

    private suspend fun fetchMonthBonuses(year: Int, month: Int): List<Pair<Int, Double>> {
        val ownerRef = firebaseSync.getOwnerRef() ?: return emptyList()
        val snapshot = runCatching { ownerRef.child("bonus_records").get().await() }.getOrNull() ?: return emptyList()
        val cal = Calendar.getInstance()
        return snapshot.children.mapNotNull { snap ->
            val empId = intValue(snap, "employeeId") ?: return@mapNotNull null
            val amt = doubleValue(snap, "amount") ?: 0.0
            val bonusDateMs = longValue(snap, "bonusDate") ?: 0L
            if (bonusDateMs > 0) {
                cal.timeInMillis = bonusDateMs
                if (cal.get(Calendar.YEAR) == year && (cal.get(Calendar.MONTH) + 1) == month) {
                    return@mapNotNull empId to amt
                }
            }
            null
        }
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

        val currentPayrolls = getOrLoadPayrollHistory(year, month)
        val previousPayrolls = getOrLoadPayrollHistory(previousYear, previousMonth)
            .associateBy { it.employeeId }

        val localEmployees = employeeDao.getAll().associateBy { it.employeeId.toIntOrNull() ?: 0 }
        val profiles = loadFirebaseEmployeeProfiles()

        currentPayrolls.map { current ->
            val previous = previousPayrolls[current.employeeId]
            val employeeName = profiles[current.employeeId]?.name
                ?: localEmployees[current.employeeId]?.name
                ?: "ID:${current.employeeId}"

            val previousNet = previous?.netSalary ?: 0.0

            PayrollVarianceRow(
                employeeName = employeeName,
                currentNet = current.netSalary,
                previousNet = previousNet,
                difference = current.netSalary - previousNet
            )
        }
    }

    suspend fun generateFinancialRegister(
        year: Int,
        month: Int
    ): List<FinancialRegisterRow> = withContext(Dispatchers.IO) {
        val payrolls = getOrLoadPayrollHistory(year, month)
        val localEmployees = employeeDao.getAll().associateBy { it.employeeId.toIntOrNull() ?: 0 }
        val firebaseEmployees = loadFirebaseEmployeeProfiles()

        payrolls.mapNotNull { payroll ->
            val employeeId = payroll.employeeId
            val localEmployee = localEmployees[employeeId]
            val firebaseEmployee = firebaseEmployees[employeeId]

            if (localEmployee == null && firebaseEmployee == null) {
                return@mapNotNull null
            }

            val employeeName = firebaseEmployee?.name?.takeIf { it.isNotBlank() }
                ?: localEmployee?.name
                ?: "ID:$employeeId"

            val biometricId = firebaseEmployee?.biometricId?.takeIf { it.isNotBlank() }
                ?: "N/A"

            val email = firebaseEmployee?.email

            val salaryType = firebaseEmployee?.salaryType?.takeIf { it.isNotBlank() }
                ?: "MONTHLY_FIXED"

            val salaryRate = firebaseEmployee?.salaryRate ?: (localEmployee?.salaryRate ?: 0.0)

            val monthlySalary = if (salaryType.equals("MONTHLY_FIXED", ignoreCase = true)) salaryRate else 0.0

            val totalStatutory = payroll.pfDeduction + payroll.esiDeduction + payroll.ptDeduction + payroll.tdsDeduction
            val totalOther = payroll.deductionsHours + payroll.deductionsAdvance
            val totalAllDeductions = totalStatutory + totalOther

            val statusText = if (payroll.syncState == 1) {
                if (payroll.netSalary > 0.0) "Paid" else "Zero Pay"
            } else {
                "Pending Finalization"
            }

            FinancialRegisterRow(
                employeeId = employeeId,
                employeeName = employeeName,
                biometricId = biometricId,
                email = email,
                monthlySalary = monthlySalary,
                hourlyRate = payroll.hourlyRate,
                baseSalaryComp = payroll.basicComponent,
                payrollType = if (salaryType.equals("MONTHLY_FIXED", ignoreCase = true)) "Monthly" else "Hourly",
                earnedHours = payroll.totalHoursWorked,
                totalOvertimeMs = payroll.totalOvertimeMs,
                totalOvertimePay = payroll.overtimePay,
                shiftAllowance = payroll.totalShiftAllowance,
                bonusPaid = payroll.bonus,
                grossPayable = payroll.netSalary + totalAllDeductions,
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
                payrollStatus = statusText
            )
        }
    }

    private suspend fun loadFirebaseEmployeeProfiles(): Map<Int, EmployeeReportProfile> {
        val employeesRef = firebaseSync.getOwnerRef()?.child("employees") ?: return emptyMap()
        val snapshot = runCatching { employeesRef.get().await() }.getOrNull() ?: return emptyMap()

        if (!snapshot.exists()) return emptyMap()

        val result = mutableMapOf<Int, EmployeeReportProfile>()

        for (child in snapshot.children) {
            val employeeId = intValue(child, "employeeId")
                ?: intValue(child, "EmployeeID")
                ?: child.key?.toIntOrNull()
                ?: continue

            if (employeeId <= 0) continue

            val name = stringValue(child, "name")
                ?: stringValue(child, "Name")
                ?: "ID:$employeeId"

            val biometricId = stringValue(child, "biometricId")
                ?: stringValue(child, "BiometricId")
                ?: ""

            val email = stringValue(child, "email")
                ?: stringValue(child, "Email")

            val salaryType = stringValue(child, "salaryType")
                ?: stringValue(child, "SalaryType")
                ?: "MONTHLY_FIXED"

            val salaryRate = doubleValue(child, "salaryRate")
                ?: doubleValue(child, "SalaryRate")
                ?: 0.0

            val basicSalaryComponent = doubleValue(child, "basicSalaryComponent") ?: 0.0
            val salaryCalcMethod = stringValue(child, "salaryCalculationMethod")
            val standardHrs = intValue(child, "standardHours") ?: 8
            val otRule = stringValue(child, "otRule") ?: "1.5x"
            val otFlatRate = doubleValue(child, "otFlatRate") ?: 0.0
            val otRateMultiplier = doubleValue(child, "otRateMultiplier") ?: 1.5
            val dailyAllowance = doubleValue(child, "dailyAllowance") ?: 0.0
            val compOff = intValue(child, "compOffDayOfWeek")
            val enablePf = booleanValue(child, "enablePf") ?: true
            val enableEsi = booleanValue(child, "enableEsi") ?: true
            val tdsRate = doubleValue(child, "tdsRatePercent") ?: 0.0

            result[employeeId] = EmployeeReportProfile(
                employeeId = employeeId,
                name = name,
                biometricId = biometricId,
                email = email,
                salaryType = salaryType,
                salaryRate = salaryRate,
                basicSalaryComponent = basicSalaryComponent,
                salaryCalculationMethod = salaryCalcMethod,
                standardHours = standardHrs,
                otRule = otRule,
                otFlatRate = otFlatRate,
                otRateMultiplier = otRateMultiplier,
                dailyAllowance = dailyAllowance,
                compOffDayOfWeek = compOff,
                enablePf = enablePf,
                enableEsi = enableEsi,
                tdsRatePercent = tdsRate
            )
        }

        return result
    }

    private fun stringValue(snapshot: DataSnapshot, name: String): String? {
        val value = snapshot.child(name).value
            ?: snapshot.child(name.replaceFirstChar { it.uppercase(Locale.US) }).value
        return value?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun intValue(snapshot: DataSnapshot, name: String): Int? {
        val value = snapshot.child(name).value
            ?: snapshot.child(name.replaceFirstChar { it.uppercase(Locale.US) }).value
        return when (value) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull()
        }
    }

    private fun doubleValue(snapshot: DataSnapshot, name: String): Double? {
        val value = snapshot.child(name).value
            ?: snapshot.child(name.replaceFirstChar { it.uppercase(Locale.US) }).value
        return when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        }
    }

    private fun longValue(snapshot: DataSnapshot, name: String): Long? {
        val value = snapshot.child(name).value
            ?: snapshot.child(name.replaceFirstChar { it.uppercase(Locale.US) }).value
        return when (value) {
            is Number -> value.toLong()
            else -> value?.toString()?.toLongOrNull()
        }
    }

    private fun booleanValue(snapshot: DataSnapshot, name: String): Boolean? {
        val value = snapshot.child(name).value
            ?: snapshot.child(name.replaceFirstChar { it.uppercase(Locale.US) }).value
        return when (value) {
            is Boolean -> value
            else -> value?.toString()?.toBooleanStrictOrNull()
        }
    }
}
