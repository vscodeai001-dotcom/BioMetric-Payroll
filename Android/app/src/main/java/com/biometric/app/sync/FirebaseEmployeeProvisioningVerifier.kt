package com.biometric.app.sync

import com.biometric.app.data.MobileSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies that a Firebase Employee identity is still bound to the same
 * employee record and tenant before Android creates/continues a session.
 * Claims identify the record; the Firebase employee row confirms the binding.
 */
@Singleton
class FirebaseEmployeeProvisioningVerifier @Inject constructor(
    private val sessionStore: MobileSessionStore
) {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    data class Result(
        val valid: Boolean,
        val message: String = ""
    )

    suspend fun verify(employeeId: Int, ownerUid: String): Result {
        val user = auth.currentUser ?: return Result(false, "Firebase session expired or was cleared by the system. Please sign in again.")
        if (employeeId <= 0 || ownerUid.isBlank()) {
            return Result(false, "Employee Firebase provisioning is incomplete.")
        }

        return runCatching {
            val snapshot = database.getReference("owners")
                .child(ownerUid)
                .child("employees")
                .child(employeeId.toString())
                .get()
                .await()

            if (!snapshot.exists()) {
                return@runCatching Result(false, "Employee record is not provisioned for this account.")
            }

            val rowEmployeeId = snapshot.child("employeeId").value?.toString()?.toLongOrNull() ?: employeeId.toLong()
            if (rowEmployeeId != employeeId.toLong()) {
                return@runCatching Result(false, "Firebase employee identity does not match the employee record.")
            }

            val rowEmail = snapshot.child("email").value?.toString()?.trim().orEmpty()
            val authEmail = user.email?.trim().orEmpty()
            
            // SECURITY RULE: If an email is provisioned in the database record, it MUST match 
            // the authenticated user's email. This prevents "shadow" logins if claims 
            // were somehow stamped incorrectly. 
            // EXCEPTION: We allow the login if the row email is blank (first-time provisioning).
            if (rowEmail.isNotBlank() && authEmail.isNotBlank() && !rowEmail.equals(authEmail, ignoreCase = true)) {
                return@runCatching Result(false, "Firebase employee email ($rowEmail) does not match the provisioned login record ($authEmail). Please ask Admin to verify the employee's email in User Management.")
            }

            val activeValue = snapshot.child("isActive").value
            val active = when (activeValue) {
                is Boolean -> activeValue
                is Number -> activeValue.toInt() != 0
                is String -> activeValue.equals("true", true) || activeValue == "1"
                null -> true
                else -> true
            }
            if (!active) {
                return@runCatching Result(false, "This employee account is inactive or terminated.")
            }

            Result(true)
        }.getOrElse {
            Result(false, "Unable to verify employee provisioning with Firebase.")
        }
    }
}
