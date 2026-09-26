package com.biometric.app.data.repository

import com.biometric.app.api.AdminChangeUserRoleRequest
import com.biometric.app.api.AdminCreateUserRequest
import com.biometric.app.api.AdminResetPasswordRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User management repository supporting canonical roles, de-duplicated users,
 * staff linking, role changes, and password resets matching Web parity.
 */
@Singleton
class UserRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val mobileApi: MobileApiService
) {

    data class UserViewModel(
        val userId: String,
        val firebaseUid: String? = null,
        val email: String,
        val role: String,
        val employeeName: String?,
        val phone: String = "",
        val isDisabled: Boolean = false
    )

    fun observeUsers(): Flow<List<UserViewModel>> =
        firebaseSync.getGlobalDataFlow<UserProfile>("user_profiles")
            .map { profiles ->
                profiles
                    .filter { it.email.isNotBlank() && it.email.contains("@") }
                    .groupBy { it.email.trim().lowercase() }
                    .values
                    .map { group ->
                        val primary = group.first()
                        val canonicalRole = when (primary.role.trim().uppercase()) {
                            "SUPERADMIN" -> "SuperAdmin"
                            "ADMIN" -> "Admin"
                            else -> "Employee"
                        }
                        UserViewModel(
                            userId = primary.uid,
                            firebaseUid = primary.uid,
                            email = primary.email.trim(),
                            role = canonicalRole,
                            employeeName = group.firstOrNull { it.name.isNotBlank() }?.name?.ifBlank { null },
                            phone = primary.phone
                        )
                    }
                    .sortedBy { it.email.lowercase() }
            }

    fun observeEmployees(): Flow<List<Employee>> =
        firebaseSync.getDataFlow<Employee>("employees")
            .map { list -> list.filter { it.isActive }.sortedBy { it.name } }

    suspend fun getAllUsers(): List<UserViewModel> {
        return try {
            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (!token.isNullOrBlank()) {
                val response = mobileApi.getAdminUsers("Bearer $token")
                if (response.isSuccessful && response.body() != null) {
                    return response.body()!!.map { dto ->
                        val canonicalRole = when (dto.role.trim().uppercase()) {
                            "SUPERADMIN" -> "SuperAdmin"
                            "ADMIN" -> "Admin"
                            else -> "Employee"
                        }
                        UserViewModel(
                            userId = dto.userId.ifBlank { dto.firebaseUid ?: "" },
                            firebaseUid = dto.firebaseUid,
                            email = dto.email,
                            role = canonicalRole,
                            employeeName = dto.employeeName,
                            isDisabled = dto.isDisabled
                        )
                    }
                }
            }
            observeUsers().first()
        } catch (_: Exception) {
            observeUsers().first()
        }
    }

    suspend fun updateUserRole(userId: String, newRole: String) {
        if (userId.isBlank()) return
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("Firebase authentication session is missing.")
        val response = mobileApi.changeAdminUserRole("Bearer $token", userId, AdminChangeUserRoleRequest(newRole))
        if (!response.isSuccessful || response.body()?.success != true) {
            throw IllegalStateException(response.body()?.message ?: "Server refused role update.")
        }
    }

    suspend fun resetPassword(userId: String, newPassword: String) {
        if (userId.isBlank()) return
        if (newPassword.length < 6) throw IllegalArgumentException("Password must be at least 6 characters.")
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("Firebase authentication session is missing.")
        val response = mobileApi.resetAdminUserPassword("Bearer $token", userId, AdminResetPasswordRequest(newPassword))
        if (!response.isSuccessful || response.body()?.success != true) {
            throw IllegalStateException(response.body()?.message ?: "Server refused password update.")
        }
    }

    suspend fun deleteUser(userId: String) {
        if (userId.isBlank()) return
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("Firebase authentication session is missing.")
        val response = mobileApi.deleteAdminUser("Bearer $token", userId)
        if (!response.isSuccessful || response.body()?.success != true) {
            throw IllegalStateException(response.body()?.message ?: "Server refused Firebase Auth account deletion.")
        }
    }

    suspend fun createUser(request: AdminCreateUserRequest) {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("Firebase authentication session is missing.")
        val response = mobileApi.createAdminUser("Bearer $token", request)
        if (!response.isSuccessful || response.body()?.success != true) {
            throw IllegalStateException(response.body()?.message ?: "Server refused user creation.")
        }
    }
}
