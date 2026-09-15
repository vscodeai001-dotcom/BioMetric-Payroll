package com.biometric.app.sync

import com.biometric.app.data.dao.LocalAdvancePaymentDao
import com.biometric.app.data.dao.LocalAttendanceDao
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.LocalAuditLogDao
import com.biometric.app.data.dao.LocalBonusRecordDao
import com.biometric.app.data.dao.LocalDailySummaryDao
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.data.dao.LocalEmployeeHistoryDao
import com.biometric.app.data.dao.LocalFbpComponentDao
import com.biometric.app.data.dao.LocalFbpDeclarationDao
import com.biometric.app.data.dao.LocalLeaveRequestDao
import com.biometric.app.data.dao.LocalPayrollHistoryDao
import com.biometric.app.data.dao.LocalRegularizationRequestDao
import com.biometric.app.data.dao.LocalResignationRequestDao
import com.biometric.app.data.dao.LocalSalarySnapshotDao
import com.biometric.app.data.dao.LocalShiftScheduleDao
import com.biometric.app.data.dao.LocalShopClosedDayDao
import com.biometric.app.data.dao.LocalShopDao
import com.biometric.app.data.dao.LocalTaxDeclarationDao
import com.biometric.app.data.entity.*
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase -> Room cache hydration.
 *
 * Firebase is the realtime transport/read model for Android. Room remains the
 * existing local/offline cache used by repositories and screens.
 *
 * This class only mirrors Firebase records into existing Room tables. It does
 * not change entities, schema, calculations, layouts, or business rules.
 */
@Singleton
class FirebaseRoomHydrator @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val shopDao: LocalShopDao,
    private val employeeDao: LocalEmployeeDao,
    private val attendanceDao: LocalAttendanceDao,
    private val advanceDao: LocalAdvancePaymentDao,
    private val historyDao: LocalEmployeeHistoryDao,
    private val closedDayDao: LocalShopClosedDayDao,
    private val regularizationDao: LocalRegularizationRequestDao,
    private val punchDao: LocalAttendancePunchDao,
    private val leaveDao: LocalLeaveRequestDao,
    private val resignationDao: LocalResignationRequestDao,
    private val salarySnapshotDao: LocalSalarySnapshotDao,
    private val auditLogDao: LocalAuditLogDao,
    private val dailySummaryDao: LocalDailySummaryDao,
    private val shiftScheduleDao: LocalShiftScheduleDao,
    private val payrollHistoryDao: LocalPayrollHistoryDao,
    private val bonusRecordDao: LocalBonusRecordDao,
    private val taxDeclarationDao: LocalTaxDeclarationDao,
    private val fbpComponentDao: LocalFbpComponentDao,
    private val fbpDeclarationDao: LocalFbpDeclarationDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hydrationJob: Job? = null
    private val listeners = mutableListOf<Pair<Query, ChildEventListener>>()

    @Synchronized
    fun start() {
        if (hydrationJob?.isActive == true) return
        if (!firebaseSync.isAuthenticated()) return

        firebaseSync.startSync()

        hydrationJob = scope.launch {
            launch { firebaseSync.getDataFlow<Shop>("shops").collectLatest { items -> items.forEach { shopDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<Employee>("employees").collectLatest { items -> items.forEach { employeeDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<Attendance>("attendance").collectLatest { items -> items.forEach { attendanceDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<AdvancePayment>("advance_payments").collectLatest { items -> items.forEach { advanceDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<EmployeeHistory>("employee_history").collectLatest { items -> items.forEach { historyDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<ShopClosedDay>("shop_closed_days").collectLatest { items -> items.forEach { closedDayDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<RegularizationRequest>("regularizations").collectLatest { items -> items.forEach { regularizationDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<AttendancePunch>("attendance_punches").collectLatest { items -> items.forEach { punchDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<LeaveRequest>("leave_requests").collectLatest { items -> items.forEach { leaveDao.upsert(it.toLocal()) } } }
            launch { firebaseSync.getDataFlow<ResignationRequest>("resignation_requests").collectLatest { items -> items.forEach { resignationDao.upsert(it.toLocal()) } } }

            // Admin/SuperAdmin data. These listeners mirror changes and
            // deletions into the existing Room cache, so current UI flows
            // update immediately without a manual refresh.
            observe("salary_snapshots",
                onUpsert = { salarySnapshotDao.upsert(it.toLocalSalarySnapshot()) },
                onDelete = { salarySnapshotDao.deleteById(it.stringValue("snapshotId") ?: it.key.orEmpty()) })
            observe("audit_logs",
                onUpsert = { auditLogDao.upsert(it.toLocalAuditLog()) },
                onDelete = { auditLogDao.deleteById(it.stringValue("logId") ?: it.key.orEmpty()) })
            observe("daily_summaries",
                onUpsert = { dailySummaryDao.upsert(it.toLocalDailySummary()) },
                onDelete = { dailySummaryDao.deleteById(it.intValue("summaryId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("shift_schedules",
                onUpsert = { shiftScheduleDao.upsert(it.toLocalShiftSchedule()) },
                onDelete = { shiftScheduleDao.deleteById(it.intValue("scheduleId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("payroll_history",
                onUpsert = { payrollHistoryDao.upsert(it.toLocalPayrollHistory()) },
                onDelete = { payrollHistoryDao.deleteById(it.intValue("payrollId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("bonus_records",
                onUpsert = { bonusRecordDao.upsert(it.toLocalBonusRecord()) },
                onDelete = { bonusRecordDao.deleteById(it.intValue("bonusId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("tax_declarations",
                onUpsert = { taxDeclarationDao.upsert(it.toLocalTaxDeclaration()) },
                onDelete = { taxDeclarationDao.deleteById(it.intValue("declarationId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("fbp_components",
                onUpsert = { fbpComponentDao.upsert(it.toLocalFbpComponent()) },
                onDelete = { fbpComponentDao.deleteById(it.intValue("componentId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            observe("fbp_declarations",
                onUpsert = { fbpDeclarationDao.upsert(it.toLocalFbpDeclaration()) },
                onDelete = { fbpDeclarationDao.deleteById(it.intValue("declarationId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
        }
    }

    private fun observe(
        table: String,
        onUpsert: suspend (DataSnapshot) -> Unit,
        onDelete: suspend (DataSnapshot) -> Unit
    ) {
        val ref = firebaseSync.getOwnerRef()?.child(table) ?: return
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch { runCatching { onUpsert(snapshot) } }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch { runCatching { onUpsert(snapshot) } }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                scope.launch { runCatching { onDelete(snapshot) } }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addChildEventListener(listener)
        listeners += ref to listener
    }

    @Synchronized
    fun stop() {
        hydrationJob?.cancel()
        hydrationJob = null
        listeners.forEach { (query, listener) -> query.removeEventListener(listener) }
        listeners.clear()
    }

    private fun DataSnapshot.childValue(name: String): Any? {
        val exact = child(name)
        if (exact.exists()) return exact.value
        val lower = name.replaceFirstChar { it.lowercase() }
        val lowerSnapshot = child(lower)
        if (lowerSnapshot.exists()) return lowerSnapshot.value
        val upper = name.replaceFirstChar { it.uppercase() }
        val upperSnapshot = child(upper)
        if (upperSnapshot.exists()) return upperSnapshot.value
        return null
    }

    private fun DataSnapshot.stringValue(name: String): String? =
        childValue(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun DataSnapshot.intValue(name: String): Int? =
        when (val v = childValue(name)) {
            is Number -> v.toInt()
            else -> v?.toString()?.toIntOrNull()
        }

    private fun DataSnapshot.longValue(name: String): Long =
        when (val v = childValue(name)) {
            is Number -> v.toLong()
            else -> {
                val s = v?.toString().orEmpty()
                s.toLongOrNull() ?: runCatching {
                    java.time.Instant.parse(s).toEpochMilli()
                }.getOrDefault(0L)
            }
        }

    private fun DataSnapshot.doubleValue(name: String): Double =
        when (val v = childValue(name)) {
            is Number -> v.toDouble()
            else -> v?.toString()?.toDoubleOrNull() ?: 0.0
        }

    private fun DataSnapshot.booleanValue(name: String): Boolean =
        when (val v = childValue(name)) {
            is Boolean -> v
            else -> v?.toString()?.toBooleanStrictOrNull() ?: false
        }

    private fun Shop.toLocal() = LocalShop(shopId, name, location, openingDate, isActive, 1)
    private fun Employee.toLocal() = LocalEmployee(employeeId, shopId, name, role, isActive, 1)
    private fun Attendance.toLocal() = LocalAttendance(attendanceId, employeeId, checkInTime, checkOutTime, 1)
    private fun AdvancePayment.toLocal() = LocalAdvancePayment(advanceId, employeeId, shopId, amount, date, isRecovered, recoveryPaymentId, 1)
    private fun EmployeeHistory.toLocal() = LocalEmployeeHistory(
        historyId, employeeId, version, type, salaryType, oldValue, newValue, shiftStart, shiftEnd,
        breakHours, shift2Start, shift2End, weekendShiftStart, weekendShiftEnd, weekendBreakHours,
        weekendShift2Start, weekendShift2End, isBonusEligible, isPaidLeaveEligible, paidLeaveOnWeekdays,
        paidLeaveOnWeekends, rulesOverrideJson, changeDate, effectiveDate, endDate, changeReason, salaryRate, 1
    )
    private fun ShopClosedDay.toLocal() = LocalShopClosedDay(id, shopId, date, paySalary, reason, affectedEmployeeIds, 1)
    private fun RegularizationRequest.toLocal() = LocalRegularizationRequest(
        id, staffId, staffName, date, punchType, originalTime, requestedTime, reason, status, adminRemarks, submittedAt, 1
    )
    private fun AttendancePunch.toLocal() = LocalAttendancePunch(
        punchId, staffId, date, type, timestamp, latitude, longitude, accuracy, source, status, 1
    )
    private fun LeaveRequest.toLocal() = LocalLeaveRequest(
        id, staffId, staffName, leaveType, startDate, endDate, reason, status, adminNotes, isHalfDay, createdAt, 1
    )
    private fun ResignationRequest.toLocal() = LocalResignationRequest(
        requestId, employeeId, submissionDate, desiredLastWorkingDay, reason, status,
        approvedLastWorkingDay, adminRemarks, isSettled, 1
    )

    private fun DataSnapshot.toLocalSalarySnapshot(): LocalSalarySnapshot = LocalSalarySnapshot(
        snapshotId = stringValue("snapshotId") ?: key.orEmpty(),
        employeeId = stringValue("employeeId").orEmpty(),
        shopId = stringValue("shopId").orEmpty(),
        periodStart = longValue("periodStart"),
        periodEnd = longValue("periodEnd"),
        totalNormalWorkedHours = doubleValue("totalNormalWorkedHours"),
        totalOTHours = doubleValue("totalOTHours"),
        presentDaysCount = intValue("presentDaysCount") ?: 0,
        closedShopDaysCount = intValue("closedShopDaysCount") ?: 0,
        totalAllowanceMoney = doubleValue("totalAllowanceMoney"),
        dayWiseEarningsJson = stringValue("dayWiseEarningsJson").orEmpty(),
        dayWiseWorkedHoursJson = stringValue("dayWiseWorkedHoursJson").orEmpty(),
        createdAt = longValue("createdAt"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalAuditLog(): LocalAuditLog = LocalAuditLog(
        logId = stringValue("logId") ?: key.orEmpty(),
        shopId = stringValue("shopId").orEmpty(),
        action = stringValue("action").orEmpty(),
        module = stringValue("module").orEmpty(),
        oldValue = stringValue("oldValue"),
        newValue = stringValue("newValue"),
        userDisplayName = stringValue("userDisplayName").orEmpty(),
        userId = stringValue("userId").orEmpty(),
        timestamp = longValue("timestamp"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalDailySummary(): LocalDailySummary = LocalDailySummary(
        summaryId = intValue("summaryId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        shiftDate = stringValue("shiftDate").orEmpty(),
        status = stringValue("status").orEmpty(),
        earnedStandardHours = doubleValue("earnedStandardHours"),
        totalOvertimeMs = longValue("totalOvertimeMs"),
        totalPenaltyMs = longValue("totalPenaltyMs"),
        totalLatenessMs = longValue("totalLatenessMs"),
        totalBreakPenaltyMs = longValue("totalBreakPenaltyMs"),
        scheduledShiftDurationMs = longValue("scheduledShiftDurationMs"),
        shiftAllowanceEarned = doubleValue("shiftAllowanceEarned"),
        isManualOverride = booleanValue("isManualOverride"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalShiftSchedule(): LocalShiftSchedule = LocalShiftSchedule(
        scheduleId = intValue("scheduleId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        shiftDate = stringValue("shiftDate").orEmpty(),
        startTime = stringValue("startTime").orEmpty(),
        endTime = stringValue("endTime").orEmpty(),
        isRecurringPattern = booleanValue("isRecurringPattern"),
        patternDurationDays = intValue("patternDurationDays") ?: 0,
        appliesToDayOfWeek = intValue("appliesToDayOfWeek") ?: 0,
        syncState = 1
    )

    private fun DataSnapshot.toLocalPayrollHistory(): LocalPayrollHistory = LocalPayrollHistory(
        payrollId = intValue("payrollId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        payMonth = intValue("payMonth") ?: 0,
        payYear = intValue("payYear") ?: 0,
        baseSalary = doubleValue("baseSalary"),
        totalHoursWorked = doubleValue("totalHoursWorked"),
        overtimePay = doubleValue("overtimePay"),
        deductionsHours = doubleValue("deductionsHours"),
        deductionsAdvance = doubleValue("deductionsAdvance"),
        bonus = doubleValue("bonus"),
        netSalary = doubleValue("netSalary"),
        manualLeaveDays = intValue("manualLeaveDays") ?: 0,
        absentDays = intValue("absentDays") ?: 0,
        totalPenaltyMs = longValue("totalPenaltyMs"),
        totalOvertimeMs = longValue("totalOvertimeMs"),
        hourlyRate = doubleValue("hourlyRate"),
        basicComponent = doubleValue("basicComponent"),
        pfDeduction = doubleValue("pfDeduction"),
        esiDeduction = doubleValue("esiDeduction"),
        employerPfContribution = doubleValue("employerPfContribution"),
        employerEsiContribution = doubleValue("employerEsiContribution"),
        ptDeduction = doubleValue("ptDeduction"),
        tdsDeduction = doubleValue("tdsDeduction"),
        totalShiftAllowance = doubleValue("totalShiftAllowance"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalBonusRecord(): LocalBonusRecord = LocalBonusRecord(
        bonusId = intValue("bonusId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        bonusDate = longValue("bonusDate"),
        amount = doubleValue("amount"),
        description = stringValue("description"),
        payrollIdPaid = intValue("payrollIdPaid"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalTaxDeclaration(): LocalTaxDeclaration = LocalTaxDeclaration(
        declarationId = intValue("declarationId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        financialYear = intValue("financialYear") ?: 0,
        regime = stringValue("regime").orEmpty(),
        section80C = doubleValue("section80C"),
        section80D = doubleValue("section80D"),
        hraRentPaid = doubleValue("hraRentPaid"),
        otherExemptions = doubleValue("otherExemptions"),
        status = stringValue("status").orEmpty(),
        adminRemarks = stringValue("adminRemarks"),
        submissionDate = longValue("submissionDate"),
        approvalDate = stringValue("approvalDate")?.let { s ->
            s.toLongOrNull() ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
        },
        syncState = 1
    )

    private fun DataSnapshot.toLocalFbpComponent(): LocalFbpComponent = LocalFbpComponent(
        componentId = intValue("componentId") ?: key.orEmpty().toIntOrNull() ?: 0,
        name = stringValue("name").orEmpty(),
        maxAnnualLimit = doubleValue("maxAnnualLimit"),
        isActive = booleanValue("isActive"),
        isTaxExempt = booleanValue("isTaxExempt"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalFbpDeclaration(): LocalFbpDeclaration = LocalFbpDeclaration(
        declarationId = intValue("declarationId") ?: key.orEmpty().toIntOrNull() ?: 0,
        employeeId = intValue("employeeId") ?: 0,
        financialYear = intValue("financialYear") ?: 0,
        componentName = stringValue("componentName").orEmpty(),
        annualAllocatedAmount = doubleValue("annualAllocatedAmount"),
        monthlyAllocatedAmount = doubleValue("monthlyAllocatedAmount"),
        status = stringValue("status").orEmpty(),
        submissionDate = longValue("submissionDate"),
        isActive = booleanValue("isActive"),
        adminRemarks = stringValue("adminRemarks"),
        syncState = 1
    )
}
