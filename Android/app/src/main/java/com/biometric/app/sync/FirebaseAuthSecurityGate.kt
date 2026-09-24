package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.sync.ssot.FirebaseSsotSchema
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
                // Firebase Auth state was cleared (e.g. force-stop on some Android versions clears
                // internal Firebase Auth storage). If we allow bypass here, FirebaseRoomHydrator
                // will start with null currentUser → ALL Realtime Database listeners receive
                // "Permission denied" because the RTDB SDK connects unauthenticated.
                // Route to LoginActivity for re-authentication. The local session (MobileSessionStore)
                // is preserved so the user only needs to enter their password once.
                Log.w("FirebaseAuthSecurity", "Firebase user is null but Admin is logged in locally; routing to re-authenticate.")
                return Result(false, message = "Firebase session needs to be refreshed. Please sign in again.")
            }
            return Result(false, message = "Firebase session expired or was cleared by the system. Please sign in again.")
        }

        // Do not force a network refresh on every app start. Firebase already
        // persists and refreshes valid sessions automatically. A forced refresh
        // here made a valid login fail during a temporary network outage.
        // Only fall back to a forced refresh when the cached token is unavailable.
        val token = runCatching {
            val initial = user.getIdToken(false).await()
            val nowSec = System.currentTimeMillis() / 1000L
            if (initial.expirationTimestamp <= nowSec + 120L) {
                Log.i("FirebaseAuthSecurity", "Cached Firebase ID token expired or expiring soon; force-refreshing.")
                user.getIdToken(true).await()
            } else {
                initial
            }
        }.getOrElse { tokenError ->
            Log.w("FirebaseAuthSecurity", "Initial token fetch failed; attempting forced refresh.", tokenError)
            runCatching { user.getIdToken(true).await() }.getOrElse { refreshError ->
                Log.w("FirebaseAuthSecurity", "Unable to refresh Firebase ID token", refreshError)
                runCatching { user.getIdToken(false).await() }.getOrElse { cachedFallback ->
                    if (isAdmin && sessionLoggedIn) {
                       Log.w("FirebaseAuthSecurity", "Admin token refresh failed; routing to re-authenticate to restore Firebase session.")
                       return Result(false, message = "Firebase authentication session could not be refreshed. Please sign in again.")
                    }
                    return Result(false, message = "Firebase authentication session could not be refreshed.")
                }
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
            ?: sessionStore.firebaseOwnerUid()?.takeIf { it.isNotBlank() }
            ?: if (role == UserRole.SuperAdmin.name || isCanonicalSuperAdmin) FirebaseSsotSchema.DEFAULT_OWNER_UID else ""

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
