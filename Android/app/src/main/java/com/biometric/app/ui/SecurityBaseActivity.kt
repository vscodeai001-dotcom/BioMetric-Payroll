package com.biometric.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

/**
 * Base activity providing session protection.
 * Sessions in BioMetric are indefinite; we only lock on cold starts (process restart)
 * or when explicitly signalled by the server.
 */
abstract class SecurityBaseActivity : AppCompatActivity() {

    companion object {
        @Volatile
        private var isProcessAuthorized = false

        @Volatile
        private var isLockingInProgress = false
        
        fun markAsVerified(context: Context) {
            Log.i("SecurityBase", "markAsVerified called. Process is now authorized.")
            isProcessAuthorized = true
            isLockingInProgress = false
            
            // Clear persistent lock state
            context.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
                putBoolean("is_locked", false)
            }
        }

        fun isProcessVerified() = isProcessAuthorized
    }

    protected open fun isSecurityBypass(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (isSecurityBypass()) {
            return
        }

        checkSession()
    }

    override fun onResume() {
        super.onResume()
        if (isSecurityBypass()) {
            return
        }
        
        checkSession()
    }

    private fun checkSession(): Boolean {
        // Break the loop: If we are already in LoginActivity, don't try to lock again
        if (this is LoginActivity) return false

        val sharedPrefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
        val isLocked = sharedPrefs.getBoolean("is_locked", false)
        
        Log.d("SecurityBase", "checkSession: Activity=${this.javaClass.simpleName}, authorized=$isProcessAuthorized, locked=$isLocked")

        if (!isProcessAuthorized || isLocked) {
            lockApp()
            return true
        }

        return false
    }

    private fun lockApp() {
        if (isLockingInProgress || isFinishing || isDestroyed) return
        isLockingInProgress = true

        val sharedPrefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
        sharedPrefs.edit(commit = true) {
            putBoolean("is_locked", true)
        }
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
