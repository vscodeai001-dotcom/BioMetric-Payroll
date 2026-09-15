package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.MobileLoginRequest
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import com.biometric.app.data.repository.AuthRepository
import com.biometric.app.databinding.ActivityLoginBinding
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.sync.ThemePreferenceSync
import com.biometric.app.sync.RealtimeUiDispatcher
import com.biometric.app.util.MotionManager
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var mobileSessionStore: MobileSessionStore
    @Inject lateinit var themePreferenceSync: ThemePreferenceSync
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var adminRealtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var realtimeUiDispatcher: RealtimeUiDispatcher

    private lateinit var biometricAuthManager: BiometricAuthManager

    override fun isSecurityBypass(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        biometricAuthManager = BiometricAuthManager(
            this, this,
            onAuthSuccess = {
                proceedToMain()
            },
            onAuthError = { error ->
                if (!error.contains("cancel", true)) {
                    Toast.makeText(this, "Biometric failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )

        setupUI()
        setupListeners()
        
        // Auto-trigger biometric if already logged in but app is locked
        if (mobileSessionStore.isLoggedIn()) {
            lifecycleScope.launch {
                delay(300) 
                if (!isFinishing && !isDestroyed) {
                    if (biometricAuthManager.canAuthenticate()) {
                        biometricAuthManager.authenticate()
                    } else {
                        proceedToMain()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnLoginAction.setOnClickListener {
            handleEmailPasswordLogin()
        }
        binding.btnFingerprint.setOnClickListener {
            if (biometricAuthManager.canAuthenticate()) {
                biometricAuthManager.authenticate()
            }
        }
        binding.btnReset.setOnClickListener {
            // Allow user to logout and switch accounts if they want
            // This button is an explicit account switch/sign-out action.
            mobileSessionStore.clearLogin()
            SecurityBaseActivity.clearProcessAuthorization(applicationContext)
            applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
                .edit(commit = true) { clear() }
            applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
                .edit(commit = true) { clear() }
            setupUI()
        }
    }

    private fun handleEmailPasswordLogin() {
        val email = binding.etUserId.text.toString().trim().lowercase()
        val password = binding.etDynamic.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(this, "Please enter your Email Address", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isEmpty()) {
            Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        handleEmployeeLogin(email, password)
    }

    private fun setupUI() {
        MotionManager.applyTouchScale(binding.btnLoginAction)
        MotionManager.applyTouchScale(binding.btnFingerprint)
        
        applyWindowInsets(binding.main)

        val loggedIn = mobileSessionStore.isLoggedIn()

        // VISIBILITY RULES:
        // If logged in -> show "Unlock" mode. If not -> show "Login" mode.
        binding.tilUserId.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.tilDynamic.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnLoginAction.visibility = if (loggedIn) View.GONE else View.VISIBLE
        
        binding.llBiometricLogin.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.btnReset.visibility = if (loggedIn) View.VISIBLE else View.GONE
        
        if (loggedIn) {
            binding.tvWelcome.text = "Unlock BioMetric 🔓"
            binding.btnReset.text = "Switch Account / Sign Out"
        } else {
            binding.tvWelcome.text = "Welcome Back! 👋"
            binding.tilUserId.hint = "Email Address"
            binding.etUserId.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            
            binding.tilDynamic.hint = "Password"
            binding.etDynamic.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            
            binding.btnLoginAction.text = "Log in"
        }
    }

    private fun handleEmployeeLogin(email: String, pass: String, forceReplace: Boolean = false) {
        lifecycleScope.launch {
            try {
                setLoading(true)
                val deviceId = getAndroidDeviceId()
                val response = mobileApi.login(
                    MobileLoginRequest(
                        email = email,
                        password = pass,
                        deviceId = deviceId,
                        forceReplace = forceReplace
                    )
                )

                if (response.isSuccessful) {
                    val result = response.body()
                    if (result?.success == true) {
                        val token = result.token
                        if (token.isNullOrBlank()) {
                            setLoading(false)
                            Toast.makeText(this@LoginActivity, "Login succeeded but no mobile session was issued.", Toast.LENGTH_LONG).show()
                            return@launch
                        }

                        // Normalize role to our enum values
                        val rawRole = result.role ?: ""
                        val role = when {
                            rawRole.contains("Super", true) -> UserRole.SUPER_ADMIN.name
                            rawRole.contains("Admin", true) -> UserRole.ADMIN.name
                            rawRole.contains("Employee", true) -> UserRole.STAFF.name
                            result.name.contains("Admin", true) -> UserRole.ADMIN.name
                            else -> UserRole.STAFF.name
                        }
                        
                        mobileSessionStore.saveLogin(token, result.employeeId, result.name, result.email, result.firebaseOwnerUid)

                        // Authenticate the existing Firebase realtime layer with
                        // a server-issued custom token. Firebase then maintains
                        // its own session independently of Render, so GPS and
                        // realtime listeners do not depend on the Payroll.Web
                        // process staying alive. If Firebase is temporarily
                        // unavailable, the existing login flow remains intact.
                        result.firebaseToken?.takeIf { it.isNotBlank() }?.let { customToken ->
                            try {
                                FirebaseAuth.getInstance()
                                    .signInWithCustomToken(customToken)
                                    .await()
                            } catch (firebaseEx: Exception) {
                                Log.w("LoginActivity", "Firebase realtime authentication deferred: ${firebaseEx.message}")
                            }
                        }

                        // Server preference is authoritative across devices. Refresh it before entering the app.
                        themePreferenceSync.refreshFromServer()
                        adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                        applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("is_logged_in", true)
                            putString("user_role", role)
                            putInt("employee_id", result.employeeId)
                            putString("employee_name", result.name)
                        }
                        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("has_logged_in_before", true)
                            putBoolean("is_locked", false)
                            putString("user_role", role)
                            putString("user_uid", if (result.employeeId != 0) result.employeeId.toString() else result.email)
                            putInt("employee_id", result.employeeId)
                            putString("employee_name", result.name)
                            putLong("last_active_time", System.currentTimeMillis())
                        }
                        proceedToMain()
                    } else {
                        setLoading(false)
                        val msg = result?.message ?: "Invalid ID or Password"
                        Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    }
                } else if (response.code() == 409) {
                    setLoading(false)
                    AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Employee already logged in")
                        .setMessage("This employee account is already active on another device. Replace that active session with this device?")
                        .setPositiveButton("Replace & Login") { _, _ ->
                            handleEmployeeLogin(email, pass, forceReplace = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    setLoading(false)
                    val errorBody = response.errorBody()?.string()
                    val msg = try {
                        val json = JSONObject(errorBody ?: "{}")
                        json.optString("message", json.optString("Message", "Invalid ID or Password"))
                    } catch (_: Exception) {
                        "Invalid ID or Password"
                    }
                    Log.e("Login", "Login failed: $errorBody (code: ${response.code()})")
                    
                    val roleFromHeader = response.headers()["X-User-Role"] ?: ""
                    val isAdminFromHeader = roleFromHeader.contains("Admin", true)

                    if (msg.contains("not registered", true) || msg.contains("not linked", true)) {
                         // If we can't tell role from header (which we probably can't on 401), we might need another way.
                         // But the web app just lets them in. 
                         // Let's assume if the msg says Identity verified but no payroll, and it's an admin, we want to let them in.
                         // However, the mobile login API I just modified should now return 200 for admins.
                         // So this block might not even be hit for admins anymore.
                        MaterialAlertDialogBuilder(this@LoginActivity)
                            .setTitle("Payroll Link Missing ⚠️")
                            .setMessage("Your account exists, but it isn't linked to an active Employee record in the payroll database.\n\nPlease ask your Admin to link $email to an Employee in the 'User Management' web portal.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(this@LoginActivity, "Unable to reach BioMetric server: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun proceedToMain() {
        // Break the loop: Mark process as verified and clear lock state
        // The successful credential/biometric flow is the explicit authorization
        // event for this process. Do this before launching the protected destination.
        SecurityBaseActivity.markAsVerified(applicationContext)

        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
            putLong("last_active_time", System.currentTimeMillis())
        }

        val role = applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .getString("user_role", UserRole.STAFF.name)
        
        val employeeId = applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).getInt("employee_id", 0)
        
        // If they are an Admin or SuperAdmin, always go to MainActivity (Admin Dashboard)
        // If they are STAFF, go to EmployeeHomeActivity
        val destination = if (role == UserRole.ADMIN.name || role == UserRole.SUPER_ADMIN.name) {
            MainActivity::class.java
        } else {
            EmployeeHomeActivity::class.java
        }

        if (mobileSessionStore.isReliabilitySetupDone()) {
            startActivity(Intent(this, destination).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                if (destination == EmployeeHomeActivity::class.java) {
                    putExtra("JUST_LOGGED_IN", true)
                }
            })
        } else {
            startActivity(Intent(this, ReliabilitySetupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("JUST_LOGGED_IN", true)
            })
        }
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLoginAction.isEnabled = !loading
    }

    private fun getAndroidDeviceId() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
}
