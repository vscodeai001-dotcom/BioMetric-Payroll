package com.biometric.app.ui

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

        val authPrefs = applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
        val isLoggedIn = sessionStore.isLoggedIn()

        if (isLoggedIn) {
            // A persisted mobile bearer session is the authoritative login state.
            // Do not send a cold process back through LoginActivity. That was the
            // source of the repeated security/unlock screen after closing the app.
            SecurityBaseActivity.markAsVerified(applicationContext)

            val role = authPrefs.getString("user_role", UserRole.STAFF.name)
            val destination = if (
                role == UserRole.ADMIN.name || role == UserRole.SUPER_ADMIN.name
            ) {
                MainActivity::class.java
            } else {
                EmployeeHomeActivity::class.java
            }

            if (role == UserRole.STAFF.name) {
                sharedViewModel.warmUpDashboard()
            }
            startActivity(Intent(this, destination))
        } else {
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
