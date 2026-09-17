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
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    private val sessionStore: MobileSessionStore
) {

    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().apply {
        try {
            setPersistenceEnabled(true)
            setPersistenceCacheSizeBytes(100 * 1024 * 1024)
        } catch (_: Exception) {}
    }.reference

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

        // Firebase Auth must already be established before the data layer is
        // accessed. The owner UID is normally populated from the Firebase
        // custom claim during login and persisted in MobileSessionStore.
        // Keep the existing single-owner fallback for backward compatibility.
        return if (auth.currentUser != null) "biometricpayroll" else null
    }

    fun getOwnerRef(): DatabaseReference? {
        val uid = getOwnerUid() ?: return null
        return database.child("owners").child(uid)
    }

    private var hasInitializedSync = false

    fun startSync() {
        if (hasInitializedSync) return
        val ref = getOwnerRef() ?: return

        ref.child("employees").keepSynced(true)
        ref.child("shops").keepSynced(true)
        ref.child("attendance").keepSynced(true)
        ref.child("attendance_punches").keepSynced(true)
        ref.child("advance_payments").keepSynced(true)
        ref.child("employee_history").keepSynced(true)
        ref.child("shop_closed_days").keepSynced(true)
        ref.child("regularizations").keepSynced(true)
        ref.child("leave_requests").keepSynced(true)
        ref.child("resignation_requests").keepSynced(true)
        ref.child("salary_snapshots").keepSynced(true)
        ref.child("audit_logs").keepSynced(true)
        ref.child("daily_summaries").keepSynced(true)
        ref.child("shift_schedules").keepSynced(true)
        ref.child("payroll_history").keepSynced(true)
        ref.child("bonus_records").keepSynced(true)
        ref.child("tax_declarations").keepSynced(true)
        ref.child("fbp_components").keepSynced(true)
        ref.child("fbp_declarations").keepSynced(true)

        hasInitializedSync = true
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
                                childSnapshot.getValue(T::class.java)?.let { list.add(it) }
                            }
                        } catch (e: Exception) {
                            Log.e("FirebaseSyncManager", "Failed to convert to ${T::class.java.name} in table '$table'", e)
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
        // miss events during a longer network outage. Neon remains the
        // authoritative reconciliation store, so replaying a small tail is
        // safe and idempotent at the UI/sync layer.
        val ref = database.child("owner_events").child(ownerUid)
            .orderByChild("timestamp")
            .limitToLast(200)
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
                Log.w("FirebaseSyncManager", "Owner realtime event listener cancelled", error.toException())
            }
        }
        ref.addChildEventListener(listener)
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
    suspend fun pushLiveLocation(
        employeeId: Int,
        sessionId: String,
        clientEventId: String,
        sequence: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        speed: Double,
        batteryLevel: Int,
        timestamp: Long
    ): Boolean {
        if (employeeId <= 0 || sessionId.isBlank() || clientEventId.isBlank()) return false

        val payload = mapOf(
            "EmployeeId" to employeeId,
            "SessionId" to sessionId,
            "Latitude" to latitude,
            "Longitude" to longitude,
            "AccuracyMeters" to accuracy.coerceAtLeast(0.0),
            "SpeedMps" to speed.coerceAtLeast(0.0),
            "BatteryLevel" to batteryLevel,
            "Sequence" to sequence,
            "Timestamp" to Date(timestamp).toInstant().toString(),
            "LastUpdatedUtc" to Date().toInstant().toString(),
            "Source" to "ANDROID_FIREBASE"
        )

        if (!isAuthenticated()) {
            Log.w("FirebaseSyncManager", "GPS Firebase write deferred: Firebase user is not authenticated")
            return false
        }

        return try {
            getGlobalRef().updateChildren(
                mapOf(
                    "tracking/live/$employeeId" to payload,
                    "tracking/history/$employeeId/$clientEventId" to payload
                )
            ).await()
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
    suspend fun pushRecycleBin(item: RecycleBinItem) { getOwnerRef()?.child("recycle_bin")?.child(item.id)?.setValue(item)?.await(); notifyRealtimeChanged("RecycleBinItem", "MODIFIED") }
    suspend fun pushAuditLog(log: AuditLog) { getOwnerRef()?.child("audit_logs")?.child(log.logId)?.setValue(log)?.await(); notifyRealtimeChanged("AuditLog", "ADDED") }

    suspend fun pushTrackingEvent(event: OfflineTrackingEvent): Boolean {
        if (!isAuthenticated()) return false
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return false

        return try {
            getGlobalRef().child("tracking/events").child(employeeId.toString())
                .child(event.eventId).setValue(event).await()
            true
        } catch (e: Exception) {
            Log.w("FirebaseSyncManager", "Tracking event write failed", e)
            false
        }
    }

    suspend fun notifyRealtimeAfterWrite(entity: String, action: String = "MODIFIED") {
        notifyRealtimeChanged(entity, action)
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
        getOwnerRef()?.child("shifts")?.child(id)?.setValue(value)?.await()
        notifyRealtimeChanged("Shift", "MODIFIED")
    }

    suspend fun deleteShiftRaw(id: String) {
        getOwnerRef()?.child("shifts")?.child(id)?.removeValue()?.await()
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
