package com.biometric.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.sync.FirebaseAuthSecurityGate
import com.biometric.app.sync.FirebaseEmployeeLifecycleMonitor
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.core.content.edit

/**
 * Central session guard for protected Android screens.
 *
 * IMPORTANT:
 * - A valid persisted mobile session is sufficient to keep the user logged in.
 * - Closing the app, removing it from Recents, or a process restart must NOT
 *   manufacture a new security-login loop.
 * - An explicit logout clears the persisted session and returns to LoginActivity.
 * - This class does not change attendance, GPS, payroll, or business rules.
 */
abstract class SecurityBaseActivity : AppCompatActivity() {

    @Inject lateinit var firebaseAuthSecurityGate: FirebaseAuthSecurityGate
    @Inject lateinit var employeeLifecycleMonitor: FirebaseEmployeeLifecycleMonitor
    @Inject lateinit var sessionStore: MobileSessionStore


    companion object {
        @Volatile
        private var isProcessAuthorized = false

        @Volatile
        private var isLockingInProgress = false

        private const val AUTH_PREFS = "auth_prefs"
        private const val SESSION_PREFS = "mobile_session"

        fun markAsVerified(context: Context) {
            val app = context.applicationContext

            isProcessAuthorized = true
            isLockingInProgress = false

            app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putBoolean("is_locked", false)
                    putLong("last_active_time", System.currentTimeMillis())
                }

            Log.i(
                "SecurityBase",
                "Session authorized for current process"
            )
        }

        fun isProcessVerified(): Boolean = isProcessAuthorized

        fun clearProcessAuthorization(context: Context) {
            isProcessAuthorized = false
            isLockingInProgress = false

            context.applicationContext
                .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putBoolean("is_locked", false)
                }

            Log.i(
                "SecurityBase",
                "Process authorization cleared because the user explicitly logged out"
            )
        }
    }

    /**
     * LoginActivity and ReliabilitySetupActivity intentionally bypass the
     * protected-session redirect because they are entry/setup screens.
     */
    protected open fun isSecurityBypass(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isSecurityBypass()) {
            checkSession()
        }
    }

    override fun onResume() {
        super.onResume()

        if (!isSecurityBypass()) {
            checkSession()
            startEmployeeLifecycleMonitorIfNeeded()
        }
    }

    override fun onPause() {
        employeeLifecycleMonitor.stop()
        super.onPause()
    }

    private fun startEmployeeLifecycleMonitorIfNeeded() {
        val role = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
            .getString("user_role", "").orEmpty()
        if (role != UserRole.STAFF.name || !sessionStore.isLoggedIn()) return

        employeeLifecycleMonitor.start(lifecycleScope) { reason ->
            runOnUiThread {
                if (isFinishing || isDestroyed || isSecurityBypass()) return@runOnUiThread
                sessionStore.clearLogin()
                getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).edit(commit = true) { clear() }
                getSharedPreferences("user_prefs", MODE_PRIVATE).edit(commit = true) { clear() }
                clearProcessAuthorization(applicationContext)
                runCatching { FirebaseAuth.getInstance().signOut() }
                android.widget.Toast.makeText(this, reason.ifBlank { "Employee session is no longer valid." }, android.widget.Toast.LENGTH_LONG).show()
                redirectToLogin()
            }
        }
    }

    /**
     * Do NOT use an in-memory "cold start" flag as the reason to redirect.
     *
     * Android can recreate an Activity/process at any time. The persisted
     * MobileSessionStore token is the authoritative login state.
     */
    private fun checkSession(): Boolean {
        if (this is LoginActivity) {
            return false
        }

        val sessionPrefs =
            applicationContext.getSharedPreferences(
                SESSION_PREFS,
                Context.MODE_PRIVATE
            )

        val token =
            sessionPrefs.getString("token", null)

        val hasToken = !token.isNullOrBlank()

        if (hasToken) {
            // A persisted authenticated mobile session survives:
            // - process recreation
            // - closing from Recents
            // - returning from another Activity
            //
            // Never redirect to LoginActivity merely because the static
            // process flag was reset.
            if (!isProcessAuthorized) {
                Log.i(
                    "SecurityBase",
                    "Restoring authorized state from persisted mobile session: ${javaClass.simpleName}"
                )
                markAsVerified(applicationContext)
            }

            return false
        }

        // Do not manufacture a Login/Security screen from the base activity.
        //
        // A protected Activity can be recreated while Android is restoring the
        // task, and an auth check here can race the session store during that
        // restoration. That race was the source of the repeated security-login
        // loop. LauncherActivity and each entry screen are responsible for
        // deciding where an unauthenticated user should go.
        //
        // IMPORTANT: this does not clear the session and does not log the user
        // out. Explicit logout remains the only normal path that clears it.
        Log.w(
            "SecurityBase",
            "No persisted mobile session for ${javaClass.simpleName}; leaving navigation to the entry screen"
        )
        return false
    }

    private fun redirectToLogin() {
        if (isLockingInProgress || isFinishing || isDestroyed) {
            return
        }

        isLockingInProgress = true

        val intent = Intent(this, LoginActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)
        finish()
    }
}
