package com.biometric.app.data.repository

import com.biometric.app.api.MobileApiService
import com.biometric.app.api.MobileLoginRequest
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.data.entity.UserRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val mobileApi: MobileApiService
) {
    suspend fun login(email: String, pass: String, deviceId: String): UserProfile? {
        val response = mobileApi.login(MobileLoginRequest(email = email, password = pass, deviceId = deviceId))
        if (response.isSuccessful) {
            val res = response.body()
            if (res?.success == true) {
                // Normalize role
                val rawRole = res.role ?: ""
                val role = when {
                    rawRole.contains("Super", true) -> UserRole.SUPER_ADMIN.name
                    rawRole.contains("Admin", true) -> UserRole.ADMIN.name
                    rawRole.contains("Employee", true) -> UserRole.STAFF.name
                    res.name.contains("Admin", true) -> UserRole.ADMIN.name
                    else -> UserRole.STAFF.name
                }

                return UserProfile(
                    uid = if (res.employeeId != 0) res.employeeId.toString() else res.email,
                    name = res.name,
                    email = res.email,
                    role = role,
                    phone = ""
                )
            }
        }
        return null
    }

    suspend fun checkDeviceBinding(uid: String, deviceId: String): Boolean {
        // Neon DB handles this on server side
        return true
    }

    suspend fun updateDeviceBinding(uid: String, deviceId: String, deviceModel: String, androidVersion: Int) {
        // Neon DB handles this on server side
    }
}
