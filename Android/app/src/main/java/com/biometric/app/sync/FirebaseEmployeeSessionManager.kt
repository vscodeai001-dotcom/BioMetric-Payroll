package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Firebase-native Employee device/session gate.
 *
 * The existing Employee screens and business APIs remain unchanged. This class
 * only owns the authentication-session lock so Android Employee login does not
 * have to call Payroll.Web merely to establish a session.
 */
@Singleton
class FirebaseEmployeeSessionManager @Inject constructor(
    private val sessionStore: MobileSessionStore
) {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    data class Result(
        val success: Boolean,
        val conflict: Boolean = false,
        val message: String = ""
    )

    suspend fun acquire(
        employeeId: Int,
        ownerUid: String,
        forceReplace: Boolean = false
    ): Result {
        val user = auth.currentUser
            ?: return Result(false, message = "Firebase authentication session is missing.")

        if (employeeId <= 0 || ownerUid.isBlank()) {
            return Result(false, message = "Employee Firebase profile is not provisioned yet.")
        }

        val deviceId = sessionStore.deviceId()
        val ref = database.getReference("employee_sessions").child(user.uid)
        val now = System.currentTimeMillis()

        return runCatching {
            val transaction = runTransaction(ref, employeeId, ownerUid, deviceId, now, forceReplace)
            when {
                transaction.first -> Result(true)
                transaction.second -> Result(
                    success = false,
                    conflict = true,
                    message = "This employee is already logged in on another device."
                )
                else -> Result(false, message = "Unable to establish the Firebase employee session.")
            }
        }.getOrElse {
            Log.e("FirebaseEmployeeSession", "Employee Firebase session transaction failed", it)
            Result(false, message = "Unable to establish the Firebase employee session.")
        }
    }

    private suspend fun runTransaction(
        ref: com.google.firebase.database.DatabaseReference,
        employeeId: Int,
        ownerUid: String,
        deviceId: String,
        now: Long,
        forceReplace: Boolean
    ): Pair<Boolean, Boolean> = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val existingDevice = currentData.child("deviceId").getValue(String::class.java).orEmpty()
                val exists = currentData.value != null && existingDevice.isNotBlank()

                if (exists && existingDevice != deviceId && !forceReplace) {
                    return Transaction.abort()
                }

                if (!exists) {
                    currentData.child("createdAt").value = now
                }

                currentData.child("deviceId").value = deviceId
                currentData.child("employeeId").value = employeeId
                currentData.child("ownerUid").value = ownerUid
                currentData.child("uid").value = auth.currentUser?.uid.orEmpty()
                currentData.child("lastSeenAt").value = now
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (resumed.getAndSet(true)) return
                if (error != null) {
                    continuation.resume(false to false)
                    return
                }
                if (committed) {
                    continuation.resume(true to false)
                } else {
                    val currentDevice = currentData?.child("deviceId")?.getValue(String::class.java).orEmpty()
                    continuation.resume(false to currentDevice.isNotBlank() && currentDevice != deviceId)
                }
            }
        })
        continuation.invokeOnCancellation {
            // Firebase SDK owns cancellation of the underlying transaction.
        }
    }
}
