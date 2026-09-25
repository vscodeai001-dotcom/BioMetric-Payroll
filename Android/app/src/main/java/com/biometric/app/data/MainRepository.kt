package com.biometric.app.data

import android.content.Context
import android.content.SharedPreferences
import com.biometric.app.data.entity.*
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.StringUtil
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import androidx.core.content.edit
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.biometric.app.backup.BackupWorker
import com.biometric.app.data.dao.*
import com.biometric.app.util.DateRangeUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.math.BigDecimal
import java.util.*
import com.biometric.app.ui.viewmodel.AuditSummary
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@OptIn(ExperimentalCoroutinesApi::class)
class MainRepository(
    private val context: Context,
    val firebaseSync: FirebaseSyncManager,
    private val firebaseRoomHydrator: com.biometric.app.sync.FirebaseRoomHydrator,
    val dataSafety: DataSafetyManager,
    private val localShopDao: LocalShopDao,
    private val localEmployeeDao: LocalEmployeeDao,
    private val localAttendanceDao: LocalAttendanceDao,
    private val advanceDao: LocalAdvancePaymentDao,
    private val historyDao: LocalEmployeeHistoryDao,
    private val closedDayDao: LocalShopClosedDayDao,
    private val regularizationDao: LocalRegularizationRequestDao,
    private val localAttendancePunchDao: LocalAttendancePunchDao,
    private val localLeaveRequestDao: LocalLeaveRequestDao,
    private val localResignationRequestDao: LocalResignationRequestDao,
    private val localDailySummaryDao: LocalDailySummaryDao,
    private val localShiftScheduleDao: LocalShiftScheduleDao,
    private val localPayrollHistoryDao: LocalPayrollHistoryDao,
    private val localSettingsDao: LocalSettingsDao
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- MAPPING HELPERS ---
    private fun LocalShop.toDomain() = Shop(
        shopId = shopId,
        name = name,
        location = location,
        openingDate = openingDate,
        isActive = isActive,
        syncState = syncState,
        lastModified = lastModified,
    )

    private fun LocalEmployee.toDomain() = Employee(
        employeeId = employeeId,
        shopId = shopId,
        name = name,
        role = role,
        isActive = isActive,
        salaryType = salaryType,
        salaryRate = salaryRate,
        basicSalaryComponent = basicSalaryComponent,
        hraComponent = hraComponent,
        daComponent = daComponent,
        standardHours = standardHours,
        otRule = otRule,
        otFlatRate = otFlatRate,
        otRateMultiplier = otRateMultiplier,
        salaryCalculationMethod = salaryCalculationMethod,
        compOffDayOfWeek = compOffDayOfWeek,
        enablePf = enablePf,
        enableEsi = enableEsi,
        tdsRatePercent = tdsRatePercent,
        syncState = syncState,
        lastModified = lastModified
    )

    private fun LocalAttendance.toDomain() = Attendance(
        attendanceId = attendanceId,
        employeeId = employeeId,
        checkInTime = checkInTime,
        checkOutTime = checkOutTime,
        syncState = syncState,
        lastModified = lastModified
    )

    private fun LocalAdvancePayment.toDomain() = AdvancePayment(
        advanceId = advanceId,
        employeeId = employeeId,
        shopId = shopId,
        amount = amount,
        date = date,
        isRecovered = isRecovered,
        recoveryPaymentId = recoveryPaymentId
    )

    private fun LocalEmployeeHistory.toDomain() = EmployeeHistory(
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
        salaryRate = salaryRate
    )

    private fun LocalShopClosedDay.toDomain() = ShopClosedDay(
        id = id,
        shopId = shopId,
        date = date,
        paySalary = paySalary,
        reason = reason,
        affectedEmployeeIds = affectedEmployeeIds
    )

    private fun LocalRegularizationRequest.toDomain() = RegularizationRequest(
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
        submittedAt = submittedAt
    )

    private fun LocalLeaveRequest.toDomain() = LeaveRequest(
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
        createdAt = createdAt
    )

    private fun LocalResignationRequest.toDomain() = ResignationRequest(
        requestId = requestId,
        employeeId = employeeId,
        submissionDate = submissionDate,
        desiredLastWorkingDay = desiredLastWorkingDay,
        reason = reason,
        status = status,
        approvedLastWorkingDay = approvedLastWorkingDay,
        adminRemarks = adminRemarks,
        isSettled = isSettled
    )

    private fun LocalAttendancePunch.toDomain() = AttendancePunch(
        punchId = punchId,
        staffId = staffId,
        date = date,
        type = type,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        source = source,
        status = status
    )

    private fun LocalAuditLog.toDomain() = AuditLog(
        logId = logId,
        shopId = shopId,
        action = action,
        module = module,
        oldValue = oldValue,
        newValue = newValue,
        userDisplayName = userDisplayName,
        userId = userId,
        timestamp = timestamp
    )

    // --- SHARED DATA FLOWS ---
    val allShopsFlow = localShopDao.getAllShops()
        .map { list -> list.map { it.toDomain().copy(name = StringUtil.toTitleCase(it.name)) } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allHistoryFlow = historyDao.getAllFlow()
        .map { list -> list.map { it.toDomain().copy(changeReason = StringUtil.toTitleCase(it.changeReason)) } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allClosedDaysFlow = closedDayDao.getAllFlow()
        .map { list -> list.map { it.toDomain().copy(reason = StringUtil.toTitleCase(it.reason)) } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allAdvancesFlow = advanceDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allEmployeesFlow = localEmployeeDao.getAllFlow()
        .map { list -> list.map { it.toDomain().copy(name = StringUtil.toTitleCase(it.name)) } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allAttendanceFlow = localAttendanceDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allAttendancePunchesFlow = localAttendancePunchDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allRegularizationsFlow = regularizationDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allLeaveRequestsFlow = localLeaveRequestDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allResignationRequestsFlow = localResignationRequestDao.getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allDailySummariesFlow = localDailySummaryDao.getAllFlow()
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allShiftSchedulesFlow = localShiftScheduleDao.getAllFlow()
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val allPayrollHistoriesFlow = localPayrollHistoryDao.getAllFlow()
        .stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

    val companySettingsFlow = localSettingsDao.getCompanySettingsFlow()
        .stateIn(repositoryScope, SharingStarted.Eagerly, null)

    val featureSettingsFlow = localSettingsDao.getFeatureSettingsFlow()
        .stateIn(repositoryScope, SharingStarted.Eagerly, null)

    // --- GENERIC LIST CACHING ---
    private val dashboardPrefs: SharedPreferences by lazy { context.getSharedPreferences("dashboard_optimistic_cache", Context.MODE_PRIVATE) }

    fun <T> saveListCache(key: String, list: List<T>) {
        val json = Gson().toJson(list)
        dashboardPrefs.edit { putString("list_cache_$key", json) }
    }

    fun getAuditSummaryCache(key: String): AuditSummary? {
        val json = dashboardPrefs.getString("list_cache_$key", null) ?: return null
        return try {
            val type = object : TypeToken<List<AuditSummary>>() {}.type
            val list = Gson().fromJson<List<AuditSummary>>(json, type)
            list.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun triggerBackup() {
        BackupWorker.trigger(context)
    }

    private fun notifyDataChanged() {
        repositoryScope.launch {
            try {
                val profile = getProfileFlow().firstOrNull() ?: return@launch
                profile.dataLastModified = System.currentTimeMillis()
                updateProfile(profile)
            } catch (e: Exception) {
                Log.e("MainRepository", "Failed to update dataLastModified", e)
            }
        }
        triggerBackup()
    }

    private val auth = FirebaseAuth.getInstance()

    // ---------------- USER PROFILE ----------------
    fun getProfileFlow(): Flow<UserProfile?> {
        val uid = auth.currentUser?.uid ?: return flowOf(null)
        return firebaseSync.getGlobalItemFlow<UserProfile>("user_profiles/$uid")
    }
    suspend fun updateProfile(profile: UserProfile) = firebaseSync.pushProfile(profile)
    
    suspend fun fetchProfileByPhone(phone: String): UserProfile? = runCatching {
        val digitsOnly = phone.filter { it.isDigit() }
        val formats = mutableListOf<String>()
        when (digitsOnly.length) {
            10 -> {
                formats.add("+91$digitsOnly")
                formats.add(digitsOnly)
            }
            12 -> {
                formats.add("+$digitsOnly")
                formats.add(digitsOnly)
                formats.add(digitsOnly.substring(2))
            }
            else -> {
                formats.add(phone)
                formats.add("+$digitsOnly")
            }
        }

        val ref = firebaseSync.getGlobalRef().child("user_profiles")
        for (f in formats.distinct()) {
            val snapshot = ref.orderByChild("phone").equalTo(f).get().await()
            snapshot.children.firstOrNull()?.getValue(UserProfile::class.java)?.let { return@runCatching it }
        }

        val allSnapshot = ref.get().await()
        allSnapshot.children.asSequence()
            .mapNotNull { it.getValue(UserProfile::class.java) }
            .find { it.phone.filter { char -> char.isDigit() }.contains(digitsOnly.takeLast(10)) }
    }.getOrNull()

    suspend fun pushProfileByUid(profile: UserProfile) {
        firebaseSync.pushProfile(profile)
    }
    
    // ---------------- SHOP ----------------
    fun getAllShops(): Flow<List<Shop>> = allShopsFlow

    suspend fun insertShop(shop: Shop) = withContext(Dispatchers.IO) {
        shop.name = StringUtil.toTitleCase(shop.name)
        val local = LocalShop(
            shopId = shop.shopId,
            name = shop.name,
            location = shop.location,
            openingDate = shop.openingDate,
            isActive = shop.isActive,
            syncState = shop.syncState,
            lastModified = shop.lastModified
        )
        localShopDao.upsert(local)
        firebaseSync.pushShop(shop)
        notifyDataChanged()
    }

    suspend fun deleteShop(shopId: String) = withContext(Dispatchers.IO) {
        localShopDao.getById(shopId)?.let { local ->
            localShopDao.upsert(local.copy(isActive = false))
                dataSafety.recordDeletion("SHOP", shopId, local, "Shop: ${local.name}")
            firebaseSync.deleteShop(shopId)
            notifyDataChanged()
        }
    }

    // ---------------- WORKFORCE ----------------
    fun getShopEmployees(shopId: String?): Flow<List<Employee>> {
        return allEmployeesFlow.map { employees ->
            if (shopId.isNullOrEmpty()) employees
            else employees.filter { it.shopId == shopId }
        }
    }

    fun getAllEmployees(): Flow<List<Employee>> = allEmployeesFlow

    fun getAttendanceForPeriod(shopId: String?, start: Long, end: Long): Flow<List<Attendance>> {
        return allAttendanceFlow.map { list ->
            list.filter { (shopId.isNullOrEmpty() || it.shopId == shopId) && it.checkInTime in start..end }
        }.flowOn(Dispatchers.Default)
    }

    fun getEmployee(employeeId: String): Flow<Employee?> = flow {
        val local = withContext(Dispatchers.IO) { localEmployeeDao.getById(employeeId) }
        emit(local?.toDomain())
    }

    suspend fun insertEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        employee.name = StringUtil.toTitleCase(employee.name)
        val local = LocalEmployee(
            employeeId = employee.employeeId,
            shopId = employee.shopId,
            name = employee.name,
            role = employee.role,
            isActive = employee.isActive,
            salaryType = employee.salaryType,
            salaryRate = employee.salaryRate,
            basicSalaryComponent = employee.basicSalaryComponent,
            hraComponent = employee.hraComponent,
            daComponent = employee.daComponent,
            standardHours = employee.standardHours,
            otRule = employee.otRule,
            otFlatRate = employee.otFlatRate,
            otRateMultiplier = employee.otRateMultiplier,
            salaryCalculationMethod = employee.salaryCalculationMethod,
            compOffDayOfWeek = employee.compOffDayOfWeek,
            enablePf = employee.enablePf,
            enableEsi = employee.enableEsi,
            tdsRatePercent = employee.tdsRatePercent,
            syncState = employee.syncState,
            lastModified = employee.lastModified
        )
        localEmployeeDao.upsert(local)
        firebaseSync.pushEmployee(employee)
        notifyDataChanged()
    }

    suspend fun updateEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        val local = LocalEmployee(
            employeeId = employee.employeeId,
            shopId = employee.shopId,
            name = employee.name,
            role = employee.role,
            isActive = employee.isActive,
            salaryType = employee.salaryType,
            salaryRate = employee.salaryRate,
            basicSalaryComponent = employee.basicSalaryComponent,
            hraComponent = employee.hraComponent,
            daComponent = employee.daComponent,
            standardHours = employee.standardHours,
            otRule = employee.otRule,
            otFlatRate = employee.otFlatRate,
            otRateMultiplier = employee.otRateMultiplier,
            salaryCalculationMethod = employee.salaryCalculationMethod,
            compOffDayOfWeek = employee.compOffDayOfWeek,
            enablePf = employee.enablePf,
            enableEsi = employee.enableEsi,
            tdsRatePercent = employee.tdsRatePercent,
            syncState = 0,
            lastModified = System.currentTimeMillis()
        )
        localEmployeeDao.upsert(local)
        firebaseSync.pushEmployee(employee)
        notifyDataChanged()
    }

    suspend fun deleteEmployee(employee: Employee) {
        val deletedEmployee = employee.copy(isActive = false, terminateDate = null)
        dataSafety.recordDeletion("STAFF", employee.employeeId, employee, "Staff: ${employee.name}")
        firebaseSync.pushEmployee(deletedEmployee)
        notifyDataChanged()
    }

    suspend fun deleteEmployeeHistory(history: EmployeeHistory) {
        dataSafety.recordDeletion("STAFF_HISTORY", history.historyId, history, "History Update")
        firebaseSync.deleteHistory(history.historyId)
        notifyDataChanged()
    }

    fun pushHistoryAtomic(history: EmployeeHistory) {
        firebaseSync.pushHistoryAtomic(history)
    }

    fun getEmployeeHistoryFlow(employeeId: String): Flow<List<EmployeeHistory>> =
        allHistoryFlow.map { history ->
            history.asSequence().filter { it.employeeId == employeeId }.sortedBy { it.version }.toList()
        }.flowOn(Dispatchers.Default)

    fun getBatchEmployeeStatsFlow(
        employees: List<Employee>,
        start: Long,
        end: Long,
        selDayStart: Long,
        selDayEnd: Long,
    ): Flow<Map<String, EmployeeStats>> {
        if (employees.isEmpty()) return flowOf(emptyMap())
        val buffer = 86400000L
        val employeeIds = employees.map { it.employeeId }.toSet()

        return combine(
            allAttendanceFlow.map { it.filter { a -> a.checkInTime in (start - buffer)..(end + buffer) } },
            allClosedDaysFlow,
            allAdvancesFlow,
            allHistoryFlow,
            allShopsFlow
        ) { args: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val allAttendance = args[0] as List<Attendance>
            @Suppress("UNCHECKED_CAST")
            val allClosedDays = args[1] as List<ShopClosedDay>
            @Suppress("UNCHECKED_CAST")
            val allAdvances = args[2] as List<AdvancePayment>
            @Suppress("UNCHECKED_CAST")
            val allHistory = args[3] as List<EmployeeHistory>
            @Suppress("UNCHECKED_CAST")
            val shops = args[4] as List<Shop>

            val attendanceByEmp = allAttendance.filter { employeeIds.contains(it.employeeId) }.groupBy { it.employeeId }
            val advancesByEmp = allAdvances.filter { employeeIds.contains(it.employeeId) }.groupBy { it.employeeId }
            val historyByEmp = allHistory.groupBy { it.employeeId }
            val shopMap = shops.associateBy { it.shopId }

            employees.associate { employee ->
                val stats = SalaryEngine.calculateStats(
                    employee = employee,
                    monthStart = start,
                    monthEnd = end,
                    allAttendance = attendanceByEmp[employee.employeeId] ?: emptyList(),
                    closedDays = allClosedDays.filter { it.shopId == employee.shopId },
                    pendingAdvance = advancesByEmp[employee.employeeId]?.filter { !it.isRecovered }?.sumOf { it.amount } ?: 0.0,
                    history = historyByEmp[employee.employeeId]?.sortedBy { it.version } ?: emptyList(),
                    selDayStart = selDayStart,
                    selDayEnd = selDayEnd,
                    rules = shopMap[employee.shopId]?.salaryRules ?: SalaryRules(),
                    snapshot = null
                ).first
                employee.employeeId to stats
            }
        }.flowOn(Dispatchers.Default)
    }

    fun getClosedDays(shopId: String?, start: Long, end: Long): Flow<List<ShopClosedDay>> = allClosedDaysFlow
        .map { days ->
            days.filter { (shopId.isNullOrEmpty() || it.shopId == shopId) && it.date in start..end }
        }.flowOn(Dispatchers.Default)

    suspend fun markAttendance(employee: Employee, date: Date = Date()) {
        val todayStart = DateRangeUtil.getStartOfDay(date.time)
        val records = getAttendanceFlow(employee.employeeId, todayStart, todayStart + 86400000).first()
        val lastRecord = records.find { it.checkOutTime == null }

        if (lastRecord != null) {
            updateAttendance(lastRecord.copy(checkOutTime = date.time))
        } else {
            insertAttendance(
                Attendance(
                    attendanceId = UUID.randomUUID().toString(),
                    employeeId = employee.employeeId,
                    shopId = employee.shopId,
                    checkInTime = date.time,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun getAttendanceFlow(employeeId: String, start: Long, end: Long): Flow<List<Attendance>> {
        return allAttendanceFlow.map { list ->
            list.filter { it.employeeId == employeeId && it.checkInTime in start..end }
                .sortedByDescending { it.checkInTime }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun insertAttendance(att: Attendance) = withContext(Dispatchers.IO) {
        if (att.attendanceId.isBlank()) {
            att.attendanceId = UUID.randomUUID().toString()
        }
        val local = LocalAttendance(
            attendanceId = att.attendanceId,
            employeeId = att.employeeId,
            checkInTime = att.checkInTime,
            checkOutTime = att.checkOutTime,
            syncState = att.syncState,
            lastModified = att.lastModified
        )
        localAttendanceDao.upsert(local)
        firebaseSync.pushAttendance(att)
        clearSalarySnapshots(att.employeeId)
        notifyDataChanged()
    }

    suspend fun updateAttendance(att: Attendance) {
        firebaseSync.pushAttendance(att)
        clearSalarySnapshots(att.employeeId)
        notifyDataChanged()
    }

    suspend fun deleteAttendance(att: Attendance) {
        dataSafety.recordDeletion("ATTENDANCE", att.attendanceId, att, "Attendance Record")
        clearSalarySnapshots(att.employeeId)
        firebaseSync.deleteAttendance(att.attendanceId)
        notifyDataChanged()
    }

    suspend fun insertPunch(punch: AttendancePunch) = withContext(Dispatchers.IO) {
        if (punch.punchId.isBlank()) {
            punch.punchId = UUID.randomUUID().toString()
        }
        val local = LocalAttendancePunch(
            punchId = punch.punchId,
            staffId = punch.staffId,
            date = punch.date,
            type = punch.type,
            timestamp = punch.timestamp,
            latitude = punch.latitude,
            longitude = punch.longitude,
            accuracy = punch.accuracy,
            source = punch.source,
            status = punch.status,
            syncState = 0,
            lastModified = System.currentTimeMillis()
        )
        localAttendancePunchDao.upsert(local)
        firebaseSync.pushAttendancePunch(punch)
        notifyDataChanged()
    }

    suspend fun deletePunch(punch: AttendancePunch) = withContext(Dispatchers.IO) {
        val local = LocalAttendancePunch(
            punchId = punch.punchId,
            staffId = punch.staffId,
            date = punch.date,
            type = punch.type,
            timestamp = punch.timestamp,
            latitude = punch.latitude,
            longitude = punch.longitude,
            accuracy = punch.accuracy,
            source = punch.source,
            status = punch.status
        )
        localAttendancePunchDao.delete(local)
        firebaseSync.deletePunch(punch.punchId)
        notifyDataChanged()
    }

    // ---------------- REGULARIZATION ----------------
    suspend fun updateRegularizationStatus(requestId: String, status: String, remarks: String?) = withContext(Dispatchers.IO) {
        val existing = regularizationDao.getAllFlow().first().find { it.id == requestId }
        if (existing != null) {
            regularizationDao.upsert(existing.copy(status = status, adminRemarks = remarks, syncState = 0))
            }
    }

    fun getPendingRegularizations(): Flow<List<RegularizationRequest>> = allRegularizationsFlow
        .map { list -> list.filter { it.status == "Pending" }.sortedByDescending { it.submittedAt } }

    // ---------------- LEAVE ----------------
    suspend fun updateLeaveStatus(requestId: String, status: String, remarks: String?) = withContext(Dispatchers.IO) {
        val existing = localLeaveRequestDao.getAllFlow().first().find { it.id == requestId }
        if (existing != null) {
            localLeaveRequestDao.upsert(existing.copy(status = status, adminNotes = remarks, syncState = 0))
            }
    }

    // ---------------- RESIGNATION ----------------
    suspend fun updateResignationStatus(requestId: String, status: String, remarks: String?) = withContext(Dispatchers.IO) {
        val existing = localResignationRequestDao.getAllFlow().first().find { it.requestId == requestId }
        if (existing != null) {
            localResignationRequestDao.upsert(existing.copy(status = status, adminRemarks = remarks, syncState = 0))
            }
    }

    fun getAdvanceRecords(employeeId: String, start: Long, end: Long): Flow<List<AdvancePayment>> = allAdvancesFlow
        .map { advances ->
            advances.filter { it.employeeId == employeeId && it.date in start..end }
                .sortedByDescending { it.date }
        }.flowOn(Dispatchers.Default)

    suspend fun insertAdvance(adv: AdvancePayment) = withContext(Dispatchers.IO) {
        val local = LocalAdvancePayment(
            advanceId = adv.advanceId.ifBlank { UUID.randomUUID().toString() },
            employeeId = adv.employeeId,
            shopId = adv.shopId,
            amount = adv.amount,
            date = adv.date,
            isRecovered = adv.isRecovered,
            recoveryPaymentId = adv.recoveryPaymentId,
            syncState = 0
        )
        advanceDao.upsert(local)
        firebaseSync.pushAdvance(adv)
        notifyDataChanged()
    }

    suspend fun updateAdvance(adv: AdvancePayment) = withContext(Dispatchers.IO) {
        val local = LocalAdvancePayment(
            advanceId = adv.advanceId,
            employeeId = adv.employeeId,
            shopId = adv.shopId,
            amount = adv.amount,
            date = adv.date,
            isRecovered = adv.isRecovered,
            recoveryPaymentId = adv.recoveryPaymentId,
            syncState = 0
        )
        advanceDao.upsert(local)
        firebaseSync.pushAdvance(adv)
        notifyDataChanged()
    }

    suspend fun deleteAdvance(adv: AdvancePayment) = withContext(Dispatchers.IO) {
        dataSafety.recordDeletion("ADVANCE", adv.advanceId, adv, "Advance: ₹${adv.amount}")
        advanceDao.delete(LocalAdvancePayment(advanceId = adv.advanceId, employeeId = "", shopId = "", amount = 0.0, date = 0, isRecovered = false, recoveryPaymentId = null))
        firebaseSync.deleteAdvance(adv.advanceId)
        notifyDataChanged()
    }

    fun insertSalaryPayment(payment: SalaryPayment) = firebaseSync.pushSalaryPayment(payment)

    suspend fun insertClosedDay(day: ShopClosedDay) = withContext(Dispatchers.IO) {
        day.reason = StringUtil.toTitleCase(day.reason)
        val local = LocalShopClosedDay(
            id = day.id.ifBlank { UUID.randomUUID().toString() },
            shopId = day.shopId,
            date = day.date,
            paySalary = day.paySalary,
            reason = day.reason,
            affectedEmployeeIds = day.affectedEmployeeIds,
            syncState = 0
        )
        closedDayDao.upsert(local)
        firebaseSync.pushClosedDay(day)
        notifyDataChanged()
    }

    suspend fun deleteClosedDay(day: ShopClosedDay) = withContext(Dispatchers.IO) {
        dataSafety.recordDeletion("CLOSED_DAY", day.id, day, "Closed Day")
        closedDayDao.delete(LocalShopClosedDay(id = day.id, shopId = "", date = 0, paySalary = false, reason = null, affectedEmployeeIds = emptyList()))
        firebaseSync.deleteClosedDay(day.id)
        notifyDataChanged()
    }

    // ---------------- SALARY ENGINE ----------------

    fun getEmployeeStatsFlow(
        employee: Employee,
        start: Long,
        end: Long,
        selDayStart: Long? = null,
        selDayEnd: Long? = null,
    ): Flow<EmployeeStats> {
        val buffer = 86400000L
        return combine(
            getAttendanceFlow(employee.employeeId, start - buffer, end + buffer),
            allClosedDaysFlow,
            allAdvancesFlow,
            getEmployeeHistoryFlow(employee.employeeId),
            allShopsFlow
        ) { args: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val allAttendance = args[0] as List<Attendance>
            @Suppress("UNCHECKED_CAST")
            val closedDays = args[1] as List<ShopClosedDay>
            @Suppress("UNCHECKED_CAST")
            val allAdvances = args[2] as List<AdvancePayment>
            @Suppress("UNCHECKED_CAST")
            val history = args[3] as List<EmployeeHistory>
            @Suppress("UNCHECKED_CAST")
            val shops = args[4] as List<Shop>

            val shop = shops.find { it.shopId == employee.shopId }
            val empAdvances = allAdvances.filter { it.employeeId == employee.employeeId }
            val totalPending = empAdvances.filter { !it.isRecovered }.sumOf { it.amount }
            val monthAdvance = empAdvances.filter { it.date in start..end }.sumOf { it.amount }

            val (stats, _) = SalaryEngine.calculateStats(
                employee = employee,
                monthStart = start,
                monthEnd = end,
                allAttendance = allAttendance,
                closedDays = closedDays.filter { it.shopId == employee.shopId },
                pendingAdvance = totalPending,
                history = history,
                selDayStart = selDayStart ?: start,
                selDayEnd = selDayEnd ?: end,
                rules = shop?.salaryRules ?: SalaryRules(),
                snapshot = null
            )

            stats.monthlyAdvance = monthAdvance
            stats
        }.flowOn(Dispatchers.Default)
    }

    @Suppress("UNCHECKED_CAST")
    fun getProjectedSalaryFlow(
        shopId: String?,
        start: Long,
        end: Long,
        includeBonus: Boolean = true
    ): Flow<Double> {
        return combine(
            getShopEmployees(shopId),
            allAttendanceFlow.map { it.filter { a -> a.checkInTime in (start - 86400000L)..(end + 86400000L) } },
            allClosedDaysFlow,
            allAdvancesFlow,
            allHistoryFlow,
            allShopsFlow
        ) { args: Array<Any?> ->
            val employees = args[0] as List<Employee>
            val allAttendance = args[1] as List<Attendance>
            val allClosedDays = args[2] as List<ShopClosedDay>
            val allAdvances = args[3] as List<AdvancePayment>
            val allHistory = args[4] as List<EmployeeHistory>
            val allShops = args[5] as List<Shop>

            var totalStaffLiability = 0.0
            if (employees.isEmpty()) return@combine totalStaffLiability

            val attendanceMap = allAttendance.groupBy { it.employeeId }
            val closedDaysMap = allClosedDays.groupBy { it.shopId }
            val advancesMap = allAdvances.groupBy { it.employeeId }
            val historyMap = allHistory.groupBy { it.employeeId }
            val shopMap = allShops.associateBy { it.shopId }

            employees.forEach { employee ->
                val (stats, _) = SalaryEngine.calculateStats(
                    employee = employee,
                    monthStart = start,
                    monthEnd = end,
                    allAttendance = attendanceMap[employee.employeeId] ?: emptyList(),
                    closedDays = closedDaysMap[employee.shopId] ?: emptyList(),
                    pendingAdvance = advancesMap[employee.employeeId]?.filter { !it.isRecovered }?.sumOf { it.amount } ?: 0.0,
                    history = (historyMap[employee.employeeId] ?: emptyList()).sortedBy { it.version },
                    selDayStart = start,
                    selDayEnd = end,
                    rules = shopMap[employee.shopId]?.salaryRules ?: SalaryRules(),
                    snapshot = null
                )
                totalStaffLiability += stats.getRoundedPeriodLiability(start, end, includeBonus)
            }
            totalStaffLiability
        }.flowOn(Dispatchers.Default)
    }

    suspend fun calculateProjectedSalary(
        shopId: String?,
        start: Long,
        end: Long,
        includeBonus: Boolean = true
    ): Double = getProjectedSalaryFlow(shopId, start, end, includeBonus).firstOrNull() ?: 0.0

    // ---------------- AUDIT TRAIL ----------------
    fun getAuditLogsSummary(shopId: String?, start: Long, end: Long): Flow<List<AuditLog>> {
        return firebaseSync.getDataFlow<AuditLog>("audit_logs").map { logs ->
            logs.filter { (shopId.isNullOrEmpty() || it.shopId == shopId) && it.timestamp in start..end }
        }.flowOn(Dispatchers.Default)
    }

    fun getAuditLogsPaged(shopId: String?, start: Long, end: Long, search: String = ""): Flow<PagingData<AuditLog>> {
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false, initialLoadSize = 100)
        ) {
            AuditPagingSource(firebaseSync, shopId, start, end, search)
        }.flow
    }

    fun clearSalarySnapshots(employeeId: String) {
        firebaseSync.getOwnerRef()?.child("salary_snapshots")?.orderByChild("employeeId")?.equalTo(employeeId)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { it.ref.removeValue() }
                    repositoryScope.launch {
                        runCatching {
                            firebaseSync.notifyRealtimeAfterWrite("SalarySnapshot", "DELETED")
                        }.onFailure { e ->
                            Log.e("MainRepository", "Failed to publish SalarySnapshot realtime change", e)
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ---------------- RECYCLE BIN ----------------
    fun getRecentRecycleBinItems(limit: Int = 100): Flow<List<RecycleBinItem>> {
        val query = firebaseSync.getOwnerRef()?.child("recycle_bin")?.orderByChild("timestamp")?.limitToLast(limit)
        return if (query == null) flowOf(emptyList())
        else firebaseSync.getQueryFlow<RecycleBinItem>(query).map { items -> items.sortedByDescending { it.timestamp } }
            .flowOn(Dispatchers.Default)
    }

    fun startSync() {
        firebaseSync.startSync()
        firebaseRoomHydrator.start()
    }

}
