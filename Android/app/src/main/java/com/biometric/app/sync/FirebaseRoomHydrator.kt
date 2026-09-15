package com.biometric.app.sync

import com.biometric.app.data.dao.LocalAdvancePaymentDao
import com.biometric.app.data.dao.LocalAttendanceDao
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.data.dao.LocalEmployeeHistoryDao
import com.biometric.app.data.dao.LocalLeaveRequestDao
import com.biometric.app.data.dao.LocalRegularizationRequestDao
import com.biometric.app.data.dao.LocalResignationRequestDao
import com.biometric.app.data.dao.LocalShopClosedDayDao
import com.biometric.app.data.dao.LocalShopDao
import com.biometric.app.data.entity.LocalAdvancePayment
import com.biometric.app.data.entity.LocalAttendance
import com.biometric.app.data.entity.LocalAttendancePunch
import com.biometric.app.data.entity.LocalEmployee
import com.biometric.app.data.entity.LocalEmployeeHistory
import com.biometric.app.data.entity.LocalLeaveRequest
import com.biometric.app.data.entity.LocalRegularizationRequest
import com.biometric.app.data.entity.LocalResignationRequest
import com.biometric.app.data.entity.LocalShop
import com.biometric.app.data.entity.LocalShopClosedDay
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
 * Firebase is the realtime source for Android application data. Room remains
 * the local/offline cache used by the existing repositories and screens.
 * This class only mirrors Firebase records into the existing Room tables. It
 * does not change entities, schema, calculations, layouts, or business rules.
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
    private val resignationDao: LocalResignationRequestDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hydrationJob: Job? = null

    @Synchronized
    fun start() {
        if (hydrationJob?.isActive == true) return
        if (!firebaseSync.isAuthenticated()) return

        firebaseSync.startSync()

        hydrationJob = scope.launch {
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.Shop>("shops").collectLatest { items ->
                items.forEach { item -> shopDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.Employee>("employees").collectLatest { items ->
                items.forEach { item -> employeeDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.Attendance>("attendance").collectLatest { items ->
                items.forEach { item -> attendanceDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.AdvancePayment>("advance_payments").collectLatest { items ->
                items.forEach { item -> advanceDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.EmployeeHistory>("employee_history").collectLatest { items ->
                items.forEach { item -> historyDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.ShopClosedDay>("shop_closed_days").collectLatest { items ->
                items.forEach { item -> closedDayDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.RegularizationRequest>("regularizations").collectLatest { items ->
                items.forEach { item -> regularizationDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.AttendancePunch>("attendance_punches").collectLatest { items ->
                items.forEach { item -> punchDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.LeaveRequest>("leave_requests").collectLatest { items ->
                items.forEach { item -> leaveDao.upsert(item.toLocal()) }
            } }
            launch { firebaseSync.getDataFlow<com.biometric.app.data.entity.ResignationRequest>("resignation_requests").collectLatest { items ->
                items.forEach { item -> resignationDao.upsert(item.toLocal()) }
            } }
        }
    }

    fun stop() {
        hydrationJob?.cancel()
        hydrationJob = null
    }

    private fun com.biometric.app.data.entity.Shop.toLocal() = LocalShop(
        shopId = shopId,
        name = name,
        location = location,
        openingDate = openingDate,
        isActive = isActive,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.Employee.toLocal() = LocalEmployee(
        employeeId = employeeId,
        shopId = shopId,
        name = name,
        role = role,
        isActive = isActive,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.Attendance.toLocal() = LocalAttendance(
        attendanceId = attendanceId,
        employeeId = employeeId,
        checkInTime = checkInTime,
        checkOutTime = checkOutTime,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.AdvancePayment.toLocal() = LocalAdvancePayment(
        advanceId = advanceId,
        employeeId = employeeId,
        shopId = shopId,
        amount = amount,
        date = date,
        isRecovered = isRecovered,
        recoveryPaymentId = recoveryPaymentId,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.EmployeeHistory.toLocal() = LocalEmployeeHistory(
        historyId = historyId,
        employeeId = employeeId,
        version = version,
        type = type,
        salaryType = salaryType,
        oldValue = oldValue,
        newValue = newValue,
        shiftStart = shiftStart,
        shiftEnd = shiftEnd,
        breakHours = breakHours,
        shift2Start = shift2Start,
        shift2End = shift2End,
        weekendShiftStart = weekendShiftStart,
        weekendShiftEnd = weekendShiftEnd,
        weekendBreakHours = weekendBreakHours,
        weekendShift2Start = weekendShift2Start,
        weekendShift2End = weekendShift2End,
        isBonusEligible = isBonusEligible,
        isPaidLeaveEligible = isPaidLeaveEligible,
        paidLeaveOnWeekdays = paidLeaveOnWeekdays,
        paidLeaveOnWeekends = paidLeaveOnWeekends,
        rulesOverrideJson = rulesOverrideJson,
        changeDate = changeDate,
        effectiveDate = effectiveDate,
        endDate = endDate,
        changeReason = changeReason,
        salaryRate = salaryRate,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.ShopClosedDay.toLocal() = LocalShopClosedDay(
        id = id,
        shopId = shopId,
        date = date,
        paySalary = paySalary,
        reason = reason,
        affectedEmployeeIds = affectedEmployeeIds,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.RegularizationRequest.toLocal() = LocalRegularizationRequest(
        id = id,
        staffId = staffId,
        staffName = staffName,
        date = date,
        punchType = punchType,
        originalTime = originalTime,
        requestedTime = requestedTime,
        reason = reason,
        status = status,
        adminRemarks = adminRemarks,
        submittedAt = submittedAt,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.AttendancePunch.toLocal() = LocalAttendancePunch(
        punchId = punchId,
        staffId = staffId,
        date = date,
        type = type,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        source = source,
        status = status,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.LeaveRequest.toLocal() = LocalLeaveRequest(
        id = id,
        staffId = staffId,
        staffName = staffName,
        leaveType = leaveType,
        startDate = startDate,
        endDate = endDate,
        reason = reason,
        status = status,
        adminNotes = adminNotes,
        isHalfDay = isHalfDay,
        createdAt = createdAt,
        syncState = 1
    )

    private fun com.biometric.app.data.entity.ResignationRequest.toLocal() = LocalResignationRequest(
        requestId = requestId,
        employeeId = employeeId,
        submissionDate = submissionDate,
        desiredLastWorkingDay = desiredLastWorkingDay,
        reason = reason,
        status = status,
        approvedLastWorkingDay = approvedLastWorkingDay,
        adminRemarks = adminRemarks,
        isSettled = isSettled,
        syncState = 1
    )
}
