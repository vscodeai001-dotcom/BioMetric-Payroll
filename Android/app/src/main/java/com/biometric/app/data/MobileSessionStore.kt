package com.biometric.app.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileSessionStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs get() = context.getSharedPreferences("mobile_session", Context.MODE_PRIVATE)

    fun saveLogin(token: String, employeeId: Int, name: String, email: String = "") {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putInt(KEY_EMPLOYEE_ID, employeeId)
            putString(KEY_NAME, name)
            putString(KEY_EMAIL, email)
            putBoolean(KEY_ACTIVE, true)
        }
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)
    fun employeeId(): Int = prefs.getInt(KEY_EMPLOYEE_ID, 0)
    fun employeeName(): String = prefs.getString(KEY_NAME, "") ?: ""
    fun userEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""
    fun userThemeKey(): String = userEmail().ifBlank { "employee-${employeeId()}" }
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_ACTIVE, false) && !token().isNullOrBlank()

    fun isReliabilitySetupDone(): Boolean = prefs.getBoolean(KEY_RELIABILITY_DONE, false)
    fun setReliabilitySetupDone(done: Boolean) { prefs.edit { putBoolean(KEY_RELIABILITY_DONE, done) } }

    fun deviceId(): String = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID
    ) ?: "android-${UUID.nameUUIDFromBytes(android.os.Build.MODEL.toByteArray())}"

    fun gpsSessionId(): String {
        val existing = prefs.getString(KEY_GPS_SESSION, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_GPS_SESSION, created) }
        return created
    }

    fun clearGpsSession() { prefs.edit { remove(KEY_GPS_SESSION) } }

    fun saveDashboardCache(json: String) { prefs.edit { putString("dashboard_stats_cache", json) } }
    fun getDashboardCache(): String? = prefs.getString("dashboard_stats_cache", null)

    fun clearLogin() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_ACTIVE = "active"
        private const val KEY_GPS_SESSION = "gps_session_id"
        private const val KEY_RELIABILITY_DONE = "reliability_setup_done"
    }
}
