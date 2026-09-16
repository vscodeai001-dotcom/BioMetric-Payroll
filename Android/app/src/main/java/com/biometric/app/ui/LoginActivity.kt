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
import com.biometric.app.BuildConfig
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
    private val superAdminEmail = "prakashshiva368@gmail.com"

    private lateinit var binding: ActivityLoginBinding

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var mobileSessionStore: MobileSessionStore
    @Inject lateinit var themePreferenceSync: ThemePreferenceSync
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var adminRealtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var realtimeUiDispatcher: RealtimeUiDispatcher
    @Inject lateinit var firebaseEmployeeSessionManager: com.biometric.app.sync.FirebaseEmployeeSessionManager

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
        // Do not trim passwords. Firebase credentials must be passed exactly as entered.
        val password = binding.etDynamic.text.toString()

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

    private fun handleEmployeeLogin(
        email: String,
        pass: String,
        forceReplace: Boolean = false
    ) {
        lifecycleScope.launch {
            setLoading(true)

            // Firebase is the primary Android authentication path.
            // Admin/SuperAdmin authenticate directly with Firebase and do not
            // depend on Payroll.Web.
            val firebaseResult = runCatching {
                FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(email, pass)
                    .await()
                    .user
            }

            val firebaseUser = firebaseResult.getOrNull()

            // Firebase authentication failed.
            if (firebaseUser == null) {
                val firebaseError = firebaseResult.exceptionOrNull()

                val code = when (firebaseError) {
                    is com.google.firebase.auth.FirebaseAuthException ->
                        firebaseError.errorCode ?: "AUTH_FAILED"

                    else ->
                        "AUTH_FAILED"
                }

                Log.e(
                    "LoginActivity",
                    "Firebase authentication failed for $email. code=$code",
                    firebaseError
                )

                setLoading(false)

                // Canonical SuperAdmin is Firebase-only.
                // Never fall back to Employee/Web compatibility login.
                if (email.equals(superAdminEmail, ignoreCase = true)) {
                    // A failed canonical SuperAdmin Firebase login must never
                    // reuse an old Employee/session state.
                    mobileSessionStore.clearLogin()
                    SecurityBaseActivity.clearProcessAuthorization(applicationContext)
                    applicationContext
                        .getSharedPreferences("auth_prefs", MODE_PRIVATE)
                        .edit(commit = true) { clear() }
                    applicationContext
                        .getSharedPreferences("user_prefs", MODE_PRIVATE)
                        .edit(commit = true) { clear() }

                    Toast.makeText(
                        this@LoginActivity,
                        "SuperAdmin Firebase login failed: $code. The Firebase Auth user ${superAdminEmail} must exist and its Firebase password must match the configured SuperAdmin password.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@launch
                }

                // Existing Employee compatibility login remains unchanged.
                handleEmployeeLoginViaExistingFlow(
                    email,
                    pass,
                    forceReplace
                )

                return@launch
            }

            // IMPORTANT:
            // firebaseUser is guaranteed non-null here because the null path
            // above always returns.
            val tokenResult = runCatching {
                firebaseUser
                    .getIdToken(true)
                    .await()
            }.getOrNull()

            val claims = tokenResult?.claims.orEmpty()

            val rawRole = claims["role"]?.toString().orEmpty()

            // The canonical SuperAdmin account is always SuperAdmin.
            // This also allows the account to continue into the SuperAdmin
            // application even if claims have not refreshed yet.
            val isCanonicalSuperAdmin =
                firebaseUser.email?.equals(
                    superAdminEmail,
                    ignoreCase = true
                ) == true

            val role = when {
                isCanonicalSuperAdmin ||
                        rawRole.equals("SuperAdmin", ignoreCase = true) ||
                        rawRole.equals(UserRole.SUPER_ADMIN.name, ignoreCase = true) ->
                    UserRole.SUPER_ADMIN.name

                rawRole.equals("Admin", ignoreCase = true) ||
                        rawRole.equals(UserRole.ADMIN.name, ignoreCase = true) ->
                    UserRole.ADMIN.name

                else ->
                    UserRole.STAFF.name
            }

            // Employee authentication is Firebase-native. The existing Web API
            // is used only as a compatibility session bridge after Firebase has
            // already verified the password. This preserves every existing
            // Employee screen/API and the established single-device rule while
            // removing the password-dependent legacy login/provisioning path.
            if (role == UserRole.STAFF.name) {
                if (tokenResult?.token.isNullOrBlank()) {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "Firebase login succeeded but no session token was returned.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val firebaseOwnerUid = claims["owner_uid"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "biometricpayroll"
                val firebaseEmployeeId = when (val value = claims["employee_id"]) {
                    is Number -> value.toInt()
                    else -> value?.toString()?.toIntOrNull() ?: 0
                }

                // Once the employee's Firebase account has been provisioned with
                // the canonical claims, establish the single-device session
                // directly in Firebase. No Web login/session bridge is needed.
                if (firebaseEmployeeId > 0) {
                    val sessionResult = firebaseEmployeeSessionManager.acquire(
                        employeeId = firebaseEmployeeId,
                        ownerUid = firebaseOwnerUid,
                        forceReplace = forceReplace
                    )

                    if (sessionResult.success) {
                        val displayName = firebaseUser.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: email.substringBefore("@")
                        val firebaseToken = tokenResult.token!!

                        mobileSessionStore.saveLogin(
                            token = firebaseToken,
                            employeeId = firebaseEmployeeId,
                            name = displayName,
                            email = firebaseUser.email ?: email,
                            firebaseOwnerUid = firebaseOwnerUid
                        )

                        applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("is_logged_in", true)
                            putString("user_role", UserRole.STAFF.name)
                            putInt("employee_id", firebaseEmployeeId)
                            putString("employee_name", displayName)
                            putString("user_uid", firebaseUser.uid)
                        }
                        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("has_logged_in_before", true)
                            putBoolean("is_locked", false)
                            putString("user_role", UserRole.STAFF.name)
                            putString("user_uid", firebaseUser.uid)
                            putInt("employee_id", firebaseEmployeeId)
                            putString("employee_name", displayName)
                            putLong("last_active_time", System.currentTimeMillis())
                        }

                        adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                        setLoading(false)
                        proceedToMain()
                        return@launch
                    }

                    if (sessionResult.conflict) {
                        setLoading(false)
                        AlertDialog.Builder(this@LoginActivity)
                            .setTitle("Employee already logged in")
                            .setMessage("This employee account is already active on another device. Replace that active session with this device?")
                            .setPositiveButton("Replace & Login") { _, _ ->
                                handleEmployeeLogin(email, pass, forceReplace = true)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@launch
                    }
                }

                // Firebase account is valid but its employee claims have not
                // been provisioned yet. Keep the established compatibility
                // bridge as a one-time migration path. Normal provisioned
                // Employee logins never reach this branch.
                handleFirebaseEmployeeSession(
                    email = email,
                    firebaseIdToken = tokenResult.token!!,
                    forceReplace = forceReplace
                )
                return@launch
            }

            // Admin/SuperAdmin require a valid Firebase ID token.
            if (tokenResult?.token.isNullOrBlank()) {
                setLoading(false)

                Toast.makeText(
                    this@LoginActivity,
                    "Firebase login succeeded but no session token was returned.",
                    Toast.LENGTH_LONG
                ).show()

                return@launch
            }

            val ownerUid = claims["owner_uid"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: firebaseUser.uid

            val employeeId = when (val value = claims["employee_id"]) {
                is Number ->
                    value.toInt()

                else ->
                    value?.toString()?.toIntOrNull() ?: 0
            }

            val displayName = firebaseUser.displayName
                ?.takeIf { it.isNotBlank() }
                ?: email.substringBefore("@")

            val firebaseToken = tokenResult!!.token!!

            // Save Android session.
            mobileSessionStore.saveLogin(
                token = firebaseToken,
                employeeId = employeeId,
                name = displayName,
                email = firebaseUser.email ?: email,
                firebaseOwnerUid = ownerUid
            )

            // Save application user state.
            applicationContext
                .getSharedPreferences("user_prefs", MODE_PRIVATE)
                .edit(commit = true) {
                    putBoolean("is_logged_in", true)
                    putString("user_role", role)
                    putInt("employee_id", employeeId)
                    putString("employee_name", displayName)
                    putString("user_uid", firebaseUser.uid)
                }

            // Save authentication state.
            applicationContext
                .getSharedPreferences("auth_prefs", MODE_PRIVATE)
                .edit(commit = true) {
                    putBoolean("has_logged_in_before", true)
                    putBoolean("is_locked", false)
                    putString("user_role", role)
                    putString("user_uid", firebaseUser.uid)
                    putInt("employee_id", employeeId)
                    putString("employee_name", displayName)
                    putLong("last_active_time", System.currentTimeMillis())
                }

            // Start Firebase realtime synchronization directly.
            // Android does not need Payroll.Web running for this.
            adminRealtimeCoordinator.start {
                realtimeUiDispatcher.refreshVisible()
            }

            setLoading(false)

            // SuperAdmin/Admin -> MainActivity
            // Employee -> EmployeeHomeActivity
            proceedToMain()
        }
    }

    private fun handleFirebaseEmployeeSession(
        email: String,
        firebaseIdToken: String,
        forceReplace: Boolean
    ) {
        lifecycleScope.launch {
            try {
                setLoading(true)
                val deviceId = getAndroidDeviceId()
                val response = mobileApi.firebaseSession(
                    com.biometric.app.api.FirebaseSessionRequest(
                        idToken = firebaseIdToken,
                        deviceId = deviceId,
                        forceReplace = forceReplace
                    )
                )

                if (response.isSuccessful) {
                    val result = response.body()
                    if (result?.success != true || result.token.isNullOrBlank()) {
                        setLoading(false)
                        Toast.makeText(
                            this@LoginActivity,
                            result?.message ?: "Unable to create employee session.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    val role = UserRole.STAFF.name
                    val displayName = result.name.ifBlank { email.substringBefore("@") }

                    // Keep the established mobile session token so every existing
                    // Employee screen and business API continues unchanged.
                    FirebaseAuth.getInstance().currentUser?.let { firebaseUser ->
                        mobileSessionStore.saveLogin(
                            token = result.token!!,
                            employeeId = result.employeeId,
                            name = displayName,
                            email = firebaseUser.email ?: result.email.ifBlank { email },
                            firebaseOwnerUid = result.firebaseOwnerUid
                        )
                    } ?: mobileSessionStore.saveLogin(
                        token = result.token!!,
                        employeeId = result.employeeId,
                        name = displayName,
                        email = result.email.ifBlank { email },
                        firebaseOwnerUid = result.firebaseOwnerUid
                    )

                    applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE).edit(commit = true) {
                        putBoolean("is_logged_in", true)
                        putString("user_role", role)
                        putInt("employee_id", result.employeeId)
                        putString("employee_name", displayName)
                        putString("user_uid", FirebaseAuth.getInstance().currentUser?.uid ?: email)
                    }

                    applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
                        putBoolean("has_logged_in_before", true)
                        putBoolean("is_locked", false)
                        putString("user_role", role)
                        putString("user_uid", FirebaseAuth.getInstance().currentUser?.uid ?: email)
                        putInt("employee_id", result.employeeId)
                        putString("employee_name", displayName)
                        putLong("last_active_time", System.currentTimeMillis())
                    }

                    adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                    setLoading(false)
                    proceedToMain()
                    return@launch
                }

                if (response.code() == 409) {
                    setLoading(false)
                    AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Employee already logged in")
                        .setMessage("This employee account is already active on another device. Replace that active session with this device?")
                        .setPositiveButton("Replace & Login") { _, _ ->
                            handleFirebaseEmployeeSession(email, firebaseIdToken, forceReplace = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@launch
                }

                setLoading(false)
                val errorBody = response.errorBody()?.string()
                val msg = try {
                    val json = JSONObject(errorBody ?: "{}")
                    json.optString("message", json.optString("Message", "Unable to create employee session."))
                } catch (_: Exception) {
                    "Unable to create employee session. HTTP ${response.code()}"
                }
                Log.e("LoginActivity", "Firebase employee session failed: $errorBody (code: ${response.code()})")
                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                setLoading(false)
                Log.e(
                    "LoginActivity",
                    "Firebase employee session bridge failed. baseUrl=${BuildConfig.BIOMETRIC_API_BASE_URL}",
                    e
                )
                Toast.makeText(
                    this@LoginActivity,
                    "Firebase login succeeded, but the Employee session service is unreachable. API: ${BuildConfig.BIOMETRIC_API_BASE_URL}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleEmployeeLoginViaExistingFlow(
        email: String,
        pass: String,
        forceReplace: Boolean
    ) {
        lifecycleScope.launch {
            try {
                Log.i(
                    "LoginActivity",
                    "Employee compatibility login via ${BuildConfig.BIOMETRIC_API_BASE_URL}"
                )
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
                            Toast.makeText(
                                this@LoginActivity,
                                "Login succeeded but no mobile session was issued.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        val displayName = result.name
                        val role = UserRole.STAFF.name

                        // The compatibility endpoint remains the authoritative
                        // single-device/session gate for Employees. Its successful
                        // response also carries a Firebase custom token, so the
                        // authenticated Android session is Firebase-backed after
                        // the lock is acquired.
                        mobileSessionStore.saveLogin(
                            token,
                            result.employeeId,
                            displayName,
                            result.email,
                            result.firebaseOwnerUid
                        )

                        result.firebaseToken?.takeIf { it.isNotBlank() }?.let { customToken ->
                            runCatching {
                                FirebaseAuth.getInstance()
                                    .signInWithCustomToken(customToken)
                                    .await()
                            }.onFailure {
                                Log.w(
                                    "LoginActivity",
                                    "Employee Firebase realtime authentication deferred: ${it.message}"
                                )
                            }
                        }

                        applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("is_logged_in", true)
                            putString("user_role", role)
                            putInt("employee_id", result.employeeId)
                            putString("employee_name", displayName)
                            putString("user_uid", result.email)
                        }

                        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit(commit = true) {
                            putBoolean("has_logged_in_before", true)
                            putBoolean("is_locked", false)
                            putString("user_role", role)
                            putString("user_uid", result.email)
                            putInt("employee_id", result.employeeId)
                            putString("employee_name", displayName)
                            putLong("last_active_time", System.currentTimeMillis())
                        }

                        adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                        setLoading(false)
                        proceedToMain()
                    } else {
                        setLoading(false)
                        Toast.makeText(
                            this@LoginActivity,
                            result?.message ?: "Invalid ID or Password",
                            Toast.LENGTH_LONG
                        ).show()
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
                    Log.e("Login", "Employee login failed: $errorBody (code: ${response.code()})")
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Employee login service is unreachable. Check the Android API URL/network.",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("LoginActivity", "Employee compatibility login failed", e)
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
