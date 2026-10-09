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
import com.biometric.app.data.dao.LocalSettingsDao
import com.biometric.app.data.dao.LocalTaxDeclarationDao
import com.biometric.app.data.AppDatabase
import com.biometric.app.data.entity.*
import com.google.firebase.database.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
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
    private val appDatabase: AppDatabase,
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
    private val fbpDeclarationDao: LocalFbpDeclarationDao,
    private val settingsDao: LocalSettingsDao,
    private val firebaseAuthTokenManager: FirebaseAuthTokenManager,
    private val realtimeUiDispatcher: RealtimeUiDispatcher,
    private val signalRManagerProvider: javax.inject.Provider<SignalRManager>
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hydrationJob: Job? = null
    private var reconnectJob: Job? = null
    private val writeMutex = Mutex()
    private val listeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val valueListeners = mutableListOf<Pair<Query, ValueEventListener>>()
    @Volatile private var activeOwnerUid: String? = null

    @Synchronized
    fun start() {
        if (!firebaseSync.isAuthenticated()) return

        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() } ?: return

        if (activeOwnerUid == ownerUid && (listeners.isNotEmpty() || valueListeners.isNotEmpty())) return

        if (activeOwnerUid != null && activeOwnerUid != ownerUid) {
            stop()
        }

        firebaseSync.startSync()
        activeOwnerUid = ownerUid

        // ALWAYS observe wipe events for ALL users (both Admin and Employee).
        // If admin wipes cloud data while mobile was offline, the moment mobile gets network
        // this listener fires and clears stale local Room tables.
        observeWipeEvents()

        val role = sessionStore.userRole().trim().uppercase()
        val isAdmin = role in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")

        hydrationJob = scope.launch {
            // Immediately purge any orphan OUT punch from local Room tables
            runCatching {
                punchDao.deleteById("AUTO_1_1791311430980")
                attendanceDao.deleteById("AUTO_1_1791311430980")
                attendanceDao.deleteById("47")
            }

            // Core operational and self-service tables are hydrated from raw snapshots for ALL roles.
            // Mobile app works fully independent of Web server state with complete offline Room caching.
            observeValue("shops", existing = { shopDao.getAllRecords().map { it.shopId to it.syncState } }, onDelete = { key -> shopDao.deleteById(key) }) { it.toShop().let { value -> shopDao.upsert(value.toLocal()) } }
            observeValue("employees", existing = { employeeDao.getAllRecords().map { it.employeeId to it.syncState } }, onDelete = { key -> employeeDao.deleteById(key) }) { it.toEmployee().let { value -> employeeDao.upsert(value.toLocal()) } }
            // SPARK PLAN OPTIMIZATION: Bound attendance to recent records (300). Prevents multi-MB historical download.
            observeValue("attendance", query = firebaseSync.getOwnerRef()?.child("attendance")?.limitToLast(300), existing = { attendanceDao.getAll().map { it.attendanceId to it.syncState } }, onDelete = { key -> attendanceDao.deleteById(key) }) { it.toAttendance().let { value -> attendanceDao.upsert(value.toLocal()) } }
            observeValue("advance_payments", existing = { advanceDao.getAll().map { it.advanceId to it.syncState } }, onDelete = { key -> advanceDao.deleteById(key) }) { it.toAdvancePayment().let { value -> advanceDao.upsert(value.toLocal()) } }
            observeValue("employee_history", existing = { historyDao.getAll().map { it.historyId to it.syncState } }, onDelete = { key -> historyDao.deleteById(key) }) { it.toEmployeeHistory().let { value -> historyDao.upsert(value.toLocal()) } }
            observeValue("shop_closed_days", existing = { closedDayDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> closedDayDao.deleteById(key) }) { it.toShopClosedDay().let { value -> closedDayDao.upsert(value.toLocal()) } }
            observeValue("regularizations", existing = { regularizationDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> regularizationDao.deleteById(key) }) { it.toRegularization().let { value -> regularizationDao.upsert(value.toLocal()) } }
            // SPARK PLAN OPTIMIZATION: Bound attendance_punches to last 500 punches. Realtime new punches still fire via onChildAdded.
            observeValue("attendance_punches", query = firebaseSync.getOwnerRef()?.child("attendance_punches")?.limitToLast(500), existing = { punchDao.getAll().map { it.punchId to it.syncState } }, onDelete = { key -> punchDao.deleteById(key) }) { it.toAttendancePunch().let { value -> punchDao.upsert(value.toLocal()) } }
            observeValue("leave_requests", existing = { leaveDao.getAll().map { it.id to it.syncState } }, onDelete = { key -> leaveDao.deleteById(key) }) { it.toLeaveRequest().let { value -> leaveDao.upsert(value.toLocal()) } }
            observeValue("resignation_requests", existing = { resignationDao.getAll().map { it.requestId to it.syncState } }, onDelete = { key -> resignationDao.deleteById(key) }) { it.toResignation().let { value -> resignationDao.upsert(value.toLocal()) } }

            observeShiftSchedules()
            observePayrollHistory()

            observeValue("company_settings", existing = { emptyList() }, onDelete = { }) { 
                if (it.key == "1") settingsDao.upsertCompanySettings(it.toLocalCompanySettings())
            }
            val ownerUid = firebaseSync.getOwnerUid()
            val defaultUid = com.biometric.app.sync.ssot.FirebaseSsotSchema.DEFAULT_OWNER_UID
            val extraTargets = setOf(defaultUid, "tenant_201")
            for (extraUid in extraTargets) {
                if (ownerUid != null && ownerUid.equals(extraUid, ignoreCase = true)) continue
                val extraRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("owners").child(extraUid).child("company_settings").child("1")
                extraRef.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            scope.launch {
                                settingsDao.upsertCompanySettings(snapshot.toLocalCompanySettings())
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) = Unit
                })
            }
            if (ownerUid != null && !ownerUid.equals(defaultUid, ignoreCase = true)) {
                scope.launch {
                    try {
                        if (settingsDao.getCompanySettings() == null) {
                            val defaultSnap = com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("owners").child(defaultUid).child("company_settings").child("1").get().await()
                            if (defaultSnap.exists()) {
                                settingsDao.upsertCompanySettings(defaultSnap.toLocalCompanySettings())
                            }
                        }
                        if (settingsDao.getFeatureSettings() == null) {
                            val defaultFeatSnap = com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("owners").child(defaultUid).child("feature_settings").child("1").get().await()
                            if (defaultFeatSnap.exists()) {
                                val fs = defaultFeatSnap.toLocalFeatureSettings()
                                settingsDao.upsertFeatureSettings(fs)
                                sessionStore.setDeploymentMode(fs.deploymentMode)
                                sessionStore.setOfflineMode(fs.isOfflineMode)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("FirebaseRoomHydrator", "One-time default settings fallback read skipped: ${e.message}")
                    }
                }
            }
            observeValue("feature_settings", existing = { emptyList() }, onDelete = { }) { 
                if (it.key == "1") {
                    val fs = it.toLocalFeatureSettings()
                    settingsDao.upsertFeatureSettings(fs)
                    sessionStore.setDeploymentMode(fs.deploymentMode)
                    sessionStore.setOfflineMode(fs.isOfflineMode)
                }
            }

            // Admin/SuperAdmin only heavy data collections.
            if (isAdmin) {
                observe("salary_snapshots",
                    query = firebaseSync.getOwnerRef()?.child("salary_snapshots")?.limitToLast(100),
                    onUpsert = { salarySnapshotDao.upsert(it.toLocalSalarySnapshot()) },
                    onDelete = { salarySnapshotDao.deleteById(it.stringValue("snapshotId") ?: it.key.orEmpty()) })
                // BANDWIDTH OPTIMIZATION: Do NOT globally observe audit_logs in background.
                // audit_logs is a massive append-only table. AuditTrailActivity queries its own
                // date-bounded paged range on demand, so downloading the entire collection here
                // wastes multiple megabytes of bandwidth on every app start.
                observe("daily_summaries",
                    query = firebaseSync.getOwnerRef()?.child("daily_summaries")?.limitToLast(300),
                    onUpsert = { dailySummaryDao.upsert(it.toLocalDailySummary()) },
                    onDelete = { dailySummaryDao.deleteById(it.intValue("summaryId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
                observe("bonus_records",
                    query = firebaseSync.getOwnerRef()?.child("bonus_records")?.limitToLast(100),
                    onUpsert = { bonusRecordDao.upsert(it.toLocalBonusRecord()) },
                    onDelete = {
                        val key = it.key.orEmpty()
                        val id = it.intValue("bonusId")
                            ?: it.intValue("BonusID")
                            ?: key.toIntOrNull()
                            ?: (if (key.isNotBlank()) Math.abs(key.hashCode()).let { h -> if (h == 0) 1 else h } else 0)
                        bonusRecordDao.deleteByIdOrKey(id, key)
                    })
                observe("tax_declarations",
                    query = firebaseSync.getOwnerRef()?.child("tax_declarations")?.limitToLast(100),
                    onUpsert = { taxDeclarationDao.upsert(it.toLocalTaxDeclaration()) },
                    onDelete = { taxDeclarationDao.deleteById(it.intValue("declarationId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
                observe("fbp_components",
                    query = firebaseSync.getOwnerRef()?.child("fbp_components")?.limitToLast(100),
                    onUpsert = { fbpComponentDao.upsert(it.toLocalFbpComponent()) },
                    onDelete = { fbpComponentDao.deleteById(it.intValue("componentId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
                observe("fbp_declarations",
                    query = firebaseSync.getOwnerRef()?.child("fbp_declarations")?.limitToLast(100),
                    onUpsert = { fbpDeclarationDao.upsert(it.toLocalFbpDeclaration()) },
                    onDelete = { fbpDeclarationDao.deleteById(it.intValue("declarationId") ?: it.key.orEmpty().toIntOrNull() ?: return@observe) })
            }
        }
    }

    private val wipeListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()

    private fun observeWipeEvents() {
        for ((ref, l) in wipeListeners) {
            ref.removeEventListener(l)
        }
        wipeListeners.clear()

        val refsToListen = mutableListOf<DatabaseReference>()
        firebaseSync.getOwnerRef()?.child("system_events")?.child("wipe")?.let { refsToListen.add(it) }

        val defaultUid = com.biometric.app.sync.ssot.FirebaseSsotSchema.DEFAULT_OWNER_UID
        if (firebaseSync.getOwnerUid() != defaultUid) {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("owners").child(defaultUid).child("system_events").child("wipe")
                .let { refsToListen.add(it) }
        }

        for (wipeRef in refsToListen) {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val timestamp = snapshot.child("timestamp").value?.toString()?.toLongOrNull() ?: 0L
                    val wipeType = snapshot.child("wipeType").value?.toString()?.uppercase() ?: "PARTIAL"
                    val lastProcessed = sessionStore.lastProcessedWipeTimestamp()
                    if (timestamp > lastProcessed) {
                        sessionStore.setLastProcessedWipeTimestamp(timestamp)
                        Log.i("FirebaseRoomHydrator", "Real-time WIPE event received from cloud: type=$wipeType, ts=$timestamp (lastProcessed=$lastProcessed)")
                        scope.launch {
                            handleRealtimeWipe(isFull = (wipeType == "FULL"), wipeTimestamp = timestamp)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("FirebaseRoomHydrator", "Wipe event listener cancelled: ${error.message}")
                }
            }
            wipeListeners.add(wipeRef to listener)
            wipeRef.addValueEventListener(listener)
        }
    }

    suspend fun handleRealtimeWipe(isFull: Boolean, wipeTimestamp: Long = 0L) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val db = appDatabase.openHelper.writableDatabase
                    val operationalTables = listOf(
                        "local_attendance",
                        "local_attendance_punches",
                        "daily_summaries",
                        "payroll_history",
                        "local_advance_payments",
                        "local_bonus_records",
                        "local_leave_requests",
                        "local_resignation_requests",
                        "local_regularization_requests",
                        "shift_schedules",
                        "local_tax_declarations",
                        "local_fbp_declarations",
                        "local_fbp_components",
                        "local_salary_snapshots",
                        "local_audit_logs",
                        "offline_tracking_events"
                    )
                    for (t in operationalTables) {
                        try { db.execSQL("DELETE FROM $t") } catch (e: Exception) { }
                    }

                    if (isFull) {
                        val masterTables = listOf(
                            "local_employees",
                            "local_employee_history",
                            "local_shops",
                            "local_shop_closed_days"
                        )
                        for (t in masterTables) {
                            try { db.execSQL("DELETE FROM $t") } catch (e: Exception) { }
                        }
                    }

                    try {
                        appDatabase.invalidationTracker.refreshVersionsAsync()
                    } catch (e: Exception) {
                        Log.w("FirebaseRoomHydrator", "Could not refresh Room versions on wipe: ${e.message}")
                    }

                    Log.i("FirebaseRoomHydrator", "Local database cleared in response to remote wipe event (isFull=$isFull, ts=$wipeTimestamp)")
                } catch (e: Exception) {
                    Log.e("FirebaseRoomHydrator", "Failed to clear local Room on wipe event", e)
                }
            }
        }

        runCatching {
            signalRManagerProvider.get().clearLiveState()
        }

        withContext(Dispatchers.Main) {
            realtimeUiDispatcher.refreshVisible()
        }

        val role = sessionStore.userRole().trim().uppercase()
        val isAdmin = role in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
        if (isAdmin) {
            forceRebind("Post-wipe resync")
        }
    }

    private fun observeValue(
        table: String,
        query: Query? = null,
        existing: suspend () -> List<Pair<String, Int>>,
        onDelete: suspend (String) -> Unit,
        onUpsert: suspend (DataSnapshot) -> Unit
    ) {
        val targetQuery: Query = query ?: (firebaseSync.getOwnerRef()?.child(table) ?: return)
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
                    triggerUiRefresh()
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    // PERMISSION_DENIED can happen during a custom-token race:
                    // the native Firebase Auth token is restored on cold-start
                    // before the custom token (owner_uid/role claims) is minted.
                    // Schedule a delayed rebind to give the custom token time to
                    // propagate (typically 5-10 seconds after login).
                    Log.w("FirebaseRoomHydrator",
                        "Listen at $table cancelled: Permission denied — scheduling token-recovery rebind")
                    scheduleRebind("$table PERMISSION_DENIED — custom token race recovery")
                    return
                }
                // Firebase listeners can be cancelled by an expired/rotated auth
                // token or a transient connection boundary.
                scheduleRebind("$table cancelled: ${error.message}")
            }
        }
        // Small master & transactional request tables need explicit initial hydration & reconciliation
        if (query == null && table in setOf("shops", "employees", "shop_closed_days", "leave_requests", "regularizations", "resignation_requests", "advance_payments", "shift_schedules", "company_settings", "feature_settings")) {
            (targetQuery as? DatabaseReference)?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    scope.launch {
                        writeMutex.withLock {
                            runCatching {
                                if (snapshot.exists() && snapshot.childrenCount > 0) {
                                    // Guarantee immediate hydration of all records into Room
                                    for (child in snapshot.children) {
                                        runCatching { onUpsert(child) }
                                    }

                                    val firebaseKeys = snapshot.children.mapNotNull { it.key }.toSet()
                                    val staleSynced = existing().asSequence()
                                        .filter { (id, syncState) -> syncState != 0 && id.isNotBlank() && id !in firebaseKeys }
                                        .map { it.first }
                                        .toList()
                                    
                                    if (staleSynced.isNotEmpty()) {
                                        Log.d("FirebaseRoomHydrator", "Cleaning up ${staleSynced.size} stale records for $table")
                                        staleSynced.forEach { onDelete(it) }
                                    }
                                } else {
                                    // Firebase node has 0 records or was deleted: wipe cached synced records!
                                    val allStale = existing().filter { it.second != 0 }.map { it.first }
                                    if (allStale.isNotEmpty()) {
                                        Log.d("FirebaseRoomHydrator", "Cleaning up ${allStale.size} wiped records for $table")
                                        allStale.forEach { onDelete(it) }
                                    }
                                }
                            }.onFailure { error ->
                                Log.e("FirebaseRoomHydrator", "Failed to reconcile Room records for $table", error)
                            }
                        }
                        triggerUiRefresh()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("FirebaseRoomHydrator", "Initial reconciliation cancelled for $table: ${error.message}")
                }
            })
        }
        // CRITICAL: Attach the ChildEventListener to Firebase reference so onChildAdded/onChildChanged fire!
        targetQuery.addChildEventListener(listener)
        // Keep the ChildEventListener alive for the application lifetime.
        listeners += targetQuery to listener
    }

    private var uiRefreshJob: Job? = null
    private fun triggerUiRefresh() {
        uiRefreshJob?.cancel()
        uiRefreshJob = scope.launch {
            delay(300L)
            withContext(Dispatchers.Main) {
                realtimeUiDispatcher.refreshVisible()
            }
        }
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
        triggerUiRefresh()
    }

    private fun DataSnapshot.raw(name: String): Any? {
        val names = listOf(name, name.replaceFirstChar { it.lowercase() }, name.replaceFirstChar { it.uppercase() })
        return names.asSequence().map { child(it) }.firstOrNull { it.exists() }?.value
    }
    private fun DataSnapshot.s(name: String): String? = raw(name)?.toString()?.takeIf { it.isNotBlank() }
    private fun DataSnapshot.i(name: String): Int = when (val v = raw(name)) { is Number -> v.toInt(); else -> v?.toString()?.toIntOrNull() ?: 0 }
    private fun DataSnapshot.l(name: String): Long = when (val v = raw(name)) { is Number -> v.toLong(); else -> v?.toString()?.toLongOrNull() ?: 0L }
    private fun DataSnapshot.d(name: String, default: Double = 0.0): Double = when (val v = raw(name)) { is Number -> v.toDouble(); else -> v?.toString()?.toDoubleOrNull() ?: default }
    private fun DataSnapshot.b(name: String, default: Boolean = false): Boolean = when (val v = raw(name)) { is Boolean -> v; else -> v?.toString()?.toBooleanStrictOrNull() ?: default }
    private fun DataSnapshot.parseDayOfWeek(name: String): Int? {
        val v = raw(name) ?: return null
        if (v is Number) return v.toInt().takeIf { it in 0..6 }
        val str = v.toString().trim()
        str.toIntOrNull()?.let { return it.takeIf { d -> d in 0..6 } }
        return when (str.lowercase(java.util.Locale.ROOT)) {
            "sunday", "sun" -> 0
            "monday", "mon" -> 1
            "tuesday", "tue" -> 2
            "wednesday", "wed" -> 3
            "thursday", "thu" -> 4
            "friday", "fri" -> 5
            "saturday", "sat" -> 6
            else -> null
        }
    }
    private fun DataSnapshot.intAny(vararg names: String): Int? {
        for (name in names) {
            val v = raw(name)
            val parsed = when (v) {
                is Number -> v.toInt()
                else -> v?.toString()?.toIntOrNull()
            }
            if (parsed != null && parsed > 0) return parsed
        }
        return null
    }
    private fun DataSnapshot.doubleAny(vararg names: String): Double? {
        for (name in names) {
            val v = raw(name)
            val parsed = when (v) {
                is Number -> v.toDouble()
                else -> v?.toString()?.toDoubleOrNull()
            }
            if (parsed != null && parsed != 0.0) return parsed
        }
        return null
    }

    private fun DataSnapshot.toShop() = Shop(
        shopId = s("shopId") ?: key.orEmpty(),
        name = s("name").orEmpty(),
        location = s("location").orEmpty(),
        openingDate = l("openingDate"),
        isActive = b("isActive", true),
        brandingName = s("brandingName"),
        brandingLogoUrl = s("brandingLogoUrl"),
        salaryRules = toSalaryRules(),
        createdAt = l("createdAt"),
        updatedAt = l("updatedAt"),
        latitude = d("latitude"),
        longitude = d("longitude")
    )

    private fun DataSnapshot.toSalaryRules() = SalaryRules(
        newJoineeCutoffDay = i("newJoineeCutoffDay").takeIf { it > 0 } ?: 5,
        maxAbsencesForPaidLeave = i("maxAbsencesForPaidLeave"),
        maxAbsencesForBonus = i("maxAbsencesForBonus"),
        maxShortfallMinutesForBonus = i("maxShortfallMinutesForBonus"),
        paidLeaveDaysPool = i("paidLeaveDaysPool"),
        defaultShiftStart = s("defaultShiftStart") ?: "10:00",
        defaultShiftEnd = s("defaultShiftEnd") ?: "22:00",
        defaultShift2Start = s("defaultShift2Start"),
        defaultShift2End = s("defaultShift2End"),
        defaultBreakHours = d("defaultBreakHours"),
        defaultOtMultiplier = d("defaultOtMultiplier"),
        isBonusEligibleDefault = b("isBonusEligibleDefault", true),
        isPaidLeaveEligibleDefault = b("isPaidLeaveEligibleDefault", true),
        paidLeaveOnWeekdaysDefault = b("paidLeaveOnWeekdaysDefault", true),
        paidLeaveOnWeekendsDefault = b("paidLeaveOnWeekendsDefault", false)
    )

    private fun DataSnapshot.toEmployee() = Employee(
        employeeId = s("employeeId") ?: l("employeeId").toString().takeIf { it != "0" } ?: key.orEmpty(),
        shopId = s("shopId").orEmpty(),
        name = s("name").orEmpty(),
        phone = s("phone").orEmpty(),
        email = s("email"),
        biometricId = s("biometricId").orEmpty(),
        role = s("role") ?: "Staff",
        salaryType = s("salaryType") ?: "MONTHLY_FIXED",
        salaryRate = d("salaryRate").takeIf { it > 0 } ?: d("monthlySalary").takeIf { it > 0 } ?: d("salary"),
        paidLeaveBalance = d("paidLeaveBalance"),
        sickLeaveBalance = d("sickLeaveBalance"),
        salaryCalculationMethod = s("salaryCalculationMethod") ?: "Days in Month",
        shiftStart = s("shiftStart") ?: "10:00",
        shiftEnd = s("shiftEnd") ?: "22:00",
        shiftMode = s("shiftMode") ?: "SINGLE_DAY",
        trackingMode = s("trackingMode") ?: "24/7",
        breakHours = d("breakHours"),
        shift2Start = s("shift2Start"),
        shift2End = s("shift2End"),
        weekendShiftStart = s("weekendShiftStart"),
        weekendShiftEnd = s("weekendShiftEnd"),
        weekendBreakHours = d("weekendBreakHours").takeIf { it > 0 },
        weekendShift2Start = s("weekendShift2Start"),
        weekendShift2End = s("weekendShift2End"),
        compOffDayOfWeek = parseDayOfWeek("compOffDayOfWeek"),
        otRule = s("otRule") ?: "No Overtime",
        otFlatRate = d("otFlatRate"),
        otRateMultiplier = d("otRateMultiplier").takeIf { it > 0 } ?: 1.0,
        dailyAllowance = d("dailyAllowance"),
        nightShiftAllowance = d("nightShiftAllowance"),
        allowanceEffectiveDate = l("allowanceEffectiveDate"),
        isActive = b("isActive", true),
        hireDate = l("hireDate"),
        dob = l("dob").takeIf { it > 0 },
        terminateDate = l("terminateDate").takeIf { it > 0 },
        standardHours = i("standardHours")?.takeIf { it > 0 } ?: 8,
        basicSalaryComponent = d("basicSalaryComponent"),
        hraComponent = d("hraComponent"),
        daComponent = d("daComponent"),
        enableShiftRotation = b("enableShiftRotation"),
        rotationGroup = s("rotationGroup"),
        shiftRotationPattern = s("shiftRotationPattern"),
        createdAt = l("createdAt"),
        isBonusEligibleRule = b("isBonusEligibleRule", true),
        isPaidLeaveEligibleRule = b("isPaidLeaveEligibleRule", true),
        paidLeaveOnWeekdays = b("paidLeaveOnWeekdays", true),
        paidLeaveOnWeekends = b("paidLeaveOnWeekends", false),
        bankAccountNumber = s("bankAccountNumber"),
        bankIfscCode = s("bankIfscCode"),
        bankName = s("bankName"),
        uanNumber = s("uanNumber"),
        esiNumber = s("esiNumber"),
        enablePf = b("enablePf"),
        enableEsi = b("enableEsi"),
        tdsRatePercent = d("tdsRatePercent"),
        lastActive = l("lastActive").takeIf { it > 0 },
        syncState = 1,
        lastModified = l("lastModified")
    )
    private fun DataSnapshot.toAttendance() = Attendance(
        attendanceId = s("attendanceId") ?: key.orEmpty(),
        employeeId = s("employeeId") ?: l("employeeId").toString(),
        shopId = s("shopId").orEmpty(),
        checkInTime = l("checkInTime"),
        checkOutTime = l("checkOutTime").takeIf { it > 0 },
        type = s("type") ?: "WORK",
        hoursWorked = d("hoursWorked"),
        shiftStart = s("shiftStart") ?: "10:00",
        shiftEnd = s("shiftEnd") ?: "22:00",
        shift2Start = s("shift2Start"),
        shift2End = s("shift2End"),
        breakHours = d("breakHours"),
        salaryType = s("salaryType") ?: "MONTHLY_FIXED",
        salaryRate = d("salaryRate"),
        note = s("note"),
        synced = b("synced"),
        lateDeduction = d("lateDeduction"),
        otHours = d("otHours"),
        createdAt = l("createdAt"),
        syncState = 1,
        lastModified = l("lastModified")
    )
    private fun DataSnapshot.toAdvancePayment(): AdvancePayment {
        val eId = s("employeeId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: s("staffId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: l("employeeId").takeIf { it > 0 }?.toString()
            ?: l("staffId").takeIf { it > 0 }?.toString()
            ?: ""
        val advDate = l("date").takeIf { it > 0 }
            ?: l("advanceDate").takeIf { it > 0 }
            ?: s("advanceDate")?.let { str ->
                runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(str)?.time }.getOrNull()
            } ?: System.currentTimeMillis()
        return AdvancePayment(
            advanceId = s("advanceId") ?: s("id") ?: key.orEmpty(),
            employeeId = eId,
            shopId = s("shopId").orEmpty(),
            amount = d("amount"),
            date = advDate,
            isRecovered = b("isRecovered"),
            recoveryPaymentId = s("recoveryPaymentId")
        )
    }
    private fun DataSnapshot.toEmployeeHistory() = EmployeeHistory(
        historyId = s("historyId") ?: key.orEmpty(),
        employeeId = s("employeeId") ?: l("employeeId").toString(),
        version = i("version"),
        type = s("type") ?: "SALARY",
        salaryType = s("salaryType").orEmpty(),
        oldValue = d("oldValue"),
        newValue = d("newValue"),
        shiftStart = s("shiftStart").orEmpty(),
        shiftEnd = s("shiftEnd").orEmpty(),
        shiftMode = s("shiftMode") ?: "SINGLE_DAY",
        breakHours = d("breakHours"),
        shift2Start = s("shift2Start"),
        shift2End = s("shift2End"),
        weekendShiftStart = s("weekendShiftStart"),
        weekendShiftEnd = s("weekendShiftEnd"),
        weekendBreakHours = d("weekendBreakHours").takeIf { it > 0 },
        weekendShift2Start = s("weekendShift2Start"),
        weekendShift2End = s("weekendShift2End"),
        isBonusEligible = b("isBonusEligible", true),
        isPaidLeaveEligible = b("isPaidLeaveEligible", true),
        paidLeaveOnWeekdays = b("paidLeaveOnWeekdays", true),
        paidLeaveOnWeekends = b("paidLeaveOnWeekends", false),
        rulesOverrideJson = s("rulesOverrideJson"),
        changeDate = l("changeDate"),
        effectiveDate = l("effectiveDate"),
        endDate = l("endDate").takeIf { it > 0 },
        changeReason = s("changeReason"),
        salaryRate = d("salaryRate")
    )
    private fun DataSnapshot.toShopClosedDay() = ShopClosedDay(
        id = s("id") ?: key.orEmpty(), shopId = s("shopId").orEmpty(), date = l("date"), paySalary = b("paySalary", true), reason = s("reason"), affectedEmployeeIds = emptyList()
    )
    private fun DataSnapshot.toRegularization(): RegularizationRequest {
        val sId = s("staffId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: s("employeeId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: l("staffId").takeIf { it > 0 }?.toString()
            ?: l("employeeId").takeIf { it > 0 }?.toString()
            ?: ""
        val sName = s("staffName")?.takeIf { it.isNotBlank() }
            ?: s("employeeName")?.takeIf { it.isNotBlank() }
            ?: ""
        return RegularizationRequest(
            id = s("id") ?: key.orEmpty(),
            staffId = sId,
            staffName = sName,
            date = s("date") ?: s("dateOfPunch").orEmpty(),
            punchType = s("punchType") ?: if (b("isInPunch", true)) "IN" else "OUT",
            originalTime = l("originalTime").takeIf { it > 0 },
            requestedTime = l("requestedTime").takeIf { it > 0 } ?: l("punchTimeNew"),
            reason = s("reason").orEmpty(),
            status = s("status") ?: "Pending",
            adminRemarks = s("adminRemarks"),
            submittedAt = l("submittedAt").takeIf { it > 0 } ?: l("submissionDate").takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }
    private fun DataSnapshot.toAttendancePunch() = AttendancePunch(
        punchId = s("punchId") ?: s("attendanceId") ?: key.orEmpty(),
        staffId = s("staffId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: s("employeeId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: l("staffId").takeIf { it > 0 }?.toString()
            ?: l("employeeId").takeIf { it > 0 }?.toString()
            ?: "",
        date = s("date")?.takeIf { it.isNotBlank() }
            ?: (l("timestamp").takeIf { it > 0 } ?: l("checkInTime")).let {
                if (it > 0) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                    }
                    sdf.format(java.util.Date(it))
                } else ""
            },
        type = s("type") ?: s("punchType") ?: s("note") ?: "IN",
        timestamp = l("timestamp").takeIf { it > 0 } ?: l("checkInTime").takeIf { it > 0 } ?: l("createdAt"),
        latitude = d("latitude"),
        longitude = d("longitude"),
        accuracy = d("accuracy").toFloat(),
        geofenceId = s("geofenceId"),
        distanceFromGeofence = d("distanceFromGeofence"),
        photoId = s("photoId"),
        deviceId = s("deviceId") ?: "",
        source = s("source") ?: "GEOFENCE",
        status = s("status") ?: "APPROVED"
    )
    private fun DataSnapshot.toLeaveRequest(): LeaveRequest {
        val sId = s("staffId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: s("employeeId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: l("staffId").takeIf { it > 0 }?.toString()
            ?: l("employeeId").takeIf { it > 0 }?.toString()
            ?: ""
        val sName = s("staffName")?.takeIf { it.isNotBlank() }
            ?: s("employeeName")?.takeIf { it.isNotBlank() }
            ?: ""
        val literalLeaveDate = s("leaveDate")?.takeIf { it.isNotBlank() }
        val start = literalLeaveDate?.let { str ->
            runCatching {
                val trimmed = str.trim().take(10)
                java.time.LocalDate.parse(trimmed).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: l("startDate").takeIf { it > 0 } ?: 0L
        val end = l("endDate").takeIf { it > 0 } ?: start
        val isAppr = b("isApproved")
        val stat = s("status")?.takeIf { it.isNotBlank() } ?: if (isAppr) "Approved" else "Pending"
        val rsn = s("reason")?.takeIf { it.isNotBlank() } ?: s("notes").orEmpty()
        val admNotes = s("adminNotes")?.takeIf { it.isNotBlank() } ?: s("adminRemarks")

        return LeaveRequest(
            id = s("id") ?: s("leaveRequestId") ?: key.orEmpty(),
            staffId = sId,
            staffName = sName,
            leaveType = s("leaveType") ?: "Casual Leave",
            startDate = start,
            endDate = end,
            leaveDate = literalLeaveDate,
            reason = rsn,
            status = stat,
            adminNotes = admNotes,
            isHalfDay = b("isHalfDay"),
            createdAt = l("createdAt").takeIf { it > 0 } ?: start
        )
    }
    private fun DataSnapshot.toResignation(): ResignationRequest {
        val eId = s("employeeId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: s("staffId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: l("employeeId").takeIf { it > 0 }?.toString()
            ?: l("staffId").takeIf { it > 0 }?.toString()
            ?: ""
        val subDate = l("submissionDate").takeIf { it > 0 } ?: System.currentTimeMillis()
        val desDate = l("desiredLastWorkingDay").takeIf { it > 0 }
            ?: s("desiredLastWorkingDay")?.let { str ->
                runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(str)?.time }.getOrNull()
            } ?: subDate
        val appDate = l("approvedLastWorkingDay").takeIf { it > 0 }
            ?: s("approvedLastWorkingDay")?.let { str ->
                runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(str)?.time }.getOrNull()
            }
        return ResignationRequest(
            requestId = s("requestId") ?: s("id") ?: key.orEmpty(),
            employeeId = eId,
            submissionDate = subDate,
            desiredLastWorkingDay = desDate,
            reason = s("reason"),
            status = s("status") ?: "Pending",
            approvedLastWorkingDay = appDate,
            adminRemarks = s("adminRemarks"),
            isSettled = b("isSettled")
        )
    }

    private fun DataSnapshot.toLocalCompanySettings() = LocalCompanySettings(
        id = 1,
        companyName = s("companyName")?.takeIf { it.isNotBlank() && !it.equals("Your Company Name", ignoreCase = true) }
            ?: sessionStore.activeCompanyName().takeIf { it.isNotBlank() } ?: "",
        addressLine1 = s("addressLine1")?.takeIf { !it.equals("Address Line 1", ignoreCase = true) } ?: "",
        cityStatePincode = s("cityStatePincode")?.takeIf { !it.equals("City, State, Pincode", ignoreCase = true) } ?: "",
        salaryCalculationMethod = s("salaryCalculationMethod") ?: "Days in Month",
        officeLatitude = doubleAny("officeLatitude", "latitude", "OfficeLatitude", "Latitude") ?: 0.0,
        officeLongitude = doubleAny("officeLongitude", "longitude", "OfficeLongitude", "Longitude") ?: 0.0,
        geoRadiusMeters = intAny("geoRadiusMeters", "radius", "geo_radius_meters", "GeoRadiusMeters", "Radius") ?: 100,
        zktecoIP = s("zktecoIP"),
        zktecoPort = i("zktecoPort").takeIf { it > 0 } ?: 4370,
        zktecoMachineNumber = i("zktecoMachineNumber").takeIf { it > 0 } ?: 1,
        workDayCutoffHour = i("workDayCutoffHour"),
        endTimeGraceMinutes = i("endTimeGraceMinutes"),
        lateGraceMinutes = i("lateGraceMinutes"),
        enablePfEsiSystem = b("enablePfEsiSystem"),
        esiWageLimit = d("esiWageLimit"),
        basicSalaryPercentage = d("basicSalaryPercentage"),
        employeePfPercentage = d("employeePfPercentage"),
        employeeEsiPercentage = d("employeeEsiPercentage"),
        employerPfPercentage = d("employerPfPercentage"),
        employerEsiPercentage = d("employerEsiPercentage"),
        enableProfessionalTax = b("enableProfessionalTax"),
        enableEmailNotifications = b("enableEmailNotifications"),
        smtpHost = s("smtpHost"),
        smtpPort = i("smtpPort"),
        smtpUser = s("smtpUser"),
        smtpPass = s("smtpPass"),
        smtpFromEmail = s("smtpFromEmail"),
        enableSsl = b("enableSsl", true),
        enableShiftAllowance = b("enableShiftAllowance"),
        enableLeaveAccrual = b("enableLeaveAccrual"),
        leaveAccrualRate = d("leaveAccrualRate"),
        enableSandwichRule = b("enableSandwichRule"),
        enableLeaveManagement = b("enableLeaveManagement"),
        enableTdsDeduction = b("enableTdsDeduction"),
        autoBackupIntervalHours = i("autoBackupIntervalHours").takeIf { it >= 0 } ?: 24,
        stayDwellMinutes = i("stayDwellMinutes").takeIf { it > 0 } ?: 10,
        stayClusterRadiusMeters = i("stayClusterRadiusMeters").takeIf { it > 0 } ?: 50,
        useSpeedBasedMarkers = b("useSpeedBasedMarkers") || b("use_speed_based_markers"),
        syncState = 1
    )

    private fun DataSnapshot.toLocalFeatureSettings() = LocalFeatureSettings(
        id = 1,
        enableEmployeeManagement = b("enableEmployeeManagement", true),
        enablePayroll = b("enablePayroll", true),
        enableAttendance = b("enableAttendance", true),
        enableLeaveManagement = b("enableLeaveManagement", true),
        enableSalaryAdvance = b("enableSalaryAdvance", true),
        enableBonusManagement = b("enableBonusManagement", true),
        enableProfessionalTax = b("enableProfessionalTax", true),
        enableStatutoryCompliance = b("enableStatutoryCompliance", true),
        enableEmailNotifications = b("enableEmailNotifications", false),
        enableInAppNotifications = b("enableInAppNotifications", true),
        enableCustomReporting = b("enableCustomReporting", true),
        enableCompanyReports = b("enableCompanyReports", true),
        enableAuditLog = b("enableAuditLog", true),
        enableRecycleBin = b("enableRecycleBin", true),
        enableGeoFencing = b("enableGeoFencing"),
        enableAutomaticGeofencePunching = b("enableAutomaticGeofencePunching"),
        enableDualAttendance = b("enableDualAttendance"),
        enablePunchCorrection = b("enablePunchCorrection"),
        enableRegularizationReq = b("enableRegularizationReq") || b("enableRegularizationRequest"),
        enableResignationModule = b("enableResignationModule"),
        enableYearEndSummary = b("enableYearEndSummary"),
        enableTaxDeclarations = b("enableTaxDeclarations"),
        enableFlexibleBenefits = b("enableFlexibleBenefits") || b("enableSalaryStructuring"),
        enableTdsDeduction = b("enableTdsDeduction"),
        enableAutoShiftRotation = b("enableAutoShiftRotation"),
        enableShiftScheduling = b("enableShiftScheduling"),
        enableShiftAllowance = b("enableShiftAllowance"),
        enableSandwichRule = b("enableSandwichRule"),
        enableLeaveAccrual = b("enableLeaveAccrual"),
        showThemeToggle = b("showThemeToggle", true),
        employeeToolsVisible = b("employeeToolsVisible", true),
        employeeCanViewDashboard = b("employeeCanViewDashboard", true),
        employeeCanViewAttendance = b("employeeCanViewAttendance", true),
        employeeCanViewLeave = b("employeeCanViewLeave", true),
        employeeCanViewLeaveHistory = b("employeeCanViewLeaveHistory", true),
        employeeCanViewAdvance = b("employeeCanViewAdvance", true),
        employeeCanViewBonus = b("employeeCanViewBonus", true),
        employeeCanViewTax = b("employeeCanViewTax", true),
        employeeCanViewPayslip = b("employeeCanViewPayslip", true),
        employeeCanViewResignation = b("employeeCanViewResignation", true),
        employeeCanViewReports = b("employeeCanViewReports", true),
        employeeCanViewShifts = b("employeeCanViewShifts", true),
        adminCanViewDashboard = b("adminCanViewDashboard", true),
        adminCanViewAttendance = b("adminCanViewAttendance", true),
        adminCanManageShifts = b("adminCanManageShifts", true),
        adminCanRunPayroll = b("adminCanRunPayroll", true),
        adminCanViewReports = b("adminCanViewReports", true),
        adminCanManageEmployees = b("adminCanManageEmployees", true),
        adminCanEditSettings = b("adminCanEditSettings", true),
        adminCanManageEmployeePermissions = b("adminCanManageEmployeePermissions", true),
        adminCanManagePunchApprovals = b("adminCanManagePunchApprovals", true),
        adminCanManageFeatureToggles = b("adminCanManageFeatureToggles", false) || b("admin_can_manage_feature_toggles", false),
        firebasePlanMode = s("firebasePlanMode") ?: s("firebase_plan_mode") ?: "Spark",
        isOfflineMode = b("isOfflineMode") ?: b("is_offline_mode") ?: false,
        deploymentMode = s("deploymentMode") ?: s("deployment_mode") ?: "CloudOnly",
        syncState = 1
    )

    private fun observePayrollHistory() {
        val isEmployee = sessionStore.userRole().trim().uppercase() !in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
        val employeeId = sessionStore.employeeId()
        val query = if (isEmployee && employeeId > 0) {
            firebaseSync.getOwnerRef()?.child("payroll_history")
                ?.orderByChild("employeeId")
                ?.equalTo(employeeId.toDouble())
        } else {
            // SPARK PLAN OPTIMIZATION: Bound Admin payroll history listener to recent records
            firebaseSync.getOwnerRef()?.child("payroll_history")?.limitToLast(120)
        } ?: return

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!isEmployee || snapshot.intValue("employeeId") == employeeId) {
                        runCatching { payrollHistoryDao.upsert(snapshot.toLocalPayrollHistory()) }
                        triggerUiRefresh()
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!isEmployee || snapshot.intValue("employeeId") == employeeId) {
                        runCatching { payrollHistoryDao.upsert(snapshot.toLocalPayrollHistory()) }
                        triggerUiRefresh()
                    }
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.intValue("payrollId") ?: snapshot.key?.toIntOrNull() ?: return
                scope.launch {
                    runCatching { payrollHistoryDao.deleteById(id) }
                    triggerUiRefresh()
                }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Listen at payroll_history cancelled: Permission denied — scheduling recovery rebind")
                    scheduleRebind("payroll_history PERMISSION_DENIED — custom token recovery")
                    return
                }
                scheduleRebind("payroll_history cancelled: ${error.message}")
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
            // SPARK PLAN OPTIMIZATION: Bound Admin shift schedule listener to recent records
            firebaseSync.getOwnerRef()?.child("shift_schedules")?.limitToLast(300)
        } ?: return

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!employeeRole || snapshot.intValue("employeeId") == employeeId) {
                        runCatching { shiftScheduleDao.upsert(snapshot.toLocalShiftSchedule()) }
                        triggerUiRefresh()
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    if (!employeeRole || snapshot.intValue("employeeId") == employeeId) {
                        runCatching { shiftScheduleDao.upsert(snapshot.toLocalShiftSchedule()) }
                        triggerUiRefresh()
                    }
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.intValue("scheduleId") ?: snapshot.key?.toIntOrNull() ?: return
                scope.launch {
                    runCatching { shiftScheduleDao.deleteById(id) }
                    triggerUiRefresh()
                }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Listen at shift_schedules cancelled: Permission denied — scheduling recovery rebind")
                    scheduleRebind("shift_schedules PERMISSION_DENIED — custom token recovery")
                    return
                }
                scheduleRebind("shift_schedules cancelled: ${error.message}")
            }
        }
        query.addChildEventListener(listener)
        listeners += query to listener
    }

    private fun observe(
        table: String,
        query: Query? = null,
        onUpsert: suspend (DataSnapshot) -> Unit,
        onDelete: suspend (DataSnapshot) -> Unit
    ) {
        val targetQuery: Query = query ?: (firebaseSync.getOwnerRef()?.child(table) ?: return)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    runCatching { onUpsert(snapshot) }
                    triggerUiRefresh()
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    runCatching { onUpsert(snapshot) }
                    triggerUiRefresh()
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                scope.launch {
                    runCatching { onDelete(snapshot) }
                    triggerUiRefresh()
                }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseRoomHydrator", "Listen at $table cancelled: Permission denied — scheduling recovery rebind")
                    scheduleRebind("$table PERMISSION_DENIED — custom token recovery")
                    return
                }
                scheduleRebind("realtime table cancelled: ${error.message}")
            }
        }
        targetQuery.addChildEventListener(listener)
        listeners += targetQuery to listener
    }

    /** Rebind all Firebase -> Room realtime listeners without logout/login. */
    @Synchronized
    fun forceRebind(reason: String = "connection/auth recovery") {
        if (!sessionStore.isLoggedIn() || !firebaseSync.isAuthenticated()) return
        val ownerUid = firebaseSync.getOwnerUid()?.takeIf { it.isNotBlank() } ?: return
        Log.i("FirebaseRoomHydrator", "Rebinding realtime Room hydration: $reason")
        reconnectJob?.cancel()
        listeners.forEach { (query, listener) -> runCatching { query.removeEventListener(listener) } }
        listeners.clear()
        valueListeners.forEach { (query, listener) -> runCatching { query.removeEventListener(listener) } }
        valueListeners.clear()
        hydrationJob?.cancel()
        hydrationJob = null
        activeOwnerUid = null
        reconnectJob = scope.launch {
            runCatching {
                firebaseAuthTokenManager.getValidToken(forceRefresh = false)
            }
            delay(250L)
            if (sessionStore.isLoggedIn() && firebaseSync.isAuthenticated() && firebaseSync.getOwnerUid() == ownerUid) {
                start()
            }
        }
    }

    private fun scheduleRebind(reason: String) {
        if (!sessionStore.isLoggedIn() || !firebaseSync.isAuthenticated()) return

        synchronized(this) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                delay(1500L)
                forceRebind(reason)
            }
        }
    }

    @Synchronized
    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        hydrationJob?.cancel()
        hydrationJob = null

        listeners.forEach { (query, listener) ->
            runCatching { query.removeEventListener(listener) }
        }
        listeners.clear()

        valueListeners.forEach { (query, listener) ->
            runCatching { query.removeEventListener(listener) }
        }
        valueListeners.clear()

        wipeListeners.forEach { (ref, listener) ->
            runCatching { ref.removeEventListener(listener) }
        }
        wipeListeners.clear()

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
            is Number -> {
                val n = v.toLong()
                if (n in 1_000_000_000L..4_100_000_000L) n * 1000L else n
            }
            else -> {
                val s = v?.toString().orEmpty().trim()
                s.toLongOrNull()?.let { n ->
                    if (n in 1_000_000_000L..4_100_000_000L) n * 1000L else n
                } ?: runCatching {
                    java.time.Instant.parse(s).toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
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
    private fun Attendance.toLocal() = LocalAttendance(attendanceId, employeeId, checkInTime, checkOutTime, 1)
    private fun AdvancePayment.toLocal() = LocalAdvancePayment(advanceId, employeeId, shopId, amount, date, isRecovered, recoveryPaymentId, 1)
    private fun EmployeeHistory.toLocal() = LocalEmployeeHistory(
        historyId, employeeId, version, type, salaryType, oldValue, newValue, shiftStart, shiftEnd,
        shiftMode, breakHours, shift2Start, shift2End, weekendShiftStart, weekendShiftEnd, weekendBreakHours,
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

    private fun DataSnapshot.jsonOrStringValue(name: String): String? {
        val value = childValue(name) ?: return null
        return when (value) {
            is Map<*, *> -> runCatching { Gson().toJson(value) }.getOrNull()
            is String -> value
            else -> value.toString()
        }
    }

    private fun DataSnapshot.toLocalAuditLog(): LocalAuditLog {
        val rawTs = longValue("timestamp").let { if (it in 1..9999999999L) it * 1000L else it }
        return LocalAuditLog(
            logId = stringValue("logId") ?: key.orEmpty(),
            shopId = stringValue("shopId").orEmpty(),
            action = stringValue("action").orEmpty(),
            module = stringValue("module").orEmpty(),
            oldValue = jsonOrStringValue("oldValue"),
            newValue = jsonOrStringValue("newValue"),
            userDisplayName = stringValue("userDisplayName").orEmpty(),
            userId = stringValue("userId").orEmpty(),
            timestamp = if (rawTs > 0) rawTs else System.currentTimeMillis(),
            syncState = 1
        )
    }

    private fun DataSnapshot.toLocalDailySummary(): LocalDailySummary {
        val sDate = stringValue("shiftDate") ?: stringValue("date").orEmpty()
        val eId = intValue("employeeId") ?: intValue("staffId") ?: 0
        val rawId = intValue("summaryId")?.takeIf { it > 0 } ?: key.orEmpty().toIntOrNull()?.takeIf { it > 0 }
        val finalId = rawId ?: (eId.toString() + "_" + sDate).hashCode().let { if (it == 0) 1 else if (it < 0) Math.abs(it) else it }

        return LocalDailySummary(
            summaryId = finalId,
            employeeId = eId,
            shiftDate = sDate,
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
    }

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

    private fun DataSnapshot.toLocalBonusRecord(): LocalBonusRecord {
        val keyStr = key.orEmpty()
        val rawBonusId = intValue("bonusId")?.takeIf { it > 0 }
            ?: intValue("BonusID")?.takeIf { it > 0 }
            ?: keyStr.toIntOrNull()?.takeIf { it > 0 }
            ?: (if (keyStr.isNotBlank()) Math.abs(keyStr.hashCode()).let { if (it == 0) 1 else it } else 1)

        val empId = intValue("employeeId")
            ?: intValue("EmployeeID")
            ?: intValue("staffId")
            ?: 0

        val dateMs = longValue("bonusDate").takeIf { it > 0 }
            ?: longValue("BonusDate").takeIf { it > 0 }
            ?: System.currentTimeMillis()

        return LocalBonusRecord(
            bonusId = rawBonusId,
            employeeId = empId,
            bonusDate = dateMs,
            amount = doubleValue("amount").takeIf { it != 0.0 } ?: doubleValue("Amount"),
            description = stringValue("description") ?: stringValue("Description"),
            payrollIdPaid = intValue("payrollIdPaid") ?: intValue("PayrollID_Paid"),
            syncState = 1,
            firebaseKey = keyStr
        )
    }

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
