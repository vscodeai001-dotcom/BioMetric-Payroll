package com.biometric.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.ui.viewmodel.SharedViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {

    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var sessionStore: MobileSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLoggedIn = sessionStore.isLoggedIn()
        val authPrefs = applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
        
        // Sessions are indefinite. We only force an unlock check if the process just started.
        val needsProcessVerification = !SecurityBaseActivity.isProcessVerified()

        val role = authPrefs.getString("user_role", UserRole.STAFF.name)
        val destination = if (role == UserRole.STAFF.name) EmployeeHomeActivity::class.java else MainActivity::class.java

        if (isLoggedIn && !needsProcessVerification) {
            // Already logged in and process is verified (e.g. Activity recreation or simple rotation)
            if (sessionStore.isReliabilitySetupDone()) {
                sharedViewModel.warmUpDashboard()
                startActivity(Intent(this, destination))
            } else {
                startActivity(Intent(this, ReliabilitySetupActivity::class.java))
            }
        } else if (isLoggedIn) {
            // Logged in but needs security unlock (cold start)
            startActivity(Intent(this, LoginActivity::class.java))
        } else {
            // Never logged in
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
