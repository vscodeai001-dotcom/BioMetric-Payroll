package com.biometric.app.data.entity

import androidx.annotation.Keep

enum class UserRole {
    SUPER_ADMIN, ADMIN, STAFF
}

@Keep
data class UserProfile(
    var uid: String = "",
    var name: String = "",
    var email: String = "",
    var phone: String = "",
    var employeeId: String = "",
    var password: String = "",
    var role: String = UserRole.STAFF.name,
    var joinDate: Long = System.currentTimeMillis(),
    var deviceId: String? = null,
    var deviceModel: String? = null,
    var androidVersion: Int = 0,
    var registeredAt: Long? = null,
    var isPremium: Boolean = false,
    var subscriptionExpiry: Long? = null,
    var lastCheckTimestamp: Long = System.currentTimeMillis(),
    var enabledFeatures: List<String>? = null,
    var brandingName: String? = null,
    var brandingLogoUrl: String? = null,
    var theme: String = "light",
    var dataLastModified: Long = System.currentTimeMillis()
) {
    fun isSuperAdmin() = role == UserRole.SUPER_ADMIN.name
    fun isAdmin() = role == UserRole.ADMIN.name
    fun isStaff() = role == UserRole.STAFF.name
    
    fun isOwner() = isSuperAdmin()

    companion object {
        const val ROLE_OWNER = "SUPER_ADMIN"
    }
}
