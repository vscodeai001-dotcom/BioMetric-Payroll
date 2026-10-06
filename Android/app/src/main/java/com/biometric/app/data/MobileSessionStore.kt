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

    fun saveLogin(token: String, employeeId: Int, name: String, email: String = "", firebaseOwnerUid: String? = null) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putInt(KEY_EMPLOYEE_ID, employeeId)
            putString(KEY_NAME, name)
            putString(KEY_EMAIL, email)
            // Preserve the active tenant if one is already selected (e.g. for Admin/SuperAdmin)
            val currentActiveTenant = prefs.getString(KEY_ACTIVE_TENANT_ID, null)?.takeIf { it.isNotBlank() }
                ?: context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tenant_id", null)?.takeIf { it.isNotBlank() }
            if (!currentActiveTenant.isNullOrBlank()) {
                putString(KEY_ACTIVE_TENANT_ID, currentActiveTenant)
                putString(KEY_FIREBASE_OWNER_UID, currentActiveTenant)
            } else if (!firebaseOwnerUid.isNullOrBlank()) {
                putString(KEY_FIREBASE_OWNER_UID, firebaseOwnerUid)
            }
            putBoolean(KEY_ACTIVE, true)
        }
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)
    fun employeeId(): Int = prefs.getInt(KEY_EMPLOYEE_ID, 0)
    fun employeeName(): String = prefs.getString(KEY_NAME, "") ?: ""
    fun updateEmployeeName(name: String) { if (name.isNotBlank()) prefs.edit { putString(KEY_NAME, name) } }
    fun userEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""
    fun userRole(): String = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        .getString("user_role", "") ?: ""

    fun firebaseOwnerUid(): String? {
        val role = userRole().trim().uppercase()
        val isAdmin = role in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
        if (isAdmin) {
            val active = activeTenantId()
            if (!active.isNullOrBlank()) return active
        }
        return prefs.getString(KEY_FIREBASE_OWNER_UID, null)?.takeIf { it.isNotBlank() }
            ?: activeTenantId()
    }

    fun userThemeKey(): String = userEmail().ifBlank { "employee-${employeeId()}" }
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_ACTIVE, false) && !token().isNullOrBlank()

    fun isReliabilitySetupDone(): Boolean = prefs.getBoolean(KEY_RELIABILITY_DONE, false)
    fun setReliabilitySetupDone(done: Boolean) { prefs.edit { putBoolean(KEY_RELIABILITY_DONE, done) } }

    fun setFirebaseOwnerUid(uid: String) {
        if (uid.isNotBlank()) {
            prefs.edit { putString(KEY_FIREBASE_OWNER_UID, uid) }
        } else {
            prefs.edit { remove(KEY_FIREBASE_OWNER_UID) }
        }
    }

    fun saveActiveTenant(tenantId: String, name: String, code: String) {
        prefs.edit {
            putString(KEY_ACTIVE_TENANT_ID, tenantId)
            putString(KEY_FIREBASE_OWNER_UID, tenantId)
            putString(KEY_ACTIVE_TENANT_NAME, name)
            putString(KEY_ACTIVE_TENANT_CODE, code)
        }
        runCatching {
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit()
                .putString("selected_tenant_id", tenantId)
                .putString("selected_tenant_name", name)
                .putString("selected_tenant_code", code)
                .putString("firebase_owner_uid", tenantId)
                .apply()
        }
    }

    fun activeTenantId(): String? {
        val active = prefs.getString(KEY_ACTIVE_TENANT_ID, null)?.takeIf { it.isNotBlank() }
        if (!active.isNullOrBlank()) return active

        val authTenant = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            .getString("selected_tenant_id", null)?.takeIf { it.isNotBlank() }
        if (!authTenant.isNullOrBlank()) return authTenant

        val code = activeCompanyCode().takeIf { it.isNotBlank() && !it.equals("ACTIVE", ignoreCase = true) }
        if (code != null) return if (code.startsWith("tenant_")) code else "tenant_$code"

        return prefs.getString(KEY_FIREBASE_OWNER_UID, null)?.takeIf { it.isNotBlank() }
    }

    fun clearActiveTenant() {
        prefs.edit {
            remove(KEY_ACTIVE_TENANT_ID)
            remove(KEY_FIREBASE_OWNER_UID)
            remove(KEY_ACTIVE_TENANT_NAME)
            remove(KEY_ACTIVE_TENANT_CODE)
        }
        runCatching {
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit()
                .remove("selected_tenant_id")
                .remove("selected_tenant_name")
                .remove("selected_tenant_code")
                .remove("firebase_owner_uid")
                .apply()
        }
    }

    fun activeCompanyName(): String = prefs.getString(KEY_ACTIVE_TENANT_NAME, null)?.takeIf { it.isNotBlank() } ?: ""
    fun activeCompanyCode(): String = prefs.getString(KEY_ACTIVE_TENANT_CODE, null)?.takeIf { it.isNotBlank() } ?: ""

    fun isOfflineMode(): Boolean = prefs.getBoolean(KEY_IS_OFFLINE_MODE, false)
    fun setOfflineMode(offline: Boolean) { prefs.edit { putBoolean(KEY_IS_OFFLINE_MODE, offline) } }

    fun deploymentMode(): String = prefs.getString(KEY_DEPLOYMENT_MODE, "CloudOnly") ?: "CloudOnly"
    fun setDeploymentMode(mode: String) { prefs.edit { putString(KEY_DEPLOYMENT_MODE, mode) } }

    private val syncStatePrefs get() = context.getSharedPreferences("app_sync_state", Context.MODE_PRIVATE)

    fun lastProcessedWipeTimestamp(): Long = syncStatePrefs.getLong("last_processed_wipe_timestamp", 0L)
    fun setLastProcessedWipeTimestamp(timestamp: Long) {
        syncStatePrefs.edit { putLong("last_processed_wipe_timestamp", timestamp) }
    }

    fun deviceId(): String = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID
    ) ?: "android-${UUID.nameUUIDFromBytes(android.os.Build.MODEL.toByteArray())}"

    fun currentGpsSessionId(): String? = prefs.getString(KEY_GPS_SESSION, null)?.takeIf { it.isNotBlank() }

    fun gpsSessionId(): String {
        val existing = prefs.getString(KEY_GPS_SESSION, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_GPS_SESSION, created) }
        return created
    }

    /**
     * Rebinds the durable local tracking session after the server/Firebase
     * session has been ended by another device/platform. This prevents an old
     * session ID from being reused after recovery.
     */
    fun setGpsSessionId(sessionId: String) {
        if (sessionId.isBlank()) return
        prefs.edit { putString(KEY_GPS_SESSION, sessionId) }
    }

    fun clearGpsSession() { prefs.edit { remove(KEY_GPS_SESSION) } }

    fun saveDashboardCache(json: String) { prefs.edit { putString("dashboard_stats_cache", json) } }
    fun getDashboardCache(): String? = prefs.getString("dashboard_stats_cache", null)

    fun saveAuthCredentials(email: String, pass: String) {
        if (email.isNotBlank() && pass.isNotBlank()) {
            val encoded = android.util.Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            prefs.edit {
                putString(KEY_SAVED_AUTH_EMAIL, email)
                putString(KEY_SAVED_AUTH_PASS, encoded)
            }
        }
    }

    fun getSavedAuthCredentials(): Pair<String?, String?> {
        val email = prefs.getString(KEY_SAVED_AUTH_EMAIL, null)
        val encoded = prefs.getString(KEY_SAVED_AUTH_PASS, null)
        val pass = if (!encoded.isNullOrBlank()) {
            runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
        } else null
        return Pair(email, pass)
    }

    fun clearLogin() {
        clearGpsSession()
        prefs.edit { clear() }
    }

    fun firebasePlanMode(): String = prefs.getString(KEY_FIREBASE_PLAN_MODE, "Spark") ?: "Spark"
    fun setFirebasePlanMode(mode: String) { prefs.edit { putString(KEY_FIREBASE_PLAN_MODE, mode) } }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_FIREBASE_OWNER_UID = "firebase_owner_uid"
        private const val KEY_ACTIVE_TENANT_ID = "active_tenant_id"
        private const val KEY_FIREBASE_PLAN_MODE = "firebase_plan_mode"
        private const val KEY_ACTIVE = "active"
        private const val KEY_GPS_SESSION = "gps_session_id"
        private const val KEY_RELIABILITY_DONE = "reliability_setup_done"
        private const val KEY_IS_OFFLINE_MODE = "is_offline_mode"
        private const val KEY_DEPLOYMENT_MODE = "deployment_mode"
        private const val KEY_ACTIVE_TENANT_NAME = "active_tenant_name"
        private const val KEY_ACTIVE_TENANT_CODE = "active_tenant_code"
        private const val KEY_SAVED_AUTH_EMAIL = "saved_auth_email"
        private const val KEY_SAVED_AUTH_PASS = "saved_auth_pass"
    }
}
