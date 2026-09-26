package com.biometric.app.data.entity

import androidx.annotation.Keep
import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties

enum class UserRole {
    SuperAdmin, Admin, Employee
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    var uid: String = "",
    var name: String = "",
    var email: String = "",
    var phone: String = "",
    var employeeId: String = "",
    var password: String = "",
    var role: String = UserRole.Employee.name,
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
    var dataLastModified: Long = System.currentTimeMillis(),
    var ownerUid: String? = null,
    var enabled: Boolean = true
) {
    @Exclude
    fun isSuperAdmin() = role.equals(UserRole.SuperAdmin.name, ignoreCase = true)

    @Exclude
    fun isAdmin() = role.equals(UserRole.Admin.name, ignoreCase = true)

    @Exclude
    fun isStaff() = role.equals(UserRole.Employee.name, ignoreCase = true) || role.equals("STAFF", ignoreCase = true)
    
    @Exclude
    fun isOwner() = isSuperAdmin()

    companion object {
        const val ROLE_OWNER = "SuperAdmin"
    }
}
