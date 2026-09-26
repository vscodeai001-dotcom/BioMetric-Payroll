package com.biometric.app.sync

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.EmployeeHistory
import com.biometric.app.data.entity.RecycleBinItem
import com.biometric.app.data.entity.Reminder
import com.biometric.app.data.entity.SalaryPayment
import com.biometric.app.data.entity.SalarySnapshot
import com.biometric.app.data.entity.Shop
import com.biometric.app.data.entity.ShopClosedDay
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.data.entity.OfflineTrackingEvent
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.sync.ssot.FirebaseSsotSchema
import com.google.gson.Gson
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val sessionStore: MobileSessionStore
) {

    private val gson = Gson()
    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    fun getGlobalRef() = database

    /** Firebase Auth persists its session across app/process/device restarts.
     * The tracking service can therefore continue using Firebase even when
     * Payroll.Web/Render is temporarily unavailable. */
    fun isAuthenticated(): Boolean = auth.currentUser != null

    val lastSyncTime = MutableStateFlow(System.currentTimeMillis())

    val syncStatus: Flow<Boolean> = callbackFlow {
        val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                trySend(connected)
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(element = false)
            }
        }
        connectedRef.addValueEventListener(listener)
        awaitClose { connectedRef.removeEventListener(listener) }
    }

    fun getOwnerUid(): String? {
        sessionStore.firebaseOwnerUid()?.takeIf { it.isNotBlank() }?.let { return it }
        return if (isAuthenticated() || sessionStore.isLoggedIn()) FirebaseSsotSchema.DEFAULT_OWNER_UID else null
    }

    fun getOwnerRef(): DatabaseReference? {
        val uid = getOwnerUid() ?: return null
        return database.child("owners").child(uid)
    }

    @Volatile
    private var initializedOwnerUid: String? = null

    private fun syncedOwnerTables(ref: DatabaseReference): List<DatabaseReference> = listOf(
        ref.child("employees"),
        ref.child("shops"),
        ref.child("attendance"),
        ref.child("attendance_punches"),
        ref.child("advance_payments"),
        ref.child("employee_history"),
        ref.child("shop_closed_days"),
        ref.child("regularizations"),
        ref.child("leave_requests"),
        ref.child("resignation_requests"),
        ref.child("salary_snapshots"),
        ref.child("audit_logs"),
        ref.child("daily_summaries"),
        ref.child("shift_schedules"),
        ref.child("payroll_history"),
        ref.child("payroll_previews"),
        ref.child("payroll_finalization"),
        ref.child("year_end_summaries"),
        ref.child("bonus_records"),
        ref.child("tax_declarations"),
        ref.child("fbp_components"),
        ref.child("fbp_declarations"),
        ref.child("feature_settings"),
        ref.child("company_settings")
    )

    @Synchronized
    fun startSync() {
        if (!isAuthenticated()) return
        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return
        if (initializedOwnerUid == ownerUid) return

        initializedOwnerUid?.let { previousOwner ->
            val oldRef = database.child("owners").child(previousOwner)
            syncedOwnerTables(oldRef).forEach { it.keepSynced(false) }
        }

        val ref = database.child("owners").child(ownerUid)
        ref.child("shops").keepSynced(true)
        ref.child("feature_settings").keepSynced(true)
        ref.child("company_settings").keepSynced(true)
        
        // Transactional and large tables (Attendance, Punches, Audit Logs, etc.) 
        // are NO LONGER kept synced in the background. This prevents the "data storm" 
        // during Admin login that triggers system restrictions. These tables 
        // will now only sync when an active listener is attached.
        
        initializedOwnerUid = ownerUid
    }

    inline fun <reified T : Any> getDataFlow(table: String): Flow<List<T>> = callbackFlow {
        val ref = getOwnerRef()?.child(table) ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSyncTime.value = System.currentTimeMillis()

                syncScope.launch(Dispatchers.Default) {
                    val list = mutableListOf<T>()
                    for (childSnapshot in snapshot.children) {
                        try {
                            if (childSnapshot.hasChildren()) {
                                tolerantFirebaseValue<T>(childSnapshot)?.let { list.add(it) }
                            }
                        } catch (e: Exception) {
                            // One legacy/malformed row must never flood logcat or
                            // abort the whole collection. Continue with valid rows.
                            Log.w(
                                "FirebaseSyncManager",
                                "Skipping malformed row in '$table' key=${childSnapshot.key}: ${e.message}"
                            )
                        }
                    }
                    trySend(list)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(emptyList())
                this@callbackFlow.close()
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun decodeAuditLog(snapshot: DataSnapshot): com.biometric.app.data.entity.AuditLog? {
        fun stringify(name: String): String? {
            val v = snapshot.child(name).value ?: return null
            return when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> try { gson.toJson(v) } catch (e: Exception) { null }
            }
        }
        fun long(name: String): Long {
            val v = snapshot.child(name).value
            return when (v) {
                is Number -> v.toLong()
                else -> v?.toString()?.toLongOrNull() ?: 0L
            }
        }
        return com.biometric.app.data.entity.AuditLog(
            logId = stringify("logId") ?: stringify("LogID") ?: snapshot.key.orEmpty(),
            shopId = stringify("shopId") ?: stringify("ShopId").orEmpty(),
            action = stringify("action") ?: stringify("Action").orEmpty(),
            module = stringify("module") ?: stringify("Module").orEmpty(),
            oldValue = stringify("oldValue") ?: stringify("OldValue"),
            newValue = stringify("newValue") ?: stringify("NewValue"),
            userDisplayName = stringify("userDisplayName") ?: stringify("UserDisplayName").orEmpty(),
            userId = stringify("userId") ?: stringify("UserId").orEmpty(),
            actorRole = stringify("actorRole") ?: stringify("ActorRole").orEmpty(),
            ownerUid = stringify("ownerUid") ?: stringify("OwnerUid").orEmpty(),
            targetId = stringify("targetId") ?: stringify("TargetId"),
            timestamp = long("timestamp").let { if (it != 0L) it else long("Timestamp") }
        )
    }

    inline fun <reified T : Any> getQueryFlow(query: Query): Flow<List<T>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSyncTime.value = System.currentTimeMillis()

                syncScope.launch(Dispatchers.Default) {
                    val list = mutableListOf<T>()
                    for (childSnapshot in snapshot.children) {
                        try {
                            childSnapshot.getValue(T::class.java)?.let { list.add(it) }
                        } catch (e: Exception) {
                            Log.e("FirebaseSyncManager", "Query conversion failed", e)
                        }
                    }
                    trySend(list)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(emptyList())
                this@callbackFlow.close()
            }
        }
        query.addValueEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    @PublishedApi
    internal inline fun <reified T : Any> tolerantFirebaseValue(snapshot: DataSnapshot): T? {
        return try {
            if (T::class.java == com.biometric.app.data.entity.Employee::class.java) {
                val e = com.biometric.app.data.entity.Employee(
                    employeeId = snapshot.child("employeeId").value?.toString()
                        ?.takeIf { it.isNotBlank() } ?: snapshot.key.orEmpty(),
                    shopId = snapshot.child("shopId").value?.toString().orEmpty(),
                    name = snapshot.child("name").value?.toString().orEmpty(),
                    phone = snapshot.child("phone").value?.toString().orEmpty(),
                    email = snapshot.child("email").value?.toString(),
                    biometricId = snapshot.child("biometricId").value?.toString().orEmpty(),
                    role = snapshot.child("role").value?.toString() ?: "Staff",
                    salaryType = snapshot.child("salaryType").value?.toString() ?: "MONTHLY_FIXED",
                    salaryRate = snapshot.child("salaryRate").value.numberOrDouble(),
                    paidLeaveBalance = snapshot.child("paidLeaveBalance").value.numberOrDouble(),
                    sickLeaveBalance = snapshot.child("sickLeaveBalance").value.numberOrDouble(),
                    salaryCalculationMethod = snapshot.child("salaryCalculationMethod").value?.toString()
                        ?: "Pro-Rata Hourly",
                    shiftStart = snapshot.child("shiftStart").value?.toString() ?: "10:00",
                    shiftEnd = snapshot.child("shiftEnd").value?.toString() ?: "22:00",
                    shiftMode = snapshot.child("shiftMode").value?.toString() ?: "SINGLE_DAY",
                    breakHours = snapshot.child("breakHours").value.numberOrDouble(),
                    compOffDayOfWeek = snapshot.child("compOffDayOfWeek").value.intOrNull(),
                    otRule = snapshot.child("otRule").value?.toString() ?: "No Overtime",
                    otFlatRate = snapshot.child("otFlatRate").value.numberOrDouble(),
                    otRateMultiplier = snapshot.child("otRateMultiplier").value.numberOrDouble(default = 1.0),
                    dailyAllowance = snapshot.child("dailyAllowance").value.numberOrDouble(),
                    nightShiftAllowance = snapshot.child("nightShiftAllowance").value.numberOrDouble(),
                    allowanceEffectiveDate = snapshot.child("allowanceEffectiveDate").value.longOrLong(System.currentTimeMillis()),
                    isActive = snapshot.child("isActive").value.boolOrNull(true),
                    hireDate = snapshot.child("hireDate").value.longOrLong(System.currentTimeMillis()),
                    dob = snapshot.child("dob").value.longOrNull(),
                    terminateDate = snapshot.child("terminateDate").value.longOrNull(),
                    enableShiftRotation = snapshot.child("enableShiftRotation").value.boolOrNull(false),
                    rotationGroup = snapshot.child("rotationGroup").value?.toString(),
                    shiftRotationPattern = snapshot.child("shiftRotationPattern").value?.toString(),
                    createdAt = snapshot.child("createdAt").value.longOrLong(System.currentTimeMillis()),
                    isBonusEligibleRule = snapshot.child("isBonusEligibleRule").value.boolOrNull(true),
                    isPaidLeaveEligibleRule = snapshot.child("isPaidLeaveEligibleRule").value.boolOrNull(true),
                    paidLeaveOnWeekdays = snapshot.child("paidLeaveOnWeekdays").value.boolOrNull(true),
                    paidLeaveOnWeekends = snapshot.child("paidLeaveOnWeekends").value.boolOrNull(false),
                    bankAccountNumber = snapshot.child("bankAccountNumber").value?.toString(),
                    bankIfscCode = snapshot.child("bankIfscCode").value?.toString(),
                    bankName = snapshot.child("bankName").value?.toString(),
                    uanNumber = snapshot.child("uanNumber").value?.toString(),
                    esiNumber = snapshot.child("esiNumber").value?.toString(),
                    enablePf = snapshot.child("enablePf").value.boolOrNull(false),
                    enableEsi = snapshot.child("enableEsi").value.boolOrNull(false),
                    tdsRatePercent = snapshot.child("tdsRatePercent").value.numberOrDouble(),
                    lastActive = snapshot.child("lastActive").value.longOrNull(),
                    syncState = 1,
                    lastModified = snapshot.child("lastModified").value.longOrLong(System.currentTimeMillis())
                )
                @Suppress("UNCHECKED_CAST")
                e as T
            } else if (T::class.java == com.biometric.app.data.entity.Attendance::class.java) {
                val a = com.biometric.app.data.entity.Attendance(
                    attendanceId = snapshot.child("attendanceId").value?.toString() ?: snapshot.key.orEmpty(),
                    employeeId = snapshot.child("employeeId").value?.toString().orEmpty(),
                    shopId = snapshot.child("shopId").value?.toString().orEmpty(),
                    checkInTime = snapshot.child("checkInTime").value.longOrLong(0L),
                    checkOutTime = snapshot.child("checkOutTime").value.longOrNull(),
                    type = snapshot.child("type").value?.toString() ?: "WORK",
                    hoursWorked = snapshot.child("hoursWorked").value.numberOrDouble(),
                    shiftStart = snapshot.child("shiftStart").value?.toString() ?: "10:00",
                    shiftEnd = snapshot.child("shiftEnd").value?.toString() ?: "22:00",
                    shift2Start = snapshot.child("shift2Start").value?.toString(),
                    shift2End = snapshot.child("shift2End").value?.toString(),
                    breakHours = snapshot.child("breakHours").value.numberOrDouble(),
                    salaryType = snapshot.child("salaryType").value?.toString() ?: "MONTHLY_FIXED",
                    salaryRate = snapshot.child("salaryRate").value.numberOrDouble(),
                    note = snapshot.child("note").value?.toString(),
                    synced = snapshot.child("synced").value.boolOrNull(false),
                    lateDeduction = snapshot.child("lateDeduction").value.numberOrDouble(),
                    otHours = snapshot.child("otHours").value.numberOrDouble(),
                    createdAt = snapshot.child("createdAt").value.longOrLong(System.currentTimeMillis()),
                    syncState = 1,
                    lastModified = snapshot.child("lastModified").value.longOrLong(System.currentTimeMillis())
                )
                @Suppress("UNCHECKED_CAST")
                a as T
            } else if (T::class.java == com.biometric.app.data.entity.AdvancePayment::class.java) {
                val a = com.biometric.app.data.entity.AdvancePayment(
                    advanceId = snapshot.child("advanceId").value?.toString() ?: snapshot.key.orEmpty(),
                    employeeId = snapshot.child("employeeId").value?.toString().orEmpty(),
                    shopId = snapshot.child("shopId").value?.toString().orEmpty(),
                    amount = snapshot.child("amount").value.numberOrDouble(),
                    date = snapshot.child("date").value.longOrLong(System.currentTimeMillis()),
                    isRecovered = snapshot.child("isRecovered").value.boolOrNull(false),
                    recoveryPaymentId = snapshot.child("recoveryPaymentId").value?.toString()
                )
                @Suppress("UNCHECKED_CAST")
                a as T
            } else if (T::class.java == com.biometric.app.data.entity.LocalBonusRecord::class.java) {
                val key = snapshot.key.orEmpty()
                val rawBonusId = snapshot.child("bonusId").value?.toString()?.toIntOrNull()
                    ?: snapshot.child("BonusID").value?.toString()?.toIntOrNull()
                    ?: key.toIntOrNull()
                    ?: (if (key.isNotBlank()) Math.abs(key.hashCode()).let { if (it == 0) 1 else it } else 1)

                val empId = snapshot.child("employeeId").value?.toString()?.toIntOrNull()
                    ?: snapshot.child("EmployeeID").value?.toString()?.toIntOrNull()
                    ?: snapshot.child("staffId").value?.toString()?.toIntOrNull()
                    ?: 0

                val rawDate = snapshot.child("bonusDate").value ?: snapshot.child("BonusDate").value
                val dateMs = rawDate.parseTimestampOrLong(System.currentTimeMillis())

                val amt = (snapshot.child("amount").value ?: snapshot.child("Amount").value).numberOrDouble()

                val desc = snapshot.child("description").value?.toString()
                    ?: snapshot.child("Description").value?.toString()

                val payrollId = (snapshot.child("payrollIdPaid").value ?: snapshot.child("PayrollID_Paid").value)?.toString()?.toIntOrNull()

                val b = com.biometric.app.data.entity.LocalBonusRecord(
                    bonusId = rawBonusId,
                    employeeId = empId,
                    bonusDate = dateMs,
                    amount = amt,
                    description = desc,
                    payrollIdPaid = payrollId,
                    syncState = 1,
                    firebaseKey = key
                )
                @Suppress("UNCHECKED_CAST")
                b as T
            } else if (T::class.java == AuditLog::class.java) {
                @Suppress("UNCHECKED_CAST")
                decodeAuditLog(snapshot) as? T
            } else {
                snapshot.getValue(T::class.java)
            }
        } catch (e: Exception) {
            Log.w(
                "FirebaseSyncManager",
                "Skipping malformed Firebase row key=${snapshot.key}",
                e
            )
            null
        }
    }

    @PublishedApi
    internal fun Any?.numberOrDouble(default: Double = 0.0): Double =
        when (this) {
            is Number -> toDouble()
            else -> this?.toString()?.trim()?.toDoubleOrNull() ?: default
        }

    @PublishedApi
    internal fun Any?.intOrNull(): Int? =
        when (this) {
            is Number -> toInt()
            else -> this?.toString()?.trim()?.toIntOrNull()
        }

    @PublishedApi
    internal fun Any?.longOrLong(default: Long): Long =
        when (this) {
            is Number -> toLong()
            else -> this?.toString()?.trim()?.toLongOrNull() ?: default
        }

    @PublishedApi
    internal fun Any?.longOrNull(): Long? =
        when (this) {
            is Number -> toLong()
            else -> this?.toString()?.trim()?.toLongOrNull()
        }

    @PublishedApi
    internal fun Any?.parseTimestampOrLong(default: Long = System.currentTimeMillis()): Long =
        when (this) {
            is Number -> {
                val n = toLong()
                if (n in 1..9999999999L) n * 1000L else n
            }
            null -> default
            else -> {
                val s = toString().trim()
                s.toLongOrNull()?.let { n ->
                    if (n in 1..9999999999L) n * 1000L else n
                } ?: runCatching {
                    java.time.Instant.parse(s).toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: runCatching {
                    java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrDefault(default)
            }
        }

    @PublishedApi
    internal fun Any?.boolOrNull(default: Boolean): Boolean =
        when (this) {
            is Boolean -> this
            else -> this?.toString()?.trim()?.toBooleanStrictOrNull() ?: default
        }

    data class RealtimeChangedItem(
        val entity: String = "",
        val action: String = "MODIFIED"
    )

    data class ApplicationRealtimeEvent(
        val eventId: String = "",
        val source: String = "",
        val timestamp: String = "",
        val changes: List<RealtimeChangedItem> = emptyList()
    )

    fun applicationEventsFlow(): Flow<ApplicationRealtimeEvent> = callbackFlow {
        val ownerUid = getOwnerUid() ?: run {
            close()
            return@callbackFlow
        }
        // ChildEventListener replays existing children. Keep a bounded tail so
        // reconnects can recover changes that happened while the device was
        // offline, instead of using a five-second window that could silently
        // miss events during a longer network outage. Firebase remains the
        // shared realtime synchronization source; SQL is the Web compatibility
        // store during the phased migration.
        val ref = database.child("owner_events").child(ownerUid)
            .orderByChild("timestamp")
            .limitToLast(25)
        val deliveredEventIds = Collections.synchronizedSet(mutableSetOf<String>())
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val eventId = snapshot.child("eventId").getValue(String::class.java) ?: snapshot.key.orEmpty()
                if (eventId.isBlank() || !deliveredEventIds.add(eventId)) return
                val source = snapshot.child("source").getValue(String::class.java).orEmpty()
                val timestamp = snapshot.child("timestamp").getValue(String::class.java).orEmpty()
                val changes = snapshot.child("changes").children.mapNotNull { child ->
                    val entity = child.child("Entity").getValue(String::class.java) ?: child.child("entity").getValue(String::class.java)
                    if (entity.isNullOrBlank()) null else RealtimeChangedItem(
                        entity = entity,
                        action = child.child("Action").getValue(String::class.java)
                            ?: child.child("action").getValue(String::class.java)
                            ?: "MODIFIED"
                    )
                }
                trySend(ApplicationRealtimeEvent(eventId, source, timestamp, changes))
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                if (error.code != DatabaseError.PERMISSION_DENIED) {
                    Log.w("FirebaseSyncManager", "Owner realtime event listener cancelled: ${error.message}")
                }
            }
        }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    inline fun <reified T : Any> getGlobalDataFlow(path: String): Flow<List<T>> = callbackFlow {
        val ref = getGlobalRef().child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSyncTime.value = System.currentTimeMillis()
                syncScope.launch(Dispatchers.Default) {
                    val list = mutableListOf<T>()
                    for (childSnapshot in snapshot.children) {
                        try {
                            childSnapshot.getValue(T::class.java)?.let { list.add(it) }
                        } catch (e: Exception) {
                            Log.w("FirebaseSyncManager", "Global data conversion failed at $path", e)
                        }
                    }
                    trySend(list)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(emptyList())
                this@callbackFlow.close()
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    inline fun <reified T : Any> getGlobalItemFlow(path: String): Flow<T?> = callbackFlow {
        val ref = getGlobalRef().child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSyncTime.value = System.currentTimeMillis()
                trySend(snapshot.getValue(T::class.java))
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(null)
                this@callbackFlow.close()
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun notifyRealtimeChanged(entity: String, action: String = "MODIFIED", recordId: String? = null) {
        val ownerUid = getOwnerUid()
        syncScope.launch {
            // Firebase owner event is the Render-independent invalidation path.
            if (!ownerUid.isNullOrBlank()) {
                runCatching {
                    publishClientApplicationChange(entity, action, recordId)
                }.onFailure {
                    Log.w("FirebaseSyncManager", "Firebase realtime event failed for $entity", it)
                }
            }
        }
    }

    private suspend fun publishClientApplicationChange(
        entity: String,
        action: String,
        recordId: String?
    ) {
        if (!isAuthenticated()) return
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return
        val ownerUid = sessionStore.firebaseOwnerUid().orEmpty()
        if (ownerUid.isBlank()) return

        val eventId = UUID.randomUUID().toString().replace("-", "")
        val change = mutableMapOf<String, Any?>(
            "Entity" to entity,
            "Action" to action
        )
        if (!recordId.isNullOrBlank()) change["RecordId"] = recordId
        val payload = mapOf(
            "eventId" to eventId,
            "source" to "FIREBASE_CLIENT",
            "employeeId" to employeeId,
            "ownerUid" to ownerUid,
            "timestamp" to Date().toInstant().toString(),
            "changes" to listOf(change)
        )
        database.child("client_events").child(employeeId.toString()).child(eventId).setValue(payload).await()
    }

    suspend fun pushShop(shop: Shop) { getOwnerRef()?.child("shops")?.child(shop.shopId)?.setValue(shop)?.await(); notifyRealtimeChanged("Shop", "MODIFIED") }
    suspend fun pushEmployee(emp: Employee) { getOwnerRef()?.child("employees")?.child(emp.employeeId)?.setValue(emp)?.await(); notifyRealtimeChanged("Employee", "MODIFIED") }
    suspend fun pushAttendance(att: Attendance) {
        val id = att.attendanceId.ifBlank { return }
        getOwnerRef()?.child("attendance")?.child(id)?.setValue(att)?.await()
        notifyRealtimeChanged("Attendance", "MODIFIED")
    }

    /**
     * Render-independent GPS transport. The Android tracking service writes
     * the current live position and an immutable history event in one Firebase
     * multi-location update. Firebase is the independent realtime/tracking source; this path
     * exists so a Render outage cannot stop GPS capture or realtime map updates.
     */
    /**
     * Mirrors the Web GeoLocationService GPS-session contract. The Web
     * compatibility worker consumes tracking/sessions/{employeeId}/{sessionId}
     * before it accepts tracking history, so Android must publish the session
     * lifecycle as well as the individual GPS points.
     */
    /**
     * Creates a GPS session once. A late retry after the session has ended must
     * never resurrect that session. This makes session start idempotent.
     */
    /**
     * Reads the durable Firebase session state without mutating it.
     * null means Firebase/auth/network could not be read.
     */
    suspend fun getTrackingSessionState(
        employeeId: Int,
        sessionId: String
    ): String? {
        if (employeeId <= 0 || sessionId.isBlank() || !isAuthenticated()) return null

        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return null

        return try {
            val snapshot = getGlobalRef()
                .child("owners/$ownerUid/tracking/sessions/$employeeId/$sessionId")
                .get()
                .await()

            if (!snapshot.exists()) {
                "MISSING"
            } else {
                snapshot.child("State")
                    .getValue(String::class.java)
                    ?.trim()
                    ?.uppercase()
                    ?: "UNKNOWN"
            }
        } catch (e: Exception) {
            Log.w(
                "FirebaseSyncManager",
                "Unable to read tracking session state. employee=$employeeId session=$sessionId",
                e
            )
            null
        }
    }

    suspend fun getTrackingLiveSessionId(employeeId: Int): String? {
        if (employeeId <= 0 || !isAuthenticated()) return null
        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val snapshot = getGlobalRef()
                .child("owners/$ownerUid/tracking/live/$employeeId")
                .get()
                .await()
            snapshot.child("SessionId").getValue(String::class.java)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Unable to read current live session. employee=$employeeId", e)
            null
        }
    }

    suspend fun pushTrackingSessionStarted(employeeId: Int, sessionId: String): Boolean {
        if (employeeId <= 0 || sessionId.isBlank() || !isAuthenticated()) return false
        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return false
        val ref = getGlobalRef().child("owners/$ownerUid/tracking/sessions/$employeeId/$sessionId")
        val payload = mapOf(
            "EmployeeId" to employeeId,
            "SessionId" to sessionId,
            "OwnerUid" to ownerUid,
            "StartedAtUtc" to Date().toInstant().toString(),
            "State" to "ACTIVE",
            "Source" to "ANDROID_FIREBASE"
        )
        return try {
            val committed = ref.runTransactionAwait { current ->
                if (current.value == null) {
                    current.value = payload
                    true
                } else {
                    val state = current.child("State").getValue(String::class.java).orEmpty()
                    if (state.equals("ENDED", ignoreCase = true)) false
                    else {
                        current.child("State").value = "ACTIVE"
                        true
                    }
                }
            }
            // Keep the legacy compatibility path, but only after the owner-scoped
            // session has accepted the start.
            if (committed) {
                try {
                    getGlobalRef().child("tracking/sessions/$employeeId/$sessionId").updateChildren(payload).await()
                } catch (legacyEx: Exception) {
                    Log.d("FirebaseSyncManager", "Legacy tracking/sessions write skipped: ${legacyEx.message}")
                }

                // Live marker is established exclusively by pushLiveLocation when genuine
                // GPS coordinates are available, preventing Null Island (0,0) phantom triggers.
            }
            committed
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "GPS session start write deferred", e)
            false
        }
    }

    suspend fun pushTrackingSessionEnded(
        employeeId: Int,
        sessionId: String,
        endReason: String = "LOGGED_OUT"
    ): Boolean {
        if (employeeId <= 0 || sessionId.isBlank() || !isAuthenticated()) return false
        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return false
        val ref = getGlobalRef().child("owners/$ownerUid/tracking/sessions/$employeeId/$sessionId")
        return try {
            val committed = ref.runTransactionAwait { current ->
                val existingEmployee = current.child("EmployeeId").getValue(Int::class.java)
                val existingSession = current.child("SessionId").getValue(String::class.java)
                if (existingEmployee != null && existingEmployee != employeeId) return@runTransactionAwait false
                if (!existingSession.isNullOrBlank() && existingSession != sessionId) return@runTransactionAwait false
                current.child("EmployeeId").value = employeeId
                current.child("SessionId").value = sessionId
                current.child("OwnerUid").value = ownerUid
                current.child("EndedAtUtc").value = Date().toInstant().toString()
                current.child("EndReason").value = endReason.take(40)
                current.child("State").value = "ENDED"
                current.child("Source").value = "ANDROID_FIREBASE"
                true
            }
            if (committed) {
                val endAt = Date().toInstant().toString()
                val endPayload = mapOf(
                    "EmployeeId" to employeeId,
                    "SessionId" to sessionId,
                    "OwnerUid" to ownerUid,
                    "EndReason" to endReason.take(40),
                    "State" to "ENDED",
                    "EndedAtUtc" to endAt,
                    "Source" to "ANDROID_FIREBASE"
                )

                getGlobalRef().child("tracking/sessions/$employeeId/$sessionId").updateChildren(endPayload).await()

                // REQUIREMENT: Prevent late GPS points from resurrecting a ghost marker.
                // Only mark the live node as ENDED if it is currently bound to 
                // the session that is being closed. This prevents a logout on 
                // one device from terminating a valid active session on another.
                val liveRef = getGlobalRef().child("owners/$ownerUid/tracking/live/$employeeId")
                liveRef.runTransactionAwait { current ->
                    val currentSession = current.child("SessionId").getValue(String::class.java).orEmpty()
                    if (currentSession == sessionId || currentSession.isBlank()) {
                        current.child("State").value = "ENDED"
                        current.child("LastUpdatedUtc").value = endAt
                        true
                    } else {
                        false
                    }
                }
                
                // Sync the legacy compatibility live node only when the
                // owner-scoped transaction actually closed this same session.
                // A rejected transaction means a newer session owns the marker;
                // never mark that newer session as ENDED.
                val ownerLiveClosed = liveRef.get().await().let { snapshot ->
                    snapshot.child("State").getValue(String::class.java)
                        .equals("ENDED", ignoreCase = true) &&
                    snapshot.child("SessionId").getValue(String::class.java).orEmpty() == sessionId
                }
                if (ownerLiveClosed) {
                    getGlobalRef().child("tracking/live/$employeeId").updateChildren(endPayload).await()
                }
            }
            committed
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "GPS session end write deferred", e)
            false
        }
    }

    private suspend fun DatabaseReference.runTransactionAwait(
        block: (MutableData) -> Boolean
    ): Boolean = suspendCoroutine { continuation ->
        runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                return if (block(currentData)) Transaction.success(currentData)
                else Transaction.abort()
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) {
                if (error != null) continuation.resumeWithException(error.toException())
                else continuation.resume(committed)
            }
        })
    }

    suspend fun pushLiveLocation(
        employeeId: Int,
        sessionId: String,
        clientEventId: String,
        sequence: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        speed: Double,
        bearing: Double = 0.0,
        batteryLevel: Int,
        timestamp: Long,
        isOffline: Boolean = false,
        recordHistory: Boolean = true
    ): Boolean {
        if (employeeId <= 0 || sessionId.isBlank() || clientEventId.isBlank() || sequence <= 0L) return false

        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return false
        if (!isAuthenticated()) return false

        val payload = mapOf(
            "EmployeeId" to employeeId,
            "SessionId" to sessionId,
            "OwnerUid" to ownerUid,
            "Latitude" to latitude,
            "Longitude" to longitude,
            "AccuracyMeters" to accuracy.coerceAtLeast(0.0),
            "SpeedMps" to speed.coerceAtLeast(0.0),
            "Bearing" to bearing,
            "BatteryLevel" to batteryLevel,
            "Sequence" to sequence,
            "Timestamp" to Date(timestamp).toInstant().toString(),
            "LastUpdatedUtc" to Date().toInstant().toString(),
            "ClientEventId" to clientEventId,
            "State" to "ACTIVE",
            "Source" to if (isOffline) "OfflineSync" else "Online",
            "CaptureSource" to if (isOffline) "OfflineSync" else "Online"
        )

        return try {
            // Offline/replayed GPS is immutable historical evidence. It must
            // never advance the live marker, even when the previous session is
            // still ACTIVE, because its capture time is in the past.
            if (isOffline) {
                val sessionState = getGlobalRef()
                    .child("owners/$ownerUid/tracking/sessions/$employeeId/$sessionId")
                    .get()
                    .await()
                    .child("State")
                    .getValue(String::class.java)
                    .orEmpty()
                getGlobalRef()
                    .child("owners/$ownerUid/tracking/history/$employeeId/$clientEventId")
                    .setValue(payload + ("SessionState" to if (sessionState.isBlank()) "OFFLINE_UNBOUND" else sessionState))
                    .await()
                return true
            }

            val sessionRef = getGlobalRef()
                .child("owners/$ownerUid/tracking/sessions/$employeeId/$sessionId")
            val sessionSnapshot = sessionRef.get().await()

            if (!sessionSnapshot.exists()) {
                // Offline/historical evidence must never create an ACTIVE session.
                // Keep it as immutable history and wait for a fresh current GPS fix.
                Log.w(
                    "FirebaseSyncManager",
                    "GPS point rejected because Firebase session does not exist. employee=$employeeId session=$sessionId"
                )
                return false
            }

            val sessionState = sessionSnapshot.child("State")
                .getValue(String::class.java)
                .orEmpty()

            if (sessionState.equals("ENDED", ignoreCase = true)) {
                // Offline replay remains historical evidence. A current online
                // GPS fix must instead force the caller to create a NEW session.
                Log.w(
                    "FirebaseSyncManager",
                    "Current GPS fix rejected because its session is ENDED; caller must rotate the session. employee=$employeeId session=$sessionId"
                )
                return false
            }

            if (!sessionState.equals("ACTIVE", ignoreCase = true)) {
                Log.w(
                    "FirebaseSyncManager",
                    "GPS point deferred because session state is '$sessionState'. employee=$employeeId session=$sessionId"
                )
                return false
            }

            val liveRef = getGlobalRef().child("owners/$ownerUid/tracking/live/$employeeId")

            // First claim the live sequence. The transaction is idempotent for
            // the same ClientEventId so a history write failure can safely retry
            // without being blocked by the already-accepted sequence.
            val accepted = liveRef.runTransactionAwait { current ->
                val currentSession = current.child("SessionId").getValue(String::class.java).orEmpty()
                val currentSequence = current.child("Sequence").getValue(Long::class.java) ?: 0L
                val currentState = current.child("State").getValue(String::class.java).orEmpty()
                val currentClientEventId = current.child("ClientEventId").getValue(String::class.java).orEmpty()

                // Only reject if THIS incoming session is the one that was ended.
                // When a new session begins (currentSession != sessionId), accept it and establish the new active live marker.
                if (currentSession.equals(sessionId, ignoreCase = true) && currentState.equals("ENDED", true)) {
                    return@runTransactionAwait false
                }
                // Sequence order check applies strictly within the same session.
                // When a new active session is started, sequence counter starts fresh.
                if (currentSession.equals(sessionId, ignoreCase = true) && currentSequence > sequence) {
                    return@runTransactionAwait false
                }

                // Same event was already accepted. Keep the transaction
                // committed so the immutable history write below is retried.
                if (currentSession.equals(sessionId, ignoreCase = true) &&
                    currentSequence == sequence &&
                    currentClientEventId.isNotBlank() &&
                    currentClientEventId != clientEventId) {
                    return@runTransactionAwait false
                }

                current.value = payload
                current.child("State").value = "ACTIVE"
                true
            }

            if (!accepted) return false

            // Immutable history is written only after the live/session checks
            // succeed and only when recordHistory is true. This throttles stationary/burst
            // points to preserve Firebase Spark plan bandwidth while keeping the live marker real-time.
            if (recordHistory) {
                getGlobalRef()
                    .child("owners/$ownerUid/tracking/history/$employeeId/$clientEventId")
                    .setValue(payload)
                    .await()
            }

            // Legacy compatibility stream receives only the newest accepted
            // point. It is never allowed to overwrite a newer owner-scoped
            // session.
            try {
                getGlobalRef()
                    .child("tracking/live/$employeeId")
                    .setValue(payload)
                    .await()
            } catch (legacyEx: Exception) {
                Log.d("FirebaseSyncManager", "Legacy tracking/live write skipped: ${legacyEx.message}")
            }

            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "GPS Firebase write deferred", e)
            false
        }
    }

    suspend fun pushAttendancePunch(punch: AttendancePunch) {
        val id = punch.punchId.ifBlank { return }
        getOwnerRef()?.child("attendance_punches")?.child(id)?.setValue(punch)?.await()
        notifyRealtimeChanged("AttendancePunch", "MODIFIED")
    }
    suspend fun pushAdvance(adv: AdvancePayment) { getOwnerRef()?.child("advance_payments")?.child(adv.advanceId)?.setValue(adv)?.await(); notifyRealtimeChanged("AdvancePayment", "MODIFIED") }
    /**
     * These three request types are intentionally accepted as Any.
     * Different project revisions keep these request models in different
     * packages, so FirebaseSyncManager must not create a compile-time
     * dependency on a particular model package.
     *
     * The existing callers can continue passing their request objects.
     * Firebase serializes the complete object exactly as before.
     */
    suspend fun pushRegularization(request: Any) {
        val id = extractStringId(request, "id") ?: return
        getOwnerRef()?.child("regularizations")?.child(id)?.setValue(request)?.await()
        notifyRealtimeChanged("AttendanceRegularization", "MODIFIED", id)
    }

    suspend fun pushLeaveRequest(request: Any) {
        val id = extractStringId(request, "id") ?: return
        getOwnerRef()?.child("leave_requests")?.child(id)?.setValue(request)?.await()
        notifyRealtimeChanged("LeaveRequest", "MODIFIED", id)
    }

    suspend fun pushResignationRequest(request: Any) {
        val id = extractStringId(request, "requestId")
            ?: extractStringId(request, "id")
            ?: return

        getOwnerRef()?.child("resignation_requests")?.child(id)?.setValue(request)?.await()
        notifyRealtimeChanged("ResignationRequest", "MODIFIED", id)
    }

    private fun extractStringId(value: Any, fieldName: String): String? {
        return runCatching {
            val field = value.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(value)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }


    fun pushSalaryPayment(p: SalaryPayment) {
        val ref = getOwnerRef() ?: return
        syncScope.launch {
            runCatching {
                ref.child("salary_payments").child(p.paymentId).setValue(p).await()
                notifyRealtimeChanged("SalaryPayment", "MODIFIED")
            }.onFailure {
                Log.w("FirebaseSyncManager", "Salary payment write failed", it)
            }
        }
    }

    suspend fun pushHistory(hist: EmployeeHistory) { getOwnerRef()?.child("employee_history")?.child(hist.historyId)?.setValue(hist)?.await(); notifyRealtimeChanged("EmployeeHistory", "MODIFIED") }

    fun pushHistoryAtomic(hist: EmployeeHistory) {
        val ref = getOwnerRef()?.child("employee_history") ?: return
        val countersRef = getOwnerRef()?.child("history_counters")?.child(hist.employeeId) ?: return

        countersRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val currentVersion = currentData.getValue(Int::class.java) ?: 0
                val nextVersion = currentVersion + 1
                currentData.value = nextVersion
                hist.version = nextVersion
                ref.child(hist.historyId).setValue(hist)
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null || !committed) {
                    Log.w("FirebaseSyncManager", "Atomic history write did not commit: ${error?.message ?: "not committed"}")
                    return
                }
                // The realtime invalidation is emitted only after Firebase
                // confirms the atomic transaction. This prevents another
                // platform from reloading before the history write exists.
                notifyRealtimeChanged("EmployeeHistory", "MODIFIED")
            }
        })
    }
    suspend fun pushClosedDay(day: ShopClosedDay) { getOwnerRef()?.child("shop_closed_days")?.child(day.id)?.setValue(day)?.await(); notifyRealtimeChanged("ShopClosedDay", "MODIFIED") }
    suspend fun pushReminder(reminder: Reminder) { getOwnerRef()?.child("reminders")?.child(reminder.reminderId)?.setValue(reminder)?.await(); notifyRealtimeChanged("Reminder", "MODIFIED") }
    suspend fun pushProfile(profile: UserProfile) {
        getOwnerRef()?.child("user_profiles")?.child(profile.uid)?.setValue(profile)?.await()
        notifyRealtimeChanged("UserProfile", "MODIFIED")
        try { FirebaseFirestore.getInstance().collection("userProfiles").document(profile.uid).set(profile).await() } catch (_: Exception) {}
    }

    suspend fun getUserTheme(uid: String): String? {
        if (uid.isBlank()) return null
        return try {
            getGlobalRef().child("user_profiles").child(uid).child("theme").get().await()
                .getValue(String::class.java)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it == "dark" || it == "light" }
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Unable to read Firebase user theme", e)
            null
        }
    }

    suspend fun setUserTheme(uid: String, theme: String): Boolean {
        if (uid.isBlank()) return false
        val normalized = if (theme.equals("dark", true)) "dark" else "light"
        return try {
            getGlobalRef().child("user_profiles").child(uid).child("theme")
                .setValue(normalized).await()
            notifyRealtimeChanged("UserProfile", "MODIFIED", uid)
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Unable to save Firebase user theme", e)
            false
        }
    }
    suspend fun pushRecycleBin(item: RecycleBinItem) { getOwnerRef()?.child("recycle_bin")?.child(item.id)?.setValue(item)?.await(); notifyRealtimeChanged("RecycleBinItem", "MODIFIED") }
    suspend fun pushAuditLog(log: AuditLog) { getOwnerRef()?.child("audit_logs")?.child(log.logId)?.setValue(log)?.await(); notifyRealtimeChanged("AuditLog", "ADDED") }

    /**
     * Publishes a mobile authentication event (login/logout/conflict) to the
     * global 'mobile_auth_events' node. The Web compatibility bridge projects
     * these into the server's AuditLogs table for Admin monitoring.
     */
    suspend fun pushMobileAuthEvent(eventType: String, email: String, deviceId: String): Boolean {
        if (!isAuthenticated()) return false
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return false
        
        val user = auth.currentUser ?: return false
        val eventId = UUID.randomUUID().toString().replace("-", "")
        
        val payload = mapOf(
            "employeeId" to employeeId,
            "eventType" to eventType,
            "firebaseUid" to user.uid,
            "email" to email,
            "deviceId" to deviceId,
            "platform" to "Android",
            "timestamp" to Date().toInstant().toString(),
            "eventId" to eventId
        )
        
        return try {
            database.child("mobile_auth_events").child(user.uid).child(eventId).setValue(payload).await()
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Mobile auth event write failed", e)
            false
        }
    }

    suspend fun pushTrackingEvent(event: OfflineTrackingEvent): Boolean {
        if (!isAuthenticated()) return false
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return false

        val ownerUid = getOwnerUid()?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val payload = mapOf(
                "event" to event,
                "ownerUid" to ownerUid,
                "employeeId" to employeeId
            )
            getGlobalRef().updateChildren(
                mapOf(
                    "tracking/events/$employeeId/${event.eventId}" to payload,
                    "owners/$ownerUid/tracking/events/$employeeId/${event.eventId}" to payload
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Tracking event write failed", e)
            false
        }
    }

    suspend fun notifyRealtimeAfterWrite(entity: String, action: String = "MODIFIED") {
        notifyRealtimeChanged(entity, action)
    }

    data class ShiftScheduleRecord(
        val scheduleId: Int = 0,
        val employeeId: Int = 0,
        val shiftDate: String = "",
        val startTime: String = "",
        val endTime: String = "",
        val isRecurringPattern: Boolean = false,
        val patternDurationDays: Int = 7,
        val appliesToDayOfWeek: Int = 0
    )

    fun observeShiftSchedules(): Flow<List<ShiftScheduleRecord>> = callbackFlow {
        val ref = getOwnerRef()?.child("shift_schedules") ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = snapshot.children.mapNotNull { child ->
                    val id = (child.child("scheduleId").value as? Number)?.toInt()
                        ?: child.child("scheduleId").value?.toString()?.toIntOrNull()
                        ?: child.key?.toIntOrNull()
                        ?: return@mapNotNull null
                    val employeeId = (child.child("employeeId").value as? Number)?.toInt()
                        ?: child.child("employeeId").value?.toString()?.toIntOrNull()
                        ?: 0
                    if (employeeId <= 0) return@mapNotNull null
                    ShiftScheduleRecord(
                        scheduleId = id,
                        employeeId = employeeId,
                        shiftDate = child.child("shiftDate").value?.toString()
                            ?: child.child("ShiftDate").value?.toString().orEmpty(),
                        startTime = child.child("startTime").value?.toString()
                            ?: child.child("StartTime").value?.toString().orEmpty(),
                        endTime = child.child("endTime").value?.toString()
                            ?: child.child("EndTime").value?.toString().orEmpty(),
                        isRecurringPattern = child.child("isRecurringPattern").value as? Boolean
                            ?: child.child("IsRecurringPattern").value?.toString()?.toBoolean() ?: false,
                        patternDurationDays = (child.child("patternDurationDays").value as? Number)?.toInt()
                            ?: child.child("patternDurationDays").value?.toString()?.toIntOrNull() ?: 7,
                        appliesToDayOfWeek = (child.child("appliesToDayOfWeek").value as? Number)?.toInt()
                            ?: child.child("appliesToDayOfWeek").value?.toString()?.toIntOrNull()
                            ?: 0
                    )
                }.sortedWith(compareBy<ShiftScheduleRecord> { it.shiftDate }.thenBy { it.employeeId }.thenBy { it.startTime })
                trySend(rows)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseSyncManager", "Shift schedule listener cancelled", error.toException())
                trySend(emptyList())
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Employee-safe shift listener. Firebase rules require employee clients to
     * constrain the collection by employeeId, so they must never attach a
     * listener to the unfiltered shift_schedules collection.
     */
    fun observeEmployeeShiftSchedules(employeeId: Int = sessionStore.employeeId()): Flow<List<ShiftScheduleRecord>> = callbackFlow {
        if (employeeId <= 0) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = getOwnerRef()?.child("shift_schedules")
            ?.orderByChild("employeeId")
            ?.equalTo(employeeId.toDouble()) ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = snapshot.children.mapNotNull { child ->
                    val id = (child.child("scheduleId").value as? Number)?.toInt()
                        ?: child.child("scheduleId").value?.toString()?.toIntOrNull()
                        ?: child.key?.toIntOrNull() ?: return@mapNotNull null
                    val emp = (child.child("employeeId").value as? Number)?.toInt()
                        ?: child.child("employeeId").value?.toString()?.toIntOrNull() ?: 0
                    if (emp != employeeId) return@mapNotNull null
                    ShiftScheduleRecord(
                        scheduleId = id,
                        employeeId = emp,
                        shiftDate = child.child("shiftDate").value?.toString()
                            ?: child.child("ShiftDate").value?.toString().orEmpty(),
                        startTime = child.child("startTime").value?.toString()
                            ?: child.child("StartTime").value?.toString().orEmpty(),
                        endTime = child.child("endTime").value?.toString()
                            ?: child.child("EndTime").value?.toString().orEmpty(),
                        isRecurringPattern = child.child("isRecurringPattern").value as? Boolean
                            ?: child.child("IsRecurringPattern").value?.toString()?.toBoolean() ?: false,
                        patternDurationDays = (child.child("patternDurationDays").value as? Number)?.toInt()
                            ?: child.child("patternDurationDays").value?.toString()?.toIntOrNull() ?: 7,
                        appliesToDayOfWeek = (child.child("appliesToDayOfWeek").value as? Number)?.toInt()
                            ?: child.child("appliesToDayOfWeek").value?.toString()?.toIntOrNull() ?: 0
                    )
                }.sortedWith(compareBy<ShiftScheduleRecord> { it.shiftDate }.thenBy { it.startTime }.thenBy { it.scheduleId })
                trySend(rows)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseSyncManager", "Employee shift listener cancelled", error.toException())
                trySend(emptyList())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun pushShiftSchedule(record: ShiftScheduleRecord): Boolean {
        if (record.scheduleId <= 0 || record.employeeId <= 0 ||
            record.shiftDate.isBlank() || record.startTime.isBlank() || record.endTime.isBlank())
            return false

        val ref = getOwnerRef()?.child("shift_schedules")?.child(record.scheduleId.toString()) ?: return false
        return try {
            ref.setValue(
                mapOf(
                    "scheduleId" to record.scheduleId,
                    "employeeId" to record.employeeId,
                    "shiftDate" to record.shiftDate,
                    "startTime" to record.startTime,
                    "endTime" to record.endTime,
                    "isRecurringPattern" to record.isRecurringPattern,
                    "patternDurationDays" to record.patternDurationDays,
                    "appliesToDayOfWeek" to record.appliesToDayOfWeek,
                    "_entity" to "ShiftSchedule",
                    "_key" to record.scheduleId.toString(),
                    "_updatedUtc" to java.time.Instant.now().toString()
                )
            ).await()
            notifyRealtimeChanged("ShiftSchedule", "MODIFIED", record.scheduleId.toString())
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Shift schedule write failed", e)
            false
        }
    }

    suspend fun deleteShiftSchedule(scheduleId: Int): Boolean {
        if (scheduleId <= 0) return false
        return try {
            getOwnerRef()?.child("shift_schedules")?.child(scheduleId.toString())?.removeValue()?.await()
            notifyRealtimeChanged("ShiftSchedule", "DELETED", scheduleId.toString())
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Shift schedule delete failed", e)
            false
        }
    }

    suspend fun generateShiftSchedulesFromPatterns(
        startDate: LocalDate = LocalDate.now(),
        endDate: LocalDate = startDate.plusDays(30)
    ): Int {
        val ref = getOwnerRef() ?: return 0
        return try {
            val employeesSnapshot = ref.child("employees").get().await()
            val schedulesSnapshot = ref.child("shift_schedules").get().await()

            val activeEmployeeIds = employeesSnapshot.children.mapNotNull { child ->
                val id = (child.child("employeeId").value as? Number)?.toInt()
                    ?: child.child("employeeId").value?.toString()?.toIntOrNull()
                    ?: child.key?.toIntOrNull()
                val active = child.child("isActive").value as? Boolean
                    ?: child.child("isActive").value?.toString()?.toBoolean() ?: true
                if (id != null && id > 0 && active) id else null
            }.toSet()

            val existing = schedulesSnapshot.children.mapNotNull { child ->
                val id = (child.child("scheduleId").value as? Number)?.toInt()
                    ?: child.key?.toIntOrNull() ?: return@mapNotNull null
                val emp = (child.child("employeeId").value as? Number)?.toInt()
                    ?: child.child("employeeId").value?.toString()?.toIntOrNull() ?: 0
                val date = child.child("shiftDate").value?.toString().orEmpty()
                val start = child.child("startTime").value?.toString().orEmpty()
                val end = child.child("endTime").value?.toString().orEmpty()
                val recurring = child.child("isRecurringPattern").value as? Boolean
                    ?: child.child("isRecurringPattern").value?.toString()?.toBoolean() ?: false
                val day = (child.child("appliesToDayOfWeek").value as? Number)?.toInt()
                    ?: child.child("appliesToDayOfWeek").value?.toString()?.toIntOrNull()
                    ?: runCatching { LocalDate.parse(date).dayOfWeek.value % 7 }.getOrDefault(0)
                ShiftScheduleRecord(id, emp, date, start, end, recurring, 7, day)
            }

            val patterns = existing.filter { it.isRecurringPattern && it.employeeId in activeEmployeeIds }
                .groupBy { it.employeeId }
                .mapValues { (_, rows) -> rows.associateBy { it.appliesToDayOfWeek } }

            val concreteKeys = existing.filter { !it.isRecurringPattern }
                .map { "${it.employeeId}|${it.shiftDate}" }.toMutableSet()

            var nextId = existing.maxOfOrNull { it.scheduleId } ?: 0
            val updates = mutableMapOf<String, Any?>()
            var created = 0
            var date = startDate

            while (!date.isAfter(endDate)) {
                val dayIndex = date.dayOfWeek.value % 7
                for (employeeId in activeEmployeeIds) {
                    val pattern = patterns[employeeId]?.get(dayIndex) ?: continue
                    val unique = "$employeeId|$date"
                    if (!concreteKeys.add(unique)) continue
                    nextId++
                    updates["$nextId"] = mapOf(
                        "scheduleId" to nextId,
                        "employeeId" to employeeId,
                        "shiftDate" to date.toString(),
                        "startTime" to pattern.startTime,
                        "endTime" to pattern.endTime,
                        "isRecurringPattern" to false,
                        "patternDurationDays" to 0,
                        "appliesToDayOfWeek" to dayIndex,
                        "_entity" to "ShiftSchedule",
                        "_key" to nextId.toString(),
                        "_updatedUtc" to java.time.Instant.now().toString()
                    )
                    created++
                }
                date = date.plusDays(1)
            }

            if (updates.isNotEmpty()) {
                ref.child("shift_schedules").updateChildren(updates).await()
                notifyRealtimeChanged("ShiftSchedule", "BULK_MODIFIED")
            }
            created
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Firebase shift generation failed", e)
            0
        }
    }

    suspend fun pushGeofenceRaw(id: String, value: Any?) {
        getOwnerRef()?.child("geofences")?.child(id)?.setValue(value)?.await()
        notifyRealtimeChanged("Geofence", "MODIFIED")
    }

    suspend fun deleteGeofenceRaw(id: String) {
        getOwnerRef()?.child("geofences")?.child(id)?.removeValue()?.await()
        notifyRealtimeChanged("Geofence", "DELETED")
    }

    suspend fun pushShiftRaw(id: String, value: Any?) {
        getOwnerRef()?.child("shift_schedules")?.child(id)?.setValue(value)?.await()
        notifyRealtimeChanged("Shift", "MODIFIED")
    }

    suspend fun deleteShiftRaw(id: String) {
        getOwnerRef()?.child("shift_schedules")?.child(id)?.removeValue()?.await()
        notifyRealtimeChanged("Shift", "DELETED")
    }

    suspend fun bulkRestore(updates: Map<String, Any?>) {
        val ref = getOwnerRef() ?: return
        updates.entries.chunked(300).forEach { chunk ->
            val chunkMap = chunk.associateBy({ it.key }) { it.value }
            ref.updateChildren(chunkMap).await()
        }
        notifyRealtimeChanged("RecycleBinItem", "MODIFIED")
    }

    suspend fun clearSnapshotsAndSummaries() {
        val ref = getOwnerRef() ?: return
        ref.child("monthly_snapshots").removeValue().await()
        ref.child("summaries").removeValue().await()
        notifyRealtimeChanged("SalarySnapshot", "DELETED")
        notifyRealtimeChanged("DailySummary", "DELETED")
    }

    suspend fun clearTable(table: String) {
        getOwnerRef()?.child(table)?.removeValue()?.await()
        notifyRealtimeChanged(table, "DELETED")
    }

    suspend fun deleteShop(shopId: String) { getOwnerRef()?.child("shops")?.child(shopId)?.removeValue()?.await(); notifyRealtimeChanged("Shop", "DELETED") }
    suspend fun deleteAttendance(attendanceId: String) { getOwnerRef()?.child("attendance")?.child(attendanceId)?.removeValue()?.await(); notifyRealtimeChanged("Attendance", "DELETED") }
    suspend fun deletePunch(punchId: String) { getOwnerRef()?.child("attendance_punches")?.child(punchId)?.removeValue()?.await(); notifyRealtimeChanged("AttendancePunch", "DELETED") }
    suspend fun deleteAdvance(advanceId: String) { getOwnerRef()?.child("advance_payments")?.child(advanceId)?.removeValue()?.await(); notifyRealtimeChanged("AdvancePayment", "DELETED") }
    suspend fun deleteClosedDay(dayId: String) { getOwnerRef()?.child("shop_closed_days")?.child(dayId)?.removeValue()?.await(); notifyRealtimeChanged("ShopClosedDay", "DELETED") }
    suspend fun deleteHistory(historyId: String) { getOwnerRef()?.child("employee_history")?.child(historyId)?.removeValue()?.await(); notifyRealtimeChanged("EmployeeHistory", "DELETED") }
    suspend fun deleteRecycleBinItem(id: String) { getOwnerRef()?.child("recycle_bin")?.child(id)?.removeValue()?.await(); notifyRealtimeChanged("RecycleBinItem", "DELETED") }
}
