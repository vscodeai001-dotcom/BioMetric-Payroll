package com.biometric.app.data.repository

import com.biometric.app.data.entity.UserProfile
import com.biometric.app.sync.FirebaseSyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase-backed user management for the native Admin/SuperAdmin UI.
 *
 * Android does not connect directly to Neon/PostgreSQL. Firebase is the
 * realtime user-profile/read-model source for the mobile administrative UI.
 * Firebase Authentication account deletion remains a server-side concern and
 * is therefore not impersonated by a client-side database delete.
 */
@Singleton
class UserRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager
) {

    data class UserViewModel(
        val userId: String,
        val email: String,
        val role: String,
        val employeeName: String?,
        val phone: String = ""
    )

    fun observeUsers(): Flow<List<UserViewModel>> =
        firebaseSync.getDataFlow<UserProfile>("user_profiles")
            .map { profiles ->
                profiles
                    .filter { it.uid.isNotBlank() }
                    .map { profile ->
                        UserViewModel(
                            userId = profile.uid,
                            email = profile.email,
                            role = profile.role,
                            employeeName = profile.name.ifBlank { null },
                            phone = profile.phone
                        )
                    }
                    .sortedBy { it.email.lowercase() }
            }

    suspend fun getAllUsers(): List<UserViewModel> =
        observeUsers().first()

    suspend fun deleteUser(userId: String) {
        if (userId.isBlank()) return
        firebaseSync.getGlobalRef()
            .child("user_profiles")
            .child(userId)
            .removeValue()
            .await()
        firebaseSync.notifyRealtimeAfterWrite("UserProfile", "DELETED")
    }
}
