package com.biometric.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.dao.LocalSettingsDao
import com.biometric.app.data.entity.CompanySettings
import com.biometric.app.data.entity.FeatureSettings
import com.biometric.app.data.entity.LocalCompanySettings
import com.biometric.app.data.entity.LocalFeatureSettings
import com.biometric.app.databinding.ActivityCompanySetupBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class CompanySetupActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityCompanySetupBinding

    @Inject lateinit var localSettingsDao: LocalSettingsDao
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var sharedViewModel: SharedViewModel

    private var localCompany = LocalCompanySettings()
    private var localFeatures = LocalFeatureSettings()
    private var isOfflineMode = false
    private var isSuperAdmin = false

    private val emojiList = listOf(
        "🏢 Corporate",
        "🏭 Industrial",
        "🏥 Hospital",
        "🏦 Financial",
        "🚀 Tech Startup",
        "💻 IT Software",
        "🏪 Retail",
        "🏨 Hotel",
        "🏫 School/Edu"
    )

    private val salaryCalcMethods = listOf(
        "Days in Month",
        "Fixed 30-Day",
        "Pro-Rata Hourly"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwardIntent = Intent(this, CompanySettingsActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
        }
        startActivity(forwardIntent)
        finish()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }
    }

    private fun setupDropdowns() {
        val emojiAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emojiList)
        binding.spinnerEmoji.setAdapter(emojiAdapter)

        val salaryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, salaryCalcMethods)
        binding.spinnerSalaryMethod.setAdapter(salaryAdapter)
    }

    private fun setupModeSelectors() {
        updateDeploymentModeUi(isOfflineMode)

        binding.cardModeOffline.setOnClickListener {
            HapticUtil.vibrateClick(it)
            isOfflineMode = true
            updateDeploymentModeUi(true)
        }

        binding.cardModeOnline.setOnClickListener {
            HapticUtil.vibrateClick(it)
            isOfflineMode = false
            updateDeploymentModeUi(false)
        }
    }

    private fun updateDeploymentModeUi(offline: Boolean) {
        val primaryColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val outlineColor = ContextCompat.getColor(this, R.color.outline_variant)
        val surfaceColor = ContextCompat.getColor(this, R.color.colorSurface)
        val greyLight = ContextCompat.getColor(this, R.color.grey_light)

        if (offline) {
            binding.cardModeOffline.strokeColor = primaryColor
            binding.cardModeOffline.strokeWidth = 4
            binding.cardModeOffline.setCardBackgroundColor(greyLight)

            binding.cardModeOnline.strokeColor = outlineColor
            binding.cardModeOnline.strokeWidth = 2
            binding.cardModeOnline.setCardBackgroundColor(surfaceColor)

            binding.tvDeploymentBadge.text = "📴 Standalone Mode"
            binding.tvDeploymentBadge.setBackgroundResource(R.drawable.badge_rounded_grey)
            binding.tvDeploymentBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            binding.cardModeOnline.strokeColor = primaryColor
            binding.cardModeOnline.strokeWidth = 4
            binding.cardModeOnline.setCardBackgroundColor(ContextCompat.getColor(this, R.color.blue_light))

            binding.cardModeOffline.strokeColor = outlineColor
            binding.cardModeOffline.strokeWidth = 2
            binding.cardModeOffline.setCardBackgroundColor(surfaceColor)

            binding.tvDeploymentBadge.text = "🌐 Online Cloud"
            binding.tvDeploymentBadge.setBackgroundResource(R.drawable.badge_rounded_blue)
            binding.tvDeploymentBadge.setTextColor(ContextCompat.getColor(this, R.color.blue_900))
        }
    }

    private fun setupListeners() {
        binding.btnTopSave.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveCompanySetup()
        }

        binding.btnBottomSave.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveCompanySetup()
        }

        binding.btnReturnDashboard.setOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        binding.btnOpenMaps.setOnClickListener {
            HapticUtil.vibrateClick(it)
            val lat = binding.etOfficeLatitude.text?.toString()?.toDoubleOrNull() ?: 0.0
            val lng = binding.etOfficeLongitude.text?.toString()?.toDoubleOrNull() ?: 0.0
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

        binding.swDualAttendance.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.swGeoFencing.isChecked = true
        }

        binding.btnDeleteCompany.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showDeleteCompanyDialog()
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            sharedViewModel.userProfile.collectLatest { profile ->
                isSuperAdmin = profile?.isSuperAdmin() == true
                binding.cardDangerZone.visibility = if (isSuperAdmin) View.VISIBLE else View.GONE
                if (!profile?.email.isNullOrBlank() && binding.etAdminEmail.text.isNullOrBlank()) {
                    binding.etAdminEmail.setText(profile?.email)
                }
                if (!profile?.name.isNullOrBlank() && binding.etAdminName.text.isNullOrBlank()) {
                    binding.etAdminName.setText(profile?.name)
                }
            }
        }
    }

    private fun loadInitialData() {
        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            val cs = withContext(Dispatchers.IO) { localSettingsDao.getCompanySettings() }
            val fs = withContext(Dispatchers.IO) { localSettingsDao.getFeatureSettings() }

            if (cs != null) localCompany = cs
            if (fs != null) localFeatures = fs

            populateUi()
            binding.loadingOverlay.visibility = View.GONE

            // Attempt Firebase sync for latest cloud values
            val owner = firebaseSync.getOwnerRef()
            if (owner != null) {
                owner.child("company_settings").child("1").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            snapshot.child("companyName").getValue(String::class.java)?.let {
                                if (binding.etCompanyName.text.isNullOrBlank()) binding.etCompanyName.setText(it)
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
        }
    }

    private fun populateUi() {
        binding.etCompanyName.setText(localCompany.companyName)
        binding.etAddressLine1.setText(localCompany.addressLine1)
        binding.etCityStatePincode.setText(localCompany.cityStatePincode)
        binding.etCutoffHour.setText(localCompany.workDayCutoffHour.toString())

        val methodIdx = salaryCalcMethods.indexOf(localCompany.salaryCalculationMethod)
        if (methodIdx >= 0) {
            binding.spinnerSalaryMethod.setText(salaryCalcMethods[methodIdx], false)
        } else {
            binding.spinnerSalaryMethod.setText(salaryCalcMethods[0], false)
        }

        binding.spinnerEmoji.setText(emojiList[0], false)

        binding.etOfficeLatitude.setText(if (localCompany.officeLatitude != 0.0) localCompany.officeLatitude.toString() else "")
        binding.etOfficeLongitude.setText(if (localCompany.officeLongitude != 0.0) localCompany.officeLongitude.toString() else "")
        binding.etGeoRadiusMeters.setText(localCompany.geoRadiusMeters.toString())
        binding.etLateGraceMinutes.setText(localCompany.lateGraceMinutes.toString())
        binding.etEndTimeGraceMinutes.setText(localCompany.endTimeGraceMinutes.toString())

        // Feature Switches
        binding.swPayroll.isChecked = localFeatures.enablePayroll
        binding.swGeoFencing.isChecked = localFeatures.enableGeoFencing
        binding.swDualAttendance.isChecked = localFeatures.enableDualAttendance
        binding.swSalaryAdvance.isChecked = localFeatures.enableSalaryAdvance
        binding.swBonusManagement.isChecked = localFeatures.enableBonusManagement
        binding.swLeaveManagement.isChecked = localFeatures.enableLeaveManagement
        binding.swShiftScheduling.isChecked = localFeatures.enableShiftScheduling
        binding.swPunchCorrection.isChecked = localFeatures.enablePunchCorrection
        binding.swTaxDeclarations.isChecked = localFeatures.enableTaxDeclarations
        binding.swResignationModule.isChecked = localFeatures.enableResignationModule
        binding.swFlexibleBenefits.isChecked = localFeatures.enableFlexibleBenefits
        binding.swCompanyReports.isChecked = localFeatures.enableCompanyReports
    }

    private fun saveCompanySetup() {
        val companyName = binding.etCompanyName.text?.toString()?.trim().orEmpty()
        val adminEmail = binding.etAdminEmail.text?.toString()?.trim().orEmpty()
        val pwd = binding.etAdminPassword.text?.toString().orEmpty()
        val pwdConfirm = binding.etAdminPasswordConfirm.text?.toString().orEmpty()

        if (companyName.isBlank()) {
            binding.etCompanyName.error = "Company Name is required"
            binding.etCompanyName.requestFocus()
            return
        }

        if (adminEmail.isBlank()) {
            binding.etAdminEmail.error = "Admin Email is required"
            binding.etAdminEmail.requestFocus()
            return
        }

        if (pwd.isNotBlank()) {
            if (pwd.length < 6) {
                binding.etAdminPassword.error = "Password must be at least 6 characters"
                binding.etAdminPassword.requestFocus()
                return
            }
            if (pwd != pwdConfirm) {
                binding.etAdminPasswordConfirm.error = "Passwords do not match"
                binding.etAdminPasswordConfirm.requestFocus()
                return
            }
        }

        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Update LocalCompanySettings
                localCompany.companyName = companyName
                localCompany.addressLine1 = binding.etAddressLine1.text?.toString()?.trim().orEmpty()
                localCompany.cityStatePincode = binding.etCityStatePincode.text?.toString()?.trim().orEmpty()
                localCompany.workDayCutoffHour = binding.etCutoffHour.text?.toString()?.toIntOrNull() ?: 22
                localCompany.salaryCalculationMethod = binding.spinnerSalaryMethod.text?.toString()?.ifBlank { "Days in Month" } ?: "Days in Month"
                localCompany.officeLatitude = binding.etOfficeLatitude.text?.toString()?.toDoubleOrNull() ?: 0.0
                localCompany.officeLongitude = binding.etOfficeLongitude.text?.toString()?.toDoubleOrNull() ?: 0.0
                localCompany.geoRadiusMeters = binding.etGeoRadiusMeters.text?.toString()?.toIntOrNull() ?: 1000
                localCompany.lateGraceMinutes = binding.etLateGraceMinutes.text?.toString()?.toIntOrNull() ?: 0
                localCompany.endTimeGraceMinutes = binding.etEndTimeGraceMinutes.text?.toString()?.toIntOrNull() ?: 0

                // Update LocalFeatureSettings
                localFeatures.enablePayroll = binding.swPayroll.isChecked
                localFeatures.enableGeoFencing = binding.swGeoFencing.isChecked
                localFeatures.enableDualAttendance = binding.swDualAttendance.isChecked
                localFeatures.enableSalaryAdvance = binding.swSalaryAdvance.isChecked
                localFeatures.enableBonusManagement = binding.swBonusManagement.isChecked
                localFeatures.enableLeaveManagement = binding.swLeaveManagement.isChecked
                localFeatures.enableShiftScheduling = binding.swShiftScheduling.isChecked
                localFeatures.enablePunchCorrection = binding.swPunchCorrection.isChecked
                localFeatures.enableTaxDeclarations = binding.swTaxDeclarations.isChecked
                localFeatures.enableResignationModule = binding.swResignationModule.isChecked
                localFeatures.enableFlexibleBenefits = binding.swFlexibleBenefits.isChecked
                localFeatures.enableCompanyReports = binding.swCompanyReports.isChecked

                // Save into Room
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertCompanySettings(localCompany)
                    localSettingsDao.upsertFeatureSettings(localFeatures)
                }

                // If online, sync to Firebase Realtime Database
                val owner = firebaseSync.getOwnerRef()
                if (owner != null && !isOfflineMode) {
                    runCatching {
                        val companyPayload = mapOf(
                            "companyName" to localCompany.companyName,
                            "addressLine1" to localCompany.addressLine1,
                            "cityStatePincode" to localCompany.cityStatePincode,
                            "officeLatitude" to localCompany.officeLatitude,
                            "officeLongitude" to localCompany.officeLongitude,
                            "geoRadiusMeters" to localCompany.geoRadiusMeters,
                            "workDayCutoffHour" to localCompany.workDayCutoffHour,
                            "lateGraceMinutes" to localCompany.lateGraceMinutes,
                            "endTimeGraceMinutes" to localCompany.endTimeGraceMinutes,
                            "salaryCalculationMethod" to localCompany.salaryCalculationMethod
                        )
                        owner.child("company_settings").child("1").setValue(companyPayload).await()

                        // Convert features to Map and push
                        val featureMap = Gson().fromJson(Gson().toJson(localFeatures), Map::class.java)
                        owner.child("feature_settings").child("1").setValue(featureMap).await()

                        firebaseSync.notifyRealtimeAfterWrite("CompanySettings", "MODIFIED")
                        firebaseSync.notifyRealtimeAfterWrite("FeatureSettings", "MODIFIED")
                    }
                }

                binding.loadingOverlay.visibility = View.GONE
                HapticUtil.vibrateSuccess(binding.root)
                Toast.makeText(this@CompanySetupActivity, "✨ Company setup saved successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (ex: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySetupActivity, "Save failed: ${ex.message}", Toast.LENGTH_LONG).show()
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
                // Clear local company settings
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertCompanySettings(LocalCompanySettings(id = 1, companyName = "New Company"))
                }

                // If online, delete owner node or company node in Firebase
                val owner = firebaseSync.getOwnerRef()
                if (owner != null) {
                    runCatching {
                        owner.child("company_settings").removeValue().await()
                        firebaseSync.notifyRealtimeAfterWrite("CompanySettings", "DELETED")
                    }
                }

                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySetupActivity, "✨ Company records wiped.", Toast.LENGTH_LONG).show()
                finish()
            } catch (ex: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@CompanySetupActivity, "Deletion failed: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
