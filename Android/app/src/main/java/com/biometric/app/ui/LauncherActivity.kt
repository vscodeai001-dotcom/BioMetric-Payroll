package com.biometric.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.sync.FirebaseAuthSecurityGate
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {

    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var firebaseAuthSecurityGate: FirebaseAuthSecurityGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authPrefs = applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
        val userPrefs = applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
        val isLoggedIn = sessionStore.isLoggedIn()

        lifecycleScope.launch {
            if (!isLoggedIn) {
                openLogin()
                return@launch
            }

            val validation = firebaseAuthSecurityGate.validateCurrentSession()
            if (!validation.allowed) {
                // A persisted local token is not enough after Firebase Auth
                // revocation, role removal, tenant mismatch, or employee session
                // replacement on another device.
                sessionStore.clearLogin()
                authPrefs.edit(commit = true) { clear() }
                userPrefs.edit(commit = true) { clear() }
                SecurityBaseActivity.clearProcessAuthorization(applicationContext)
                runCatching { FirebaseAuth.getInstance().signOut() }
                Toast.makeText(
                    this@LauncherActivity,
                    validation.message.ifBlank { "Your Firebase session is no longer valid. Please sign in again." },
                    Toast.LENGTH_LONG
                ).show()
                openLogin()
                return@launch
            }

            SecurityBaseActivity.markAsVerified(applicationContext)
            authPrefs.edit(commit = true) {
                putString("user_role", validation.role)
                putString("user_uid", FirebaseAuth.getInstance().currentUser?.uid.orEmpty())
                putInt("employee_id", validation.employeeId)
                putLong("last_active_time", System.currentTimeMillis())
            }
            userPrefs.edit(commit = true) {
                putBoolean("is_logged_in", true)
                putString("user_role", validation.role)
                putString("user_uid", FirebaseAuth.getInstance().currentUser?.uid.orEmpty())
                putInt("employee_id", validation.employeeId)
            }

            val destination = if (
                validation.role == UserRole.ADMIN.name || validation.role == UserRole.SUPER_ADMIN.name
            ) MainActivity::class.java else EmployeeHomeActivity::class.java

            if (validation.role == UserRole.STAFF.name) {
                sharedViewModel.warmUpDashboard()
            }

            startActivity(Intent(this@LauncherActivity, destination))
            finish()
        }
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
