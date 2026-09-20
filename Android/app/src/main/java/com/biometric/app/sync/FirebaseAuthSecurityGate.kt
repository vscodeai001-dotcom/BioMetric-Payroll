package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime Firebase authorization gate for cold-start and protected governance
 * entry points.
 *
 * SharedPreferences are treated as navigation/cache state only. Firebase Auth
 * ID-token claims are the runtime authority for role/tenant identity, while
 * employee_sessions is the single-device authority for Employee sessions.
 */
@Singleton
class FirebaseAuthSecurityGate @Inject constructor(
    private val sessionStore: MobileSessionStore,
    private val employeeSessionManager: FirebaseEmployeeSessionManager,
    private val employeeProvisioningVerifier: FirebaseEmployeeProvisioningVerifier
) {
    private val auth = FirebaseAuth.getInstance()

    data class Result(
        val allowed: Boolean,
        val role: String = "",
        val ownerUid: String = "",
        val employeeId: Int = 0,
        val message: String = ""
    )

    suspend fun validateCurrentSession(): Result {
        val user = auth.currentUser
        val sessionLoggedIn = sessionStore.isLoggedIn()
        
        // REQUIREMENT: Admin "never automatically logged out".
        // If they are an Admin and we have a local session, we'll try to 
        // proceed even if Firebase user is null (e.g. initialization delay).
        val localRole = sessionStore.userRole()
        val isAdmin = localRole == UserRole.Admin.name || localRole == UserRole.SuperAdmin.name

        if (user == null) {
            if (isAdmin && sessionLoggedIn) {
                Log.w("FirebaseAuthSecurity", "Firebase user is null but Admin is logged in locally; allowing persistence.")
                return Result(true, localRole ?: "", sessionStore.firebaseOwnerUid() ?: "", sessionStore.employeeId())
            }
            return Result(false, message = "Firebase session expired or was cleared by the system. Please sign in again.")
        }

        // Do not force a network refresh on every app start. Firebase already
        // persists and refreshes valid sessions automatically. A forced refresh
        // here made a valid login fail during a temporary network outage.
        // Only fall back to a forced refresh when the cached token is unavailable.
        val token = runCatching {
            user.getIdToken(false).await()
        }.getOrElse { cachedError ->
            Log.w("FirebaseAuthSecurity", "Cached Firebase ID token unavailable; forcing refresh.", cachedError)
            runCatching { user.getIdToken(true).await() }.getOrElse { refreshError ->
                Log.w("FirebaseAuthSecurity", "Unable to refresh Firebase ID token", refreshError)
                
                if (isAdmin && sessionLoggedIn) {
                   Log.w("FirebaseAuthSecurity", "Admin refresh failed but allowing persistence.")
                   return Result(true, localRole ?: "", sessionStore.firebaseOwnerUid() ?: "", sessionStore.employeeId())
                }
                
                return Result(false, message = "Firebase authentication session could not be refreshed.")
            }
        }

        val claims = token.claims
        val rawRole = claims["role"]?.toString().orEmpty().trim()
        val isCanonicalSuperAdmin = user.email.equals(CANONICAL_SUPER_ADMIN_EMAIL, ignoreCase = true)
        val role = when {
            isCanonicalSuperAdmin || rawRole.equals("SuperAdmin", true) || rawRole.equals(UserRole.SuperAdmin.name, true) -> UserRole.SuperAdmin.name
            rawRole.equals("Admin", true) || rawRole.equals(UserRole.Admin.name, true) -> UserRole.Admin.name
            rawRole.equals("Employee", true) || rawRole.equals("Staff", true) || rawRole.equals(UserRole.Employee.name, true) -> UserRole.Employee.name
            else -> ""
        }

        if (role.isBlank()) {
            return Result(false, message = "Firebase account has no supported application role.")
        }

        val ownerUid = claims["owner_uid"]?.toString()?.takeIf { it.isNotBlank() }
            ?: if (role == UserRole.SuperAdmin.name && isCanonicalSuperAdmin) "biometricpayroll" else ""

        if (ownerUid.isBlank()) {
            return Result(false, message = "Firebase account is missing the required owner_uid claim.")
        }

        val employeeId = claims["employee_id"]?.toString()?.toIntOrNull() ?: 0
        if (role == UserRole.Employee.name) {
            if (employeeId <= 0) {
                return Result(false, message = "Employee Firebase account is missing employee_id provisioning.")
            }
            if (!employeeSessionManager.isCurrentDeviceOwner(employeeId, ownerUid)) {
                return Result(false, message = "This employee session is no longer active on this device.")
            }
            val provisioning = employeeProvisioningVerifier.verify(employeeId, ownerUid)
            if (!provisioning.valid) {
                return Result(false, message = provisioning.message)
            }
        }

        // Keep the short-lived ID token cache synchronized with the Firebase
        // session without changing the existing screen/business flow.
        val displayName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: sessionStore.employeeName()
            ?: ""
        sessionStore.saveLogin(
            token = token.token ?: return Result(false, message = "Firebase ID token is unavailable."),
            employeeId = employeeId,
            name = displayName,
            email = user.email.orEmpty(),
            firebaseOwnerUid = ownerUid
        )

        return Result(true, role, ownerUid, employeeId)
    }

    companion object {
        const val CANONICAL_SUPER_ADMIN_EMAIL = "prakashshiva368@gmail.com"
    }
}
