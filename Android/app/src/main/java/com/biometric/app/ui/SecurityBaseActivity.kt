package com.biometric.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
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

        // No persisted session means there is no authenticated user.
        // Only then return to the real login screen.
        Log.w(
            "SecurityBase",
            "No persisted mobile session for ${javaClass.simpleName}; returning to LoginActivity"
        )

        redirectToLogin()
        return true
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
