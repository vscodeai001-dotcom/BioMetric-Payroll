package com.biometric.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.dao.LocalSettingsDao
import com.biometric.app.data.entity.LocalCompanySettings
import com.biometric.app.data.repository.TrackingConfigurationRepository
import com.biometric.app.databinding.ActivityCompanySettingsBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class CompanySettingsActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityCompanySettingsBinding

    @Inject lateinit var localSettingsDao: LocalSettingsDao
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var trackingConfiguration: TrackingConfigurationRepository
    @Inject lateinit var sharedViewModel: SharedViewModel

    private var localCompany = LocalCompanySettings()
    private var trackingIntervalSeconds = 30
    private var isSuperAdmin = false

    private val salaryCalcMethods = listOf(
        "Days in Month",
        "Fixed 30-Day",
        "Pro-Rata Hourly"
    )

    private val intervalLabels = listOf(
        "30 seconds" to 30,
        "1 minute" to 60,
        "2 minutes" to 120,
        "5 minutes" to 300
    )

    private val backupIntervalLabels = listOf(
        "1 hour" to 1,
        "6 hours" to 6,
        "12 hours" to 12,
        "24 hours (1 Day)" to 24,
        "48 hours (2 Days)" to 48,
        "168 hours (7 Days)" to 168
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clCompanySettingsRoot, binding.appBar)

        setupToolbar()
        setupTabs()
        setupDropdowns()
        setupListeners()
        observeProfile()
        loadInitialData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            "🏢 General & Rules",
            "👑 Admin Credentials",
            "🏛️ Statutory (Tax)",
            "📧 Email Config",
            "🏖️ Leave Rules"
        )
        tabs.forEach { title ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(title))
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                HapticUtil.vibrateClick(binding.tabLayout)
                switchTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun switchTab(position: Int) {
        binding.containerGeneralTab.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.containerAdminTab.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.containerStatutoryTab.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.containerEmailTab.visibility = if (position == 3) View.VISIBLE else View.GONE
        binding.containerLeaveTab.visibility = if (position == 4) View.VISIBLE else View.GONE
    }

    private fun setupDropdowns() {
        val salaryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, salaryCalcMethods)
        binding.spinnerGenSalaryMethod.setAdapter(salaryAdapter)

        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, intervalLabels.map { it.first })
        binding.spinnerGpsInterval.setAdapter(intervalAdapter)

        val backupAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, backupIntervalLabels.map { it.first })
        binding.spinnerBackupInterval.setAdapter(backupAdapter)
    }

    private fun setupListeners() {
        binding.btnTopSave.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveCompanySettings()
        }

        binding.btnBottomSave.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveCompanySettings()
        }

        binding.btnReturnDashboard.setOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        binding.btnGenOpenMaps.setOnClickListener {
            HapticUtil.vibrateClick(it)
            val lat = binding.etGenLatitude.text?.toString()?.toDoubleOrNull() ?: 0.0
            val lng = binding.etGenLongitude.text?.toString()?.toDoubleOrNull() ?: 0.0
            val uri = if (lat != 0.0 || lng != 0.0) {
                Uri.parse("geo:$lat,$lng?q=$lat,$lng(Office)")
            } else {
                Uri.parse("https://www.google.com/maps")
            }
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
                Toast.makeText(this, "Unable to launch map browser", Toast.LENGTH_SHORT).show()
            }
        }

        binding.swPfEsiSystem.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutPfEsiFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.swEmailNotifications.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutEmailFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnDeleteCompanySettings.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showDeleteCompanyDialog()
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            sharedViewModel.userProfile.collectLatest { profile ->
                isSuperAdmin = profile?.isSuperAdmin() == true
                binding.cardDangerZoneSettings.visibility = if (isSuperAdmin) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadInitialData() {
        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            val cs = withContext(Dispatchers.IO) { localSettingsDao.getCompanySettings() }
            if (cs != null) localCompany = cs

            // Load tracking config
            val config = trackingConfiguration.load()
            trackingIntervalSeconds = config.intervalSeconds

            // Populate UI
            populateUi()
            binding.loadingOverlay.visibility = View.GONE

            // Sync latest company and admin details from Firebase
            val owner = firebaseSync.getOwnerRef()
            if (owner != null) {
                owner.child("company_settings").child("1").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            snapshot.child("companyName").getValue(String::class.java)?.let {
                                if (binding.etGenCompanyName.text.isNullOrBlank()) binding.etGenCompanyName.setText(it)
                            }
                            snapshot.child("autoBackupIntervalHours").getValue(Int::class.java)?.let { hours ->
                                if (hours > 0) {
                                    localCompany.autoBackupIntervalHours = hours
                                    val backupItem = backupIntervalLabels.find { it.second == hours } ?: backupIntervalLabels.find { it.second == 24 }
                                    backupItem?.let { binding.spinnerBackupInterval.setText(it.first, false) }
                                }
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })

                // Load Admin Details from Owner root node
                owner.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val email = snapshot.child("adminEmail").getValue(String::class.java)
                        val name = snapshot.child("adminName").getValue(String::class.java)
                        val phone = snapshot.child("adminPhone").getValue(String::class.java)
                        if (!email.isNullOrBlank()) binding.etAdminEmail.setText(email)
                        if (!name.isNullOrBlank()) binding.etAdminName.setText(name)
                        if (!phone.isNullOrBlank()) binding.etAdminPhone.setText(phone)
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
        }
    }

    private fun populateUi() {
        // Tab 1: General & Rules
        binding.etGenCompanyName.setText(localCompany.companyName)
        binding.etGenAddressLine1.setText(localCompany.addressLine1)
        binding.etGenCityStatePincode.setText(localCompany.cityStatePincode)

        val methodIdx = salaryCalcMethods.indexOf(localCompany.salaryCalculationMethod)
        if (methodIdx >= 0) {
            binding.spinnerGenSalaryMethod.setText(salaryCalcMethods[methodIdx], false)
        } else {
            binding.spinnerGenSalaryMethod.setText(salaryCalcMethods[0], false)
        }

        binding.etGenCutoffHour.setText(localCompany.workDayCutoffHour.toString())
        binding.etGenLateGrace.setText(localCompany.lateGraceMinutes.toString())
        binding.etGenEarlyGrace.setText(localCompany.endTimeGraceMinutes.toString())

        // GPS Interval
        val intervalItem = intervalLabels.find { it.second == trackingIntervalSeconds } ?: intervalLabels[0]
        binding.spinnerGpsInterval.setText(intervalItem.first, false)

        // Auto-Backup Interval (Default 24)
        val backupIntervalItem = backupIntervalLabels.find { it.second == localCompany.autoBackupIntervalHours }
            ?: backupIntervalLabels.find { it.second == 24 } ?: backupIntervalLabels[3]
        binding.spinnerBackupInterval.setText(backupIntervalItem.first, false)

        binding.etGenLatitude.setText(if (localCompany.officeLatitude != 0.0) localCompany.officeLatitude.toString() else "")
        binding.etGenLongitude.setText(if (localCompany.officeLongitude != 0.0) localCompany.officeLongitude.toString() else "")
        binding.etGenRadius.setText(localCompany.geoRadiusMeters.toString())

        binding.etZktecoIp.setText(localCompany.zktecoIP.orEmpty())
        binding.etZktecoPort.setText(localCompany.zktecoPort.toString())
        binding.etZktecoMachineNo.setText(localCompany.zktecoMachineNumber.toString())

        // Tab 2: Admin Credentials Pre-fill
        val sessionEmail = sessionStore.userEmail()
        val sessionName = sessionStore.employeeName()
        if (sessionEmail.isNotBlank() && binding.etAdminEmail.text.isNullOrBlank()) {
            binding.etAdminEmail.setText(sessionEmail)
        }
        if (sessionName.isNotBlank() && binding.etAdminName.text.isNullOrBlank()) {
            binding.etAdminName.setText(sessionName)
        }

        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (binding.etAdminEmail.text.isNullOrBlank() && !fbUser?.email.isNullOrBlank()) {
            binding.etAdminEmail.setText(fbUser?.email)
        }
        if (binding.etAdminName.text.isNullOrBlank() && !fbUser?.displayName.isNullOrBlank()) {
            binding.etAdminName.setText(fbUser?.displayName)
        }
        if (binding.etAdminPhone.text.isNullOrBlank() && !fbUser?.phoneNumber.isNullOrBlank()) {
            binding.etAdminPhone.setText(fbUser?.phoneNumber)
        }

        // Tab 3: Statutory
        binding.swPfEsiSystem.isChecked = localCompany.enablePfEsiSystem
        binding.layoutPfEsiFields.visibility = if (localCompany.enablePfEsiSystem) View.VISIBLE else View.GONE
        binding.etEsiWageLimit.setText(localCompany.esiWageLimit.toString())
        binding.etBasicSalaryPct.setText(localCompany.basicSalaryPercentage.toString())
        binding.etEmployeePfPct.setText(localCompany.employeePfPercentage.toString())
        binding.etEmployeeEsiPct.setText(localCompany.employeeEsiPercentage.toString())
        binding.etEmployerPfPct.setText(localCompany.employerPfPercentage.toString())
        binding.etEmployerEsiPct.setText(localCompany.employerEsiPercentage.toString())

        binding.swProfessionalTax.isChecked = localCompany.enableProfessionalTax
        binding.swShiftAllowance.isChecked = localCompany.enableShiftAllowance

        // Tab 4: Email
        binding.swEmailNotifications.isChecked = localCompany.enableEmailNotifications
        binding.layoutEmailFields.visibility = if (localCompany.enableEmailNotifications) View.VISIBLE else View.GONE
        binding.etSmtpHost.setText(localCompany.smtpHost.orEmpty())
        binding.etSmtpPort.setText(localCompany.smtpPort.toString())
        binding.etSmtpFromEmail.setText(localCompany.smtpFromEmail.orEmpty())
        binding.etSmtpUser.setText(localCompany.smtpUser.orEmpty())
        binding.etSmtpPass.setText(localCompany.smtpPass.orEmpty())

        // Tab 5: Leave Rules
        binding.swLeaveAccrual.isChecked = localCompany.enableLeaveAccrual
        binding.etLeaveAccrualRate.setText(localCompany.leaveAccrualRate.toString())
        binding.swSandwichRule.isChecked = localCompany.enableSandwichRule
    }

    private fun saveCompanySettings() {
        val companyName = binding.etGenCompanyName.text?.toString()?.trim().orEmpty()
        if (companyName.isBlank()) {
            binding.etGenCompanyName.error = "Company Name is required"
            binding.tabLayout.getTabAt(0)?.select()
            binding.etGenCompanyName.requestFocus()
            return
        }

        val adminEmail = binding.etAdminEmail.text?.toString()?.trim().orEmpty()
        val adminName = binding.etAdminName.text?.toString()?.trim().orEmpty()
        val adminPhone = binding.etAdminPhone.text?.toString()?.trim().orEmpty()
        val adminPass = binding.etAdminPassword.text?.toString()?.trim().orEmpty()
        val confirmPass = binding.etConfirmAdminPassword.text?.toString()?.trim().orEmpty()

        if (adminPass.isNotBlank()) {
            if (adminPass.length < 6) {
                binding.etAdminPassword.error = "Password must be at least 6 characters"
                binding.tabLayout.getTabAt(1)?.select()
                binding.etAdminPassword.requestFocus()
                return
            }
            if (adminPass != confirmPass) {
                binding.etConfirmAdminPassword.error = "Passwords do not match"
                binding.tabLayout.getTabAt(1)?.select()
                binding.etConfirmAdminPassword.requestFocus()
                return
            }
        }

        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Update LocalCompanySettings object
                localCompany.companyName = companyName
                localCompany.addressLine1 = binding.etGenAddressLine1.text?.toString()?.trim().orEmpty()
                localCompany.cityStatePincode = binding.etGenCityStatePincode.text?.toString()?.trim().orEmpty()
                localCompany.salaryCalculationMethod = binding.spinnerGenSalaryMethod.text?.toString()?.ifBlank { "Days in Month" } ?: "Days in Month"
                localCompany.workDayCutoffHour = binding.etGenCutoffHour.text?.toString()?.toIntOrNull() ?: 22
                localCompany.lateGraceMinutes = binding.etGenLateGrace.text?.toString()?.toIntOrNull() ?: 0
                localCompany.endTimeGraceMinutes = binding.etGenEarlyGrace.text?.toString()?.toIntOrNull() ?: 0
                localCompany.officeLatitude = binding.etGenLatitude.text?.toString()?.toDoubleOrNull() ?: 0.0
                localCompany.officeLongitude = binding.etGenLongitude.text?.toString()?.toDoubleOrNull() ?: 0.0
                localCompany.geoRadiusMeters = binding.etGenRadius.text?.toString()?.toIntOrNull() ?: 1000

                localCompany.zktecoIP = binding.etZktecoIp.text?.toString()?.trim()
                localCompany.zktecoPort = binding.etZktecoPort.text?.toString()?.toIntOrNull() ?: 4370
                localCompany.zktecoMachineNumber = binding.etZktecoMachineNo.text?.toString()?.toIntOrNull() ?: 1

                localCompany.enablePfEsiSystem = binding.swPfEsiSystem.isChecked
                localCompany.esiWageLimit = binding.etEsiWageLimit.text?.toString()?.toDoubleOrNull() ?: 21000.0
                localCompany.basicSalaryPercentage = binding.etBasicSalaryPct.text?.toString()?.toDoubleOrNull() ?: 40.0
                localCompany.employeePfPercentage = binding.etEmployeePfPct.text?.toString()?.toDoubleOrNull() ?: 12.0
                localCompany.employeeEsiPercentage = binding.etEmployeeEsiPct.text?.toString()?.toDoubleOrNull() ?: 0.75
                localCompany.employerPfPercentage = binding.etEmployerPfPct.text?.toString()?.toDoubleOrNull() ?: 13.0
                localCompany.employerEsiPercentage = binding.etEmployerEsiPct.text?.toString()?.toDoubleOrNull() ?: 3.25

                localCompany.enableProfessionalTax = binding.swProfessionalTax.isChecked
                localCompany.enableShiftAllowance = binding.swShiftAllowance.isChecked

                localCompany.enableEmailNotifications = binding.swEmailNotifications.isChecked
                localCompany.smtpHost = binding.etSmtpHost.text?.toString()?.trim()
                localCompany.smtpPort = binding.etSmtpPort.text?.toString()?.toIntOrNull() ?: 587
                localCompany.smtpFromEmail = binding.etSmtpFromEmail.text?.toString()?.trim()
                localCompany.smtpUser = binding.etSmtpUser.text?.toString()?.trim()
                localCompany.smtpPass = binding.etSmtpPass.text?.toString()?.trim()

                localCompany.enableLeaveAccrual = binding.swLeaveAccrual.isChecked
                localCompany.leaveAccrualRate = binding.etLeaveAccrualRate.text?.toString()?.toDoubleOrNull() ?: 1.5
                localCompany.enableSandwichRule = binding.swSandwichRule.isChecked

                // Save into Room
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertCompanySettings(localCompany)
                }

                // Save GPS Tracking Interval
                val selectedLabel = binding.spinnerGpsInterval.text?.toString()
                val seconds = intervalLabels.find { it.first == selectedLabel }?.second ?: 30
                runCatching {
                    val currentCfg = trackingConfiguration.load()
                    trackingConfiguration.save(currentCfg.copy(intervalSeconds = seconds))
                }

                // Save Auto-Backup Interval
                val selectedBackupLabel = binding.spinnerBackupInterval.text?.toString()
                val backupHours = backupIntervalLabels.find { it.first == selectedBackupLabel }?.second ?: 24
                localCompany.autoBackupIntervalHours = backupHours

                // Save into Room (with updated backup interval)
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertCompanySettings(localCompany)
                }

                // If online, sync to Firebase Realtime Database
                val owner = firebaseSync.getOwnerRef()
                if (owner != null) {
                    runCatching {
                        val companyPayload = mapOf(
                            "companyName" to localCompany.companyName,
                            "addressLine1" to localCompany.addressLine1,
                            "cityStatePincode" to localCompany.cityStatePincode,
                            "salaryCalculationMethod" to localCompany.salaryCalculationMethod,
                            "officeLatitude" to localCompany.officeLatitude,
                            "officeLongitude" to localCompany.officeLongitude,
                            "geoRadiusMeters" to localCompany.geoRadiusMeters,
                            "workDayCutoffHour" to localCompany.workDayCutoffHour,
                            "lateGraceMinutes" to localCompany.lateGraceMinutes,
                            "endTimeGraceMinutes" to localCompany.endTimeGraceMinutes,
                            "zktecoIP" to localCompany.zktecoIP,
                            "zktecoPort" to localCompany.zktecoPort,
                            "zktecoMachineNumber" to localCompany.zktecoMachineNumber,
                            "enablePfEsiSystem" to localCompany.enablePfEsiSystem,
                            "esiWageLimit" to localCompany.esiWageLimit,
                            "basicSalaryPercentage" to localCompany.basicSalaryPercentage,
                            "employeePfPercentage" to localCompany.employeePfPercentage,
                            "employeeEsiPercentage" to localCompany.employeeEsiPercentage,
                            "employerPfPercentage" to localCompany.employerPfPercentage,
                            "employerEsiPercentage" to localCompany.employerEsiPercentage,
                            "enableProfessionalTax" to localCompany.enableProfessionalTax,
                            "enableShiftAllowance" to localCompany.enableShiftAllowance,
                            "enableEmailNotifications" to localCompany.enableEmailNotifications,
                            "smtpHost" to localCompany.smtpHost,
                            "smtpPort" to localCompany.smtpPort,
                            "smtpFromEmail" to localCompany.smtpFromEmail,
                            "smtpUser" to localCompany.smtpUser,
                            "smtpPass" to localCompany.smtpPass,
                            "enableLeaveAccrual" to localCompany.enableLeaveAccrual,
                            "leaveAccrualRate" to localCompany.leaveAccrualRate,
                            "enableSandwichRule" to localCompany.enableSandwichRule,
                            "autoBackupIntervalHours" to localCompany.autoBackupIntervalHours
                        )
                        owner.child("company_settings").child("1").setValue(companyPayload).await()

                        // Sync Admin Credentials to owner root node
                        val adminUpdates = mutableMapOf<String, Any>()
                        if (adminEmail.isNotBlank()) adminUpdates["adminEmail"] = adminEmail
                        if (adminName.isNotBlank()) adminUpdates["adminName"] = adminName
                        if (adminPhone.isNotBlank()) adminUpdates["adminPhone"] = adminPhone
                        if (adminUpdates.isNotEmpty()) {
                            owner.updateChildren(adminUpdates).await()
                        }

                        // Update Firebase Auth password if changed
                        if (adminPass.isNotBlank()) {
                            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            user?.updatePassword(adminPass)?.await()
                        }

                        firebaseSync.notifyRealtimeAfterWrite("CompanySettings", "MODIFIED")
                    }
                }

                binding.loadingOverlay.visibility = View.GONE
                HapticUtil.vibrateSuccess(binding.root)
                Toast.makeText(this@CompanySettingsActivity, "✨ Settings saved successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (ex: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySettingsActivity, "Save failed: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeleteCompanyDialog() {
        val input = EditText(this).apply {
            hint = "Type DELETE in capital letters"
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Permanent Company Deletion")
            .setMessage("Permanently wipe this entire company and all associated records from local database and cloud. This action is irreversible!\n\nType DELETE to confirm:")
            .setView(input)
            .setPositiveButton("DELETE COMPANY") { _, _ ->
                val confirmation = input.text?.toString()?.trim()
                if (confirmation == "DELETE") {
                    executeCompanyDeletion()
                } else {
                    Toast.makeText(this, "Confirmation text did not match. Deletion cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeCompanyDeletion() {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertCompanySettings(LocalCompanySettings(id = 1, companyName = "New Company"))
                }

                val owner = firebaseSync.getOwnerRef()
                if (owner != null) {
                    runCatching {
                        owner.child("company_settings").removeValue().await()
                        firebaseSync.notifyRealtimeAfterWrite("CompanySettings", "DELETED")
                    }
                }

                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySettingsActivity, "✨ Company records wiped.", Toast.LENGTH_LONG).show()
                finish()
            } catch (ex: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySettingsActivity, "Deletion failed: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
