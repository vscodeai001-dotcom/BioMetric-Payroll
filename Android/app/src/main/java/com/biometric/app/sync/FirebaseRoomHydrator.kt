package com.biometric.app.sync

import android.util.Log
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
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalTaxDeclarationDao
import com.biometric.app.data.entity.*
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val sessionStore: MobileSessionStore,
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
    private val writeMutex = Mutex()
    private val listeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val valueListeners = mutableListOf<Pair<Query, ValueEventListener>>()
    @Volatile private var activeOwnerUid: String? = null

    @Synchronized
    fun start() {
        if (!firebaseSync.isAuthenticated()) return

        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() } ?: return

        if (hydrationJob?.isActive == true && activeOwnerUid == ownerUid) return

        if (activeOwnerUid != null && activeOwnerUid != ownerUid) {
            stop()
        }

        firebaseSync.startSync()
        activeOwnerUid = ownerUid

        hydrationJob = scope.launch {
            // Core employee/self-service tables are hydrated from raw snapshots.
            // Firebase records migrated from the Web DB can store employee IDs as
            // numbers while Android's existing Room/domain models use String.
            // Raw mapping prevents Firebase's strict getValue() mapper from
            // dropping the records and leaving the Admin dashboard empty.
            observeValue("shops", existing = { shopDao.getAllRecords().map { it.shopId to it.syncState } }, onDelete = { key -> shopDao.deleteById(key) }) { it.toShop().let { value -> shopDao.upsert(value.toLocal()) } }
            observeValue("employees", existing = { employeeDao.getAllRecords().map { it.employeeId to it.syncState } }, onDelete = { key -> employeeDao.deleteById(key) }) { it.toEmployee().let { value -> employeeDao.upsert(value.toLocal()) } }
            observeValue("attendance", existing = { attendanceDao.getAll().map { it.attendanceId to it.syncState } }, onDelete = { key -> attendanceDao.deleteById(key) }) { it.toAttendance().let { value -> attendanceDao.upsert(value.toLocal()) } }
            observeValue("advance_payments", existing = { advanceDao.getAll().map { it.advanceId to it.syncState } }, onDelete = { key -> advanceDao.deleteById(key) }) { it.toAdvancePayment().let { value -> advanceDao.upsert(value.toLocal()) } }
            observeValue("employee_history", existing = { historyDao.getAll().map { it.historyId to it.syncState } }, onDelete = { key -> historyDao.deleteById(key) }) { it.toEmployeeHistory().let { value -> historyDao.upsert(value.toLocal()) } }
            observeValue("shop_closed_days", existing = { closedDayDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> closedDayDao.deleteById(key) }) { it.toShopClosedDay().let { value -> closedDayDao.upsert(value.toLocal()) } }
            observeValue("regularizations", existing = { regularizationDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> regularizationDao.deleteById(key) }) { it.toRegularization().let { value -> regularizationDao.upsert(value.toLocal()) } }
            observeValue("attendance_punches", existing = { punchDao.getAll().map { it.punchId to it.syncState } }, onDelete = { key -> punchDao.deleteById(key) }) { it.toAttendancePunch().let { value -> punchDao.upsert(value.toLocal()) } }
            observeValue("leave_requests", existing = { leaveDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> leaveDao.deleteById(key) }) { it.toLeaveRequest().let { value -> leaveDao.upsert(value.toLocal()) } }
            observeValue("resignation_requests", existing = { resignationDao.getAll().map { it.requestId to it.syncState } }, onDelete = { key -> resignationDao.deleteById(key) }) { it.toResignation().let { value -> resignationDao.upsert(value.toLocal()) } }

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
            observeShiftSchedules()
            observePayrollHistory()
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

    private fun observeValue(
        table: String,
        existing: suspend () -> List<Pair<String, Int>>,
        onDelete: suspend (String) -> Unit,
        onUpsert: suspend (DataSnapshot) -> Unit
    ) {
        val ref = firebaseSync.getOwnerRef()?.child(table) ?: return
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch { hydrate(table, snapshot, onUpsert) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch { hydrate(table, snapshot, onUpsert) }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                scope.launch {
                    writeMutex.withLock {
                        runCatching { onDelete(key) }
                            .onFailure { error ->
                                Log.e("FirebaseRoomHydrator", "Failed to delete Room record $table/$key", error)
                            }
                    }
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                // Defensive: Permission denial during logout is expected and should not be logged as a severe error.
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Hydration listener cancelled for $table: ${error.message}")
                }
            }
        }
        ref.addChildEventListener(listener)
        // Reconcile the existing Room cache against the authoritative Firebase
        // snapshot once after listener registration. Wait briefly to allow 
        // initial child-added events to settle.
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                scope.launch {
                    delay(2000L) // Increased stagger for reconciliation
                    writeMutex.withLock {
                        runCatching {
                            val firebaseKeys = snapshot.children.mapNotNull { it.key }.toSet()
                            val staleSynced = existing().asSequence()
                                .filter { (id, syncState) -> syncState != 0 && id.isNotBlank() && id !in firebaseKeys }
                                .map { it.first }
                                .toList()
                            
                            if (staleSynced.isNotEmpty()) {
                                Log.d("FirebaseRoomHydrator", "Cleaning up ${staleSynced.size} stale records for $table")
                                staleSynced.forEach { onDelete(it) }
                            }
                        }.onFailure { error ->
                            Log.e("FirebaseRoomHydrator", "Failed to reconcile stale Room records for $table", error)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseRoomHydrator", "Initial reconciliation cancelled for $table", error.toException())
            }
        })
        // Keep the ChildEventListener alive for the application lifetime.
        listeners += ref to listener
    }

    private suspend fun hydrate(
        table: String,
        snapshot: DataSnapshot,
        onUpsert: suspend (DataSnapshot) -> Unit
    ) {
        writeMutex.withLock {
            runCatching { onUpsert(snapshot) }
                .onFailure { error ->
                    Log.e("FirebaseRoomHydrator", "Failed to hydrate $table/${snapshot.key}", error)
                }
        }
    }

    private fun DataSnapshot.raw(name: String): Any? {
        val names = listOf(name, name.replaceFirstChar { it.lowercase() }, name.replaceFirstChar { it.uppercase() })
        return names.asSequence().map { child(it) }.firstOrNull { it.exists() }?.value
    }
    private fun DataSnapshot.s(name: String): String? = raw(name)?.toString()?.takeIf { it.isNotBlank() }
    private fun DataSnapshot.i(name: String): Int = when (val v = raw(name)) { is Number -> v.toInt(); else -> v?.toString()?.toIntOrNull() ?: 0 }
    private fun DataSnapshot.l(name: String): Long = when (val v = raw(name)) { is Number -> v.toLong(); else -> v?.toString()?.toLongOrNull() ?: 0L }
    private fun DataSnapshot.d(name: String): Double = when (val v = raw(name)) { is Number -> v.toDouble(); else -> v?.toString()?.toDoubleOrNull() ?: 0.0 }
    private fun DataSnapshot.b(name: String, default: Boolean = false): Boolean = when (val v = raw(name)) { is Boolean -> v; else -> v?.toString()?.toBooleanStrictOrNull() ?: default }

    private fun DataSnapshot.toShop() = Shop(
        shopId = s("shopId") ?: key.orEmpty(), name = s("name").orEmpty(), location = s("location").orEmpty(),
        openingDate = l("openingDate"), isActive = b("isActive", true), createdAt = l("createdAt"), updatedAt = l("updatedAt"),
        latitude = d("latitude"), longitude = d("longitude")
    )
    private fun DataSnapshot.toEmployee() = Employee(
        employeeId = s("employeeId") ?: l("employeeId").toString().takeIf { it != "0" } ?: key.orEmpty(),
        shopId = s("shopId").orEmpty(), name = s("name").orEmpty(), role = s("role") ?: "Staff", isActive = b("isActive", true),
        syncState = 1, lastModified = l("lastModified")
    )
    private fun DataSnapshot.toAttendance() = Attendance(
        attendanceId = s("attendanceId") ?: key.orEmpty(), employeeId = s("employeeId") ?: l("employeeId").toString(),
        shopId = s("shopId").orEmpty(), checkInTime = l("checkInTime"), checkOutTime = l("checkOutTime").takeIf { it > 0 },
        type = s("type") ?: "WORK", hoursWorked = d("hoursWorked"), syncState = 1
    )
    private fun DataSnapshot.toAdvancePayment() = AdvancePayment(
        advanceId = s("advanceId") ?: key.orEmpty(), employeeId = s("employeeId") ?: l("employeeId").toString(),
        shopId = s("shopId").orEmpty(), amount = d("amount"), date = l("date"), isRecovered = b("isRecovered"), recoveryPaymentId = s("recoveryPaymentId")
    )
    private fun DataSnapshot.toEmployeeHistory() = EmployeeHistory(
        historyId = s("historyId") ?: key.orEmpty(), employeeId = s("employeeId") ?: l("employeeId").toString(), version = i("version"),
        type = s("type") ?: "SALARY", salaryType = s("salaryType").orEmpty(), oldValue = d("oldValue"), newValue = d("newValue"),
        shiftStart = s("shiftStart").orEmpty(), shiftEnd = s("shiftEnd").orEmpty(), breakHours = d("breakHours"),
        changeDate = l("changeDate"), effectiveDate = l("effectiveDate"), endDate = l("endDate").takeIf { it > 0 }, changeReason = s("changeReason"), salaryRate = d("salaryRate")
    )
    private fun DataSnapshot.toShopClosedDay() = ShopClosedDay(
        id = s("id") ?: key.orEmpty(), shopId = s("shopId").orEmpty(), date = l("date"), paySalary = b("paySalary", true), reason = s("reason"), affectedEmployeeIds = emptyList()
    )
    private fun DataSnapshot.toRegularization() = RegularizationRequest(
        id = s("id") ?: key.orEmpty(), staffId = s("staffId") ?: l("staffId").toString(), staffName = s("staffName").orEmpty(), date = s("date").orEmpty(),
        punchType = s("punchType") ?: "IN", originalTime = l("originalTime").takeIf { it > 0 }, requestedTime = l("requestedTime"),
        reason = s("reason").orEmpty(), status = s("status") ?: "Pending", adminRemarks = s("adminRemarks"), submittedAt = l("submittedAt")
    )
    private fun DataSnapshot.toAttendancePunch() = AttendancePunch(
        punchId = s("punchId") ?: key.orEmpty(), staffId = s("staffId") ?: l("staffId").toString(), date = s("date").orEmpty(),
        type = s("type") ?: "IN", timestamp = l("timestamp"), latitude = d("latitude"), longitude = d("longitude"), accuracy = d("accuracy").toFloat(),
        source = s("source") ?: "GEOFENCE", status = s("status") ?: "PENDING"
    )
    private fun DataSnapshot.toLeaveRequest() = LeaveRequest(
        id = s("id") ?: key.orEmpty(), staffId = s("staffId") ?: l("staffId").toString(), staffName = s("staffName").orEmpty(),
        leaveType = s("leaveType") ?: "Casual Leave", startDate = l("startDate"), endDate = l("endDate"), reason = s("reason").orEmpty(),
        status = s("status") ?: "Pending", adminNotes = s("adminNotes"), isHalfDay = b("isHalfDay"), createdAt = l("createdAt")
    )
    private fun DataSnapshot.toResignation() = ResignationRequest(
        requestId = s("requestId") ?: key.orEmpty(), employeeId = s("employeeId") ?: l("employeeId").toString(), submissionDate = l("submissionDate"),
        desiredLastWorkingDay = l("desiredLastWorkingDay"), reason = s("reason"), status = s("status") ?: "Pending",
        approvedLastWorkingDay = l("approvedLastWorkingDay").takeIf { it > 0 }, adminRemarks = s("adminRemarks"), isSettled = b("isSettled")
    )

    private fun observePayrollHistory() {
        val isEmployee = sessionStore.userRole().trim().uppercase() !in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
        val employeeId = sessionStore.employeeId()
        val query = if (isEmployee && employeeId > 0) {
            firebaseSync.getOwnerRef()?.child("payroll_history")
                ?.orderByChild("employeeId")
                ?.equalTo(employeeId.toDouble())
        } else {
            firebaseSync.getOwnerRef()?.child("payroll_history")
        } ?: return

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!isEmployee || snapshot.intValue("employeeId") == employeeId)
                        runCatching { payrollHistoryDao.upsert(snapshot.toLocalPayrollHistory()) }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!isEmployee || snapshot.intValue("employeeId") == employeeId)
                        runCatching { payrollHistoryDao.upsert(snapshot.toLocalPayrollHistory()) }
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.intValue("payrollId") ?: snapshot.key?.toIntOrNull() ?: return
                scope.launch { runCatching { payrollHistoryDao.deleteById(id) } }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Payroll history hydration cancelled: ${error.message}")
                }
            }
        }
        query.addChildEventListener(listener)
        listeners += query to listener
    }

    private fun observeShiftSchedules() {
        val employeeRole = sessionStore.userRole().trim().uppercase() !in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
        val employeeId = sessionStore.employeeId()
        val query = if (employeeRole && employeeId > 0) {
            firebaseSync.getOwnerRef()?.child("shift_schedules")
                ?.orderByChild("employeeId")
                ?.equalTo(employeeId.toDouble())
        } else {
            firebaseSync.getOwnerRef()?.child("shift_schedules")
        } ?: return

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!employeeRole || snapshot.intValue("employeeId") == employeeId)
                        runCatching { shiftScheduleDao.upsert(snapshot.toLocalShiftSchedule()) }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!employeeRole || snapshot.intValue("employeeId") == employeeId)
                        runCatching { shiftScheduleDao.upsert(snapshot.toLocalShiftSchedule()) }
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.intValue("scheduleId") ?: snapshot.key?.toIntOrNull() ?: return
                scope.launch { runCatching { shiftScheduleDao.deleteById(id) } }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Shift schedule hydration cancelled: ${error.message}")
                }
            }
        }
        query.addChildEventListener(listener)
        listeners += query to listener
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

        listeners.forEach { (query, listener) ->
            query.removeEventListener(listener)
        }
        listeners.clear()

        valueListeners.forEach { (query, listener) ->
            query.removeEventListener(listener)
        }
        valueListeners.clear()

        activeOwnerUid = null
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
