package com.biometric.app.data.repository

import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.domain.location.TrackingWindowResolver
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Owner-scoped tracking assignments. Precedence: employee -> shop -> global. */
@Singleton
class TrackingScopeRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore,
    private val employeeDao: LocalEmployeeDao,
    private val trackingConfiguration: TrackingConfigurationRepository
) {
    data class Assignment(
        val mode: String = TrackingWindowResolver.MODE_24_7,
        val customStart: String = "",
        val customEnd: String = "",
        val intervalSeconds: Int = 30,
        val enabled: Boolean = true
    ) {
        fun normalized(): Assignment = copy(
            mode = mode.trim().uppercase().let { if (it == "SHIFT" || it == "CUSTOM" || it == TrackingWindowResolver.MODE_24_7) it else TrackingWindowResolver.MODE_24_7 },
            intervalSeconds = intervalSeconds.coerceIn(15, 3600)
        )
    }

    suspend fun saveEmployeeAssignment(employeeId: String, assignment: Assignment) = save("employees", employeeId, assignment)
    suspend fun saveShopAssignment(shopId: String, assignment: Assignment) = save("shops", shopId, assignment)
    suspend fun clearEmployeeAssignment(employeeId: String) = clear("employees", employeeId)
    suspend fun clearShopAssignment(shopId: String) = clear("shops", shopId)

    suspend fun loadEmployeeAssignment(employeeId: String): Assignment? = load("employees", employeeId)
    suspend fun loadShopAssignment(shopId: String): Assignment? = load("shops", shopId)

    fun observeCurrentEmployeeBinding(): kotlinx.coroutines.flow.Flow<String> =
        employeeDao.getAllFlow().map { employees ->
            val employeeId = sessionStore.employeeId().toString()
            employees.firstOrNull { it.employeeId == employeeId }?.shopId.orEmpty()
        }

    suspend fun resolveForCurrentEmployee(): TrackingConfigurationRepository.Config {
        val employeeId = sessionStore.employeeId().toString()
        val employee = employeeDao.getById(employeeId)
        val employeeAssignment = loadEmployeeAssignment(employeeId)
        if (employeeAssignment != null) return employeeAssignment.toConfig()
        val shopAssignment = employee?.shopId?.takeIf { it.isNotBlank() }?.let { loadShopAssignment(it) }
        if (shopAssignment != null) return shopAssignment.toConfig()
        return trackingConfiguration.load()
    }

    /** Realtime effective configuration. The whole scope subtree is observed so scope
     * additions/removals never leave a stale shop/employee listener behind. The current
     * employee binding is resolved from Room on every change. */
    fun startEffectiveListener(onChanged: (TrackingConfigurationRepository.Config) -> Unit): List<Pair<DatabaseReference, ValueEventListener>> {
        val owner = firebaseSync.getOwnerRef() ?: return emptyList()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serviceScopeResolve(onChanged)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        val ref = owner.child("tracking_scopes")
        ref.addValueEventListener(listener)
        // Also observe global configuration because it lives outside tracking_scopes.
        val globalRef = owner.child(TrackingConfigurationRepository.PATH)
        val globalListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serviceScopeResolve(onChanged)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        globalRef.addValueEventListener(globalListener)
        serviceScopeResolve(onChanged)
        return listOf(ref to listener, globalRef to globalListener)
    }

    private fun serviceScopeResolve(onChanged: (TrackingConfigurationRepository.Config) -> Unit) {
        // Firebase callbacks are not suspendable. Resolve on a lightweight coroutine and
        // always re-read the employee's current shop binding from Room, so reassignment
        // cannot leave the previous shop override active.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { resolveForCurrentEmployee() }.onSuccess(onChanged)
        }
    }

    fun removeEffectiveListeners(listeners: List<Pair<DatabaseReference, ValueEventListener>>) {
        val owner = firebaseSync.getOwnerRef() ?: return
        listeners.forEach { (raw, listener) -> (raw as? com.google.firebase.database.DatabaseReference)?.removeEventListener(listener) }
    }

    private suspend fun save(type: String, id: String, assignment: Assignment) {
        require(id.isNotBlank()) { "Scope identifier is required" }
        val owner = firebaseSync.getOwnerRef() ?: error("Firebase owner session is unavailable")
        val a = assignment.normalized()
        owner.child("tracking_scopes").child(type).child(id).setValue(mapOf(
            "mode" to a.mode,
            "customStart" to a.customStart,
            "customEnd" to a.customEnd,
            "intervalSeconds" to a.intervalSeconds,
            "enabled" to a.enabled,
            "updatedBy" to (FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"),
            "updatedAt" to System.currentTimeMillis()
        )).await()
        firebaseSync.notifyRealtimeAfterWrite("TrackingScope", "MODIFIED")
    }

    private suspend fun clear(type: String, id: String) {
        val owner = firebaseSync.getOwnerRef() ?: error("Firebase owner session is unavailable")
        owner.child("tracking_scopes").child(type).child(id).removeValue().await()
        firebaseSync.notifyRealtimeAfterWrite("TrackingScope", "DELETED")
    }

    private suspend fun load(type: String, id: String): Assignment? {
        if (id.isBlank()) return null
        return runCatching {
            val snapshot = firebaseSync.getOwnerRef()?.child("tracking_scopes")?.child(type)?.child(id)?.get()?.await() ?: return@runCatching null
            if (snapshot.exists()) parseAssignment(snapshot) else null
        }.getOrNull()
    }

    private fun parseAssignment(s: DataSnapshot): Assignment? = if (!s.exists()) null else Assignment(
        mode = s.child("mode").getValue(String::class.java) ?: TrackingWindowResolver.MODE_24_7,
        customStart = s.child("customStart").getValue(String::class.java) ?: "",
        customEnd = s.child("customEnd").getValue(String::class.java) ?: "",
        intervalSeconds = s.child("intervalSeconds").getValue(Int::class.java) ?: 30,
        enabled = s.child("enabled").getValue(Boolean::class.java) ?: true
    ).normalized()

    private fun parseConfig(s: DataSnapshot) = TrackingConfigurationRepository.Config(
        mode = s.child("mode").getValue(String::class.java) ?: TrackingWindowResolver.MODE_24_7,
        customStart = s.child("customStart").getValue(String::class.java) ?: "",
        customEnd = s.child("customEnd").getValue(String::class.java) ?: "",
        intervalSeconds = s.child("intervalSeconds").getValue(Int::class.java) ?: 30,
        enabled = s.child("enabled").getValue(Boolean::class.java) ?: true
    )

    private fun Assignment.toConfig() = TrackingConfigurationRepository.Config(mode, customStart, customEnd, intervalSeconds, enabled)
    private fun assignmentFromConfig(c: TrackingConfigurationRepository.Config) = Assignment(c.mode, c.customStart, c.customEnd, c.intervalSeconds, c.enabled)

    companion object {
        const val TYPE_EMPLOYEES = "employees"
        const val TYPE_SHOPS = "shops"
    }
}
