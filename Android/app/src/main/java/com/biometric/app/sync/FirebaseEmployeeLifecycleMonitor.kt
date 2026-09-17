package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime Employee lifecycle monitor.
 *
 * Web/Admin changes to the provisioned employee row or the single-device
 * session are observed directly from Firebase. This is intentionally a
 * security/session concern only; payroll, attendance and calculation rules
 * remain in their existing repositories/services.
 */
@Singleton
class FirebaseEmployeeLifecycleMonitor @Inject constructor(
    private val sessionStore: MobileSessionStore,
    private val provisioningVerifier: FirebaseEmployeeProvisioningVerifier,
    private val sessionManager: FirebaseEmployeeSessionManager
) {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private var employeeListener: ValueEventListener? = null
    private var sessionListener: ValueEventListener? = null
    private var employeeRef: DatabaseReference? = null
    private var sessionRef: DatabaseReference? = null
    private var verificationJob: Job? = null
    private val running = AtomicBoolean(false)

    data class State(val valid: Boolean, val reason: String = "")

    fun start(scope: CoroutineScope, onInvalid: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return

        val user = auth.currentUser
        val employeeId = sessionStore.employeeId()
        val ownerUid = sessionStore.firebaseOwnerUid().orEmpty()
        if (user == null || employeeId <= 0 || ownerUid.isBlank()) {
            running.set(false)
            onInvalid("Employee Firebase session is incomplete. Please sign in again.")
            return
        }

        val invalidated = AtomicBoolean(false)
        fun invalidate(reason: String) {
            if (invalidated.compareAndSet(false, true)) {
                Log.w(TAG, reason)
                stop()
                onInvalid(reason)
            }
        }

        employeeRef = database.getReference("owners")
            .child(ownerUid).child("employees").child(employeeId.toString())
        sessionRef = database.getReference("employee_sessions").child(user.uid)

        employeeListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    invalidate("Your employee record was removed or is no longer provisioned.")
                    return
                }
                if (!isActive(snapshot.child("isActive").value)) {
                    invalidate("Your employee account has been deactivated or terminated.")
                    return
                }
                val rowId = snapshot.child("employeeId").value?.toString()?.toLongOrNull()
                if (rowId != null && rowId != employeeId.toLong()) {
                    invalidate("Your Firebase employee identity no longer matches this device session.")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Employee lifecycle listener cancelled: ${error.message}")
            }
        }
        employeeRef!!.addValueEventListener(employeeListener!!)

        sessionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val device = snapshot.child("deviceId").getValue(String::class.java).orEmpty()
                val activeEmployeeId = snapshot.child("employeeId").value?.toString()?.toIntOrNull() ?: 0
                val activeOwner = snapshot.child("ownerUid").getValue(String::class.java).orEmpty()
                if (!snapshot.exists() || device != sessionStore.deviceId() ||
                    activeEmployeeId != employeeId || activeOwner != ownerUid) {
                    invalidate("This employee session is no longer active on this device.")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Employee session listener cancelled: ${error.message}")
            }
        }
        sessionRef!!.addValueEventListener(sessionListener!!)

        // Claims can change after Web role/employee administration. Refreshing
        // periodically catches those changes even when the employee row itself
        // has not changed. Five minutes avoids a tight token-refresh loop.
        verificationJob = scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                delay(CLAIM_REFRESH_INTERVAL_MS)
                if (!running.get()) break
                val userNow = auth.currentUser ?: run {
                    invalidate("Firebase authentication session ended.")
                    break
                }
                val token = runCatching { userNow.getIdToken(true).await() }.getOrNull()
                val role = token?.claims?.get("role")?.toString().orEmpty()
                val claimOwner = token?.claims?.get("owner_uid")?.toString().orEmpty()
                val claimEmployee = token?.claims?.get("employee_id")?.toString()?.toIntOrNull() ?: 0
                if (!role.equals("Employee", true) && !role.equals("Staff", true)) {
                    invalidate("Your Firebase role is no longer an Employee role.")
                    break
                }
                if (claimOwner != ownerUid || claimEmployee != employeeId) {
                    invalidate("Your Firebase employee identity or tenant binding has changed.")
                    break
                }
                val verified = provisioningVerifier.verify(employeeId, ownerUid)
                if (!verified.valid) {
                    invalidate(verified.message)
                    break
                }
            }
        }
    }

    fun stop() {
        employeeListener?.let { employeeRef?.removeEventListener(it) }
        sessionListener?.let { sessionRef?.removeEventListener(it) }
        employeeListener = null
        sessionListener = null
        employeeRef = null
        sessionRef = null
        verificationJob?.cancel()
        verificationJob = null
        running.set(false)
    }

    private fun isActive(value: Any?): Boolean = when (value) {
        null -> true
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> true
    }

    companion object {
        private const val TAG = "EmployeeLifecycle"
        private const val CLAIM_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }
}
