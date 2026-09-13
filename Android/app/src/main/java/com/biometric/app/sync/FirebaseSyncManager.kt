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
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.RealtimeChangedItem
import com.biometric.app.api.RealtimeChangedRequest
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
    private val mobileApi: MobileApiService,
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

    fun getOwnerRef(): DatabaseReference? {
        val uid = auth.currentUser?.uid ?: return null
        return database.child("owners").child(uid)
    }

    private var hasInitializedSync = false

    fun startSync() {
        if (hasInitializedSync) return
        val ref = getOwnerRef() ?: return
        
        ref.child("employees").keepSynced(true)
        ref.child("summaries").keepSynced(true)
        ref.child("monthly_snapshots").keepSynced(true)
        ref.child("attendance_punches").keepSynced(true)
        
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

    fun notifyRealtimeChanged(entity: String, action: String = "MODIFIED") {
        val token = sessionStore.token() ?: return
        syncScope.launch {
            runCatching {
                mobileApi.notifyRealtimeChanged(
                    "Bearer $token",
                    RealtimeChangedRequest(listOf(RealtimeChangedItem(entity, action)))
                )
            }.onFailure {
                // Notification is best-effort. The existing Firebase write is
                // authoritative for this legacy path and must never fail because
                // SignalR is temporarily unavailable.
                Log.w("FirebaseSyncManager", "Realtime notification failed for $entity", it)
            }
        }
    }

    suspend fun pushShop(shop: Shop) { getOwnerRef()?.child("shops")?.child(shop.shopId)?.setValue(shop)?.await(); notifyRealtimeChanged("Shop", "MODIFIED") }
    suspend fun pushEmployee(emp: Employee) { getOwnerRef()?.child("employees")?.child(emp.employeeId)?.setValue(emp)?.await(); notifyRealtimeChanged("Employee", "MODIFIED") }
    suspend fun pushAttendance(att: Attendance) {
        val id = att.attendanceId.ifBlank { return }
        getOwnerRef()?.child("attendance")?.child(id)?.setValue(att)?.await()
        notifyRealtimeChanged("Attendance", "MODIFIED")
    }
    suspend fun pushAttendancePunch(punch: AttendancePunch) {
        val id = punch.punchId.ifBlank { return }
        getOwnerRef()?.child("attendance_punches")?.child(id)?.setValue(punch)?.await()
        notifyRealtimeChanged("AttendancePunch", "MODIFIED")
    }
    suspend fun pushAdvance(adv: AdvancePayment) { getOwnerRef()?.child("advance_payments")?.child(adv.advanceId)?.setValue(adv)?.await(); notifyRealtimeChanged("AdvancePayment", "MODIFIED") }
    
    fun pushSalaryPayment(p: SalaryPayment) {
        val ref = getOwnerRef() ?: return
        ref.child("salary_payments").child(p.paymentId).setValue(p)
        notifyRealtimeChanged("SalaryPayment", "MODIFIED")
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
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {}
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
    }

    suspend fun clearSnapshotsAndSummaries() {
        val ref = getOwnerRef() ?: return
        ref.child("monthly_snapshots").removeValue().await()
        ref.child("summaries").removeValue().await()
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
