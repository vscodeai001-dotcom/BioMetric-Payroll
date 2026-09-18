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
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : MotionBaseActivity() {
    private val superAdminEmail = "prakashshiva368@gmail.com"

    private lateinit var binding: ActivityLoginBinding

    @Inject lateinit var mobileSessionStore: MobileSessionStore
    @Inject lateinit var themePreferenceSync: ThemePreferenceSync
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var adminRealtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var realtimeUiDispatcher: RealtimeUiDispatcher
    @Inject lateinit var firebaseEmployeeSessionManager: com.biometric.app.sync.FirebaseEmployeeSessionManager
    @Inject lateinit var firebaseEmployeeProvisioningVerifier: com.biometric.app.sync.FirebaseEmployeeProvisioningVerifier

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
            lifecycleScope.launch {
                runCatching { firebaseEmployeeSessionManager.release() }
                runCatching { FirebaseAuth.getInstance().signOut() }
                mobileSessionStore.clearLogin()
                SecurityBaseActivity.clearProcessAuthorization(applicationContext)
                applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
                    .edit(commit = true) { clear() }
                applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
                    .edit(commit = true) { clear() }
                setupUI()
            }
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

        // Verify Firebase session status matches local storage.
        // If Firebase session is lost but app thinks it is logged in,
        // we must reset the local session to prevent "session missing" errors.
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val loggedIn = mobileSessionStore.isLoggedIn() && firebaseUser != null
        
        if (mobileSessionStore.isLoggedIn() && firebaseUser == null) {
            mobileSessionStore.clearLogin()
            clearProcessAuthorization(applicationContext)
            applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
                .edit(commit = true) { clear() }
            // Recursively call setupUI to refresh state with loggedIn=false
            setupUI()
            return
        }

        // VISIBILITY RULES:
        // If logged in -> show "Unlock" mode. If not -> show "Login" mode.
        binding.tilUserId.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.tilDynamic.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnLoginAction.visibility = if (loggedIn) View.GONE else View.VISIBLE
        
        binding.llBiometricLogin.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.btnReset.visibility = if (loggedIn) View.VISIBLE else View.GONE
        
        if (loggedIn) {
            binding.tvWelcome.text = "Unlock BioMetric 🔒 🔓"
            binding.btnReset.text = "Switch Account / Sign Out 👤"
        } else {
            binding.tvWelcome.text = "Welcome Back! 👋 ✨"
            binding.tilUserId.hint = "Email Address 📧"
            binding.etUserId.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            
            binding.tilDynamic.hint = "Password 🔑"
            binding.etDynamic.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            
            binding.btnLoginAction.text = "Log in 🚀"
        }
    }

    private fun handleEmployeeLogin(
        email: String,
        pass: String,
        forceReplace: Boolean = false
    ) {
        lifecycleScope.launch {
            binding.tilUserId.error = null
            binding.tilDynamic.error = null
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

                val userMessage = when (code) {
                    "ERROR_INVALID_EMAIL" -> "Please enter a valid Firebase email address."
                    "ERROR_USER_NOT_FOUND" -> "Firebase account was not found. Ask Admin to provision this account."
                    "ERROR_WRONG_PASSWORD" -> "Firebase password is incorrect."
                    "ERROR_USER_DISABLED" -> "This Firebase account is disabled."
                    "ERROR_TOO_MANY_REQUESTS" -> "Too many login attempts. Please wait and try again."
                    "ERROR_NETWORK_REQUEST_FAILED" -> "Network connection failed. Check internet and try again."
                    else -> "Firebase login failed: $code"
                }
                binding.tilDynamic.error = userMessage

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

                Toast.makeText(
                    this@LoginActivity,
                    "Firebase login failed: $code. Employee accounts must be provisioned in Firebase Authentication.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // IMPORTANT:
            // firebaseUser is guaranteed non-null here because the null path
            // above always returns.
            // Firebase Authentication succeeds before custom claims are necessarily
            // available. The Web provisioning worker stamps Employee claims for
            // existing Firebase Console-created users. Give that one-time
            // provisioning a short window, then force-refresh the ID token.
            var tokenResult = runCatching {
                firebaseUser.getIdToken(true).await()
            }.getOrNull()

            var claims = tokenResult?.claims.orEmpty()
            repeat(4) { attempt ->
                val hasApplicationRole = !claims["role"]?.toString().isNullOrBlank()
                val hasEmployeeId = (claims["employee_id"]?.toString()?.toIntOrNull() ?: 0) > 0
                val isKnownEmployeeEmail = !firebaseUser.email.isNullOrBlank() &&
                    !firebaseUser.email.equals(superAdminEmail, ignoreCase = true)

                if (hasApplicationRole && (!isKnownEmployeeEmail || hasEmployeeId)) return@repeat
                if (attempt == 3) return@repeat

                delay(1500L)
                tokenResult = runCatching {
                    firebaseUser.getIdToken(true).await()
                }.getOrNull()
                claims = tokenResult?.claims.orEmpty()
            }

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

                rawRole.equals("Employee", ignoreCase = true) ||
                        rawRole.equals("Staff", ignoreCase = true) ||
                        rawRole.equals(UserRole.STAFF.name, ignoreCase = true) ->
                    UserRole.STAFF.name

                else ->
                    "UNKNOWN"
            }

            if (role == "UNKNOWN") {
                setLoading(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Firebase account has no supported application role. Please ask Admin to provision the account.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
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
                val firebaseEmployeeId = when (val value = claims["employee_id"]) {
                    is Number -> value.toInt()
                    else -> value?.toString()?.toIntOrNull() ?: 0
                }

                // Employee authentication is Firebase-only. The account must
                // carry the canonical claims written by Web provisioning: role,
                // employee_id and owner_uid. Never guess an owner or fall back to
                // Payroll.Web because doing so can point the app at the wrong tenant.
                if (firebaseEmployeeId <= 0 || firebaseOwnerUid.isNullOrBlank()) {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "Employee Firebase profile is not provisioned yet. Please ask Admin to provision this employee, then try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val provisioning = firebaseEmployeeProvisioningVerifier.verify(
                    employeeId = firebaseEmployeeId,
                    ownerUid = firebaseOwnerUid
                )
                if (!provisioning.valid) {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        provisioning.message,
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

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

                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        sessionResult.message.ifBlank { "Employee session could not be created. Please try again." },
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Employee authentication has been fully handled above.
                // Do not return here for Admin/SuperAdmin: they must continue
                // through the shared Firebase session initialization below.

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
                ?: if (isCanonicalSuperAdmin) "biometricpayroll" else ""

            if (ownerUid.isBlank()) {
                setLoading(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Firebase account is missing owner_uid provisioning. Please ask Admin to synchronize this account, then sign in again.",
                    Toast.LENGTH_LONG
                ).show()
                Log.e(
                    "LoginActivity",
                    "Authenticated Firebase user ${firebaseUser.uid} has no owner_uid claim. email=${firebaseUser.email}"
                )
                runCatching { FirebaseAuth.getInstance().signOut() }
                return@launch
            }

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

            setLoading(false)

            // SuperAdmin/Admin -> MainActivity
            // Employee -> EmployeeHomeActivity
            proceedToMain()
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
            
            // Admin sessions must never inherit an Employee GPS service.
            stopService(Intent(this, com.biometric.app.domain.location.TrackingService::class.java))
            getSharedPreferences("tracking_prefs", MODE_PRIVATE).edit {
                putBoolean("is_service_active_intended", false)
                putBoolean("tracking_waiting_for_shift", false)
            }
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
