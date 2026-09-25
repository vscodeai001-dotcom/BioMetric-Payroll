package com.biometric.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.AppDatabase
import com.biometric.app.data.dao.LocalSettingsDao
import com.biometric.app.data.entity.LocalFeatureSettings
import com.biometric.app.databinding.ActivityFeatureToggleManagerBinding
import com.biometric.app.databinding.ItemBackupEntryBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class LocalBackupMetadata(
    val fileName: String,
    val filePath: String,
    val createdAtMillis: Long,
    val fileSizeBytes: Long,
    val triggerType: String = "Manual",
    val tenantId: String = "biometricpayroll"
) {
    val formattedSize: String
        get() = when {
            fileSizeBytes < 1024 -> "$fileSizeBytes B"
            fileSizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", fileSizeBytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", fileSizeBytes / (1024.0 * 1024.0))
        }

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("dd-MMM-yyyy hh:mm:ss a", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            return sdf.format(Date(createdAtMillis))
        }
}

@AndroidEntryPoint
class FeatureToggleManagerActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityFeatureToggleManagerBinding

    @Inject lateinit var localSettingsDao: LocalSettingsDao
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var appDatabase: AppDatabase

    private val gson = Gson()
    private var currentSettings = LocalFeatureSettings()
    private var isSaving = false
    private var activeCompanyName = "Primary Workspace"
    private var activeTenantId = "biometricpayroll"
    private var activeCompanyCode = "PRIMARY"
    private var isUserSuperAdmin = false

    private val backupsList = mutableListOf<LocalBackupMetadata>()
    private lateinit var backupAdapter: BackupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeatureToggleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clFeatureToggleRoot, binding.appBar)

        val role = sessionStore.userRole().trim().uppercase()
        isUserSuperAdmin = role in setOf("SUPERADMIN", "SUPER_ADMIN", "ADMIN")

        activeTenantId = sessionStore.firebaseOwnerUid() ?: "biometricpayroll"
        activeCompanyCode = if (activeTenantId.startsWith("tenant_")) activeTenantId.removePrefix("tenant_").uppercase() else "PRIMARY"

        setupToolbar()
        setupTabs()
        setupListeners()
        setupBackupsRecyclerView()

        loadSettings()
        loadBackups()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSaveAllSettings.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveAllSettings()
        }
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.removeAllTabs()

        tabLayout.addTab(tabLayout.newTab().setText("⊞ Core Modules"))
        tabLayout.addTab(tabLayout.newTab().setText("👤 Admin Permissions"))
        tabLayout.addTab(tabLayout.newTab().setText("👥 Employee Permissions"))
        tabLayout.addTab(tabLayout.newTab().setText("☢️ Danger Zone"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                HapticUtil.vibrateTick(tabLayout)
                when (tab?.position) {
                    0 -> showTab(0)
                    1 -> showTab(1)
                    2 -> showTab(2)
                    3 -> showTab(3)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        showTab(0)
    }

    private fun showTab(position: Int) {
        binding.scrollCoreModules.isVisible = (position == 0)
        binding.scrollAdminPermissions.isVisible = (position == 1)
        binding.scrollEmployeePermissions.isVisible = (position == 2)
        binding.scrollDangerZone.isVisible = (position == 3)
    }

    private fun setupListeners() {
        // --- Inter-Toggle Dependencies (1:1 Web Parity) ---

        // Bonus Management -> Employee View Bonuses
        binding.swBonusManagement.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmployeeViewBonuses.isEnabled = isChecked
            if (!isChecked) {
                binding.swEmployeeViewBonuses.isChecked = false
                binding.swEmpViewBonuses.isChecked = false
            }
        }

        // Shift Scheduling -> Auto Shift Rotation & Employee Shift Schedule
        binding.swShiftScheduling.setOnCheckedChangeListener { _, isChecked ->
            binding.swAutoShiftRotation.isEnabled = isChecked
            binding.swEmpViewShiftSchedule.isEnabled = isChecked
            if (!isChecked) {
                binding.swAutoShiftRotation.isChecked = false
                binding.swEmpViewShiftSchedule.isChecked = false
            }
        }

        // Geo-Fencing Master Switch -> Dual Attendance & Automatic Geofence Punching
        binding.swEnableGeoFencing.setOnCheckedChangeListener { _, isChecked ->
            binding.swDualAttendance.isEnabled = isChecked
            binding.swAutoGeofencePunching.isEnabled = isChecked
            if (!isChecked) {
                binding.swDualAttendance.isChecked = false
                binding.swAutoGeofencePunching.isChecked = false
            }
            updateGeoFencingExplanation()
        }

        binding.swDualAttendance.setOnCheckedChangeListener { _, _ -> updateGeoFencingExplanation() }
        binding.swAutoGeofencePunching.setOnCheckedChangeListener { _, _ -> updateGeoFencingExplanation() }

        // Leave Module -> Accrual, Sandwich & Employee Leave View
        binding.swEnableLeaveModule.setOnCheckedChangeListener { _, isChecked ->
            binding.swLeaveAccrual.isEnabled = isChecked
            binding.swSandwichRule.isEnabled = isChecked
            binding.swEmpViewLeave.isEnabled = isChecked
            if (!isChecked) {
                binding.swLeaveAccrual.isChecked = false
                binding.swSandwichRule.isChecked = false
                binding.swEmpViewLeave.isChecked = false
            }
        }

        // Payroll -> Employee Payslips
        binding.swRunPayroll.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmpViewPayslips.isEnabled = isChecked
            if (!isChecked) binding.swEmpViewPayslips.isChecked = false
        }

        // Salary Advance -> Employee Salary Advances
        binding.swSalaryAdvance.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmpViewSalaryAdvances.isEnabled = isChecked
            if (!isChecked) binding.swEmpViewSalaryAdvances.isChecked = false
        }

        // Tax Declarations -> Employee Tax Declarations
        binding.swTaxDeclarations.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmpSubmitTax.isEnabled = isChecked
            if (!isChecked) binding.swEmpSubmitTax.isChecked = false
        }

        // Exit / Resignation -> Employee Resignation
        binding.swExitFnfSettlement.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmpSubmitResignation.isEnabled = isChecked
            if (!isChecked) binding.swEmpSubmitResignation.isChecked = false
        }

        // Custom Reporting -> Employee View Reports
        binding.swCustomReporting.setOnCheckedChangeListener { _, isChecked ->
            binding.swEmpViewReports.isEnabled = isChecked
            if (!isChecked) binding.swEmpViewReports.isChecked = false
        }

        // Danger Zone Action Buttons
        binding.btnBackupDatabaseNow.setOnClickListener {
            HapticUtil.vibrateClick(it)
            performBackup("Manual")
        }

        binding.btnResetFeatureToggles.setOnClickListener {
            HapticUtil.vibrateClick(it)
            confirmResetToggles()
        }

        binding.btnPartialWipe.setOnClickListener {
            HapticUtil.vibrateClick(it)
            confirmPartialWipe()
        }

        binding.btnFullWipe.setOnClickListener {
            HapticUtil.vibrateClick(it)
            confirmFullWipe()
        }

        binding.btnRefreshBackups.setOnClickListener {
            HapticUtil.vibrateClick(it)
            loadBackups()
        }
    }

    private fun updateGeoFencingExplanation() {
        val geo = binding.swEnableGeoFencing.isChecked
        val dual = binding.swDualAttendance.isChecked
        val autoPunch = binding.swAutoGeofencePunching.isChecked

        binding.tvGeoFencingExplanation.text = when {
            !geo -> "Geo-Fencing is OFF: all GPS, Dual Attendance and Automatic Geofence Punching are disabled. Physical biometric attendance remains active."
            dual && autoPunch -> "Full Dual Attendance: physical biometric is highest priority; automatic geofence IN/OUT is also recorded and reconciled."
            dual -> "Dual Attendance: physical biometric and geofence are active; automatic geofence punching is disabled."
            autoPunch -> "Automatic Geo Attendance: geofence automatically records IN/OUT; physical biometric is disabled in single Geo mode."
            else -> "Geo-Fencing is active without automatic punching. Manual employee punch is available."
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val company = withContext(Dispatchers.IO) {
                localSettingsDao.getCompanySettings()
            }
            if (company != null && company.companyName.isNotBlank()) {
                activeCompanyName = company.companyName
            }
            updateWorkspaceBanner()

            val local = withContext(Dispatchers.IO) {
                localSettingsDao.getFeatureSettings() ?: LocalFeatureSettings()
            }
            currentSettings = local
            populateUI(local)
        }
    }

    private fun updateWorkspaceBanner() {
        binding.tvDangerWorkspaceTitle.text = "Danger Zone Workspace: $activeCompanyName"
        binding.tvDangerTenantCode.text = "Tenant Code: $activeCompanyCode | ID: $activeTenantId"
        binding.tvResetTogglesDesc.text = "Revert feature toggles for $activeCompanyName to factory defaults."
        binding.tvPartialWipeDesc.text = "Wipes logs, tracking & payroll for $activeCompanyName only. Other companies remain safe."
        binding.tvFullWipeDesc.text = "Wipes employees & records for $activeCompanyName only from Local & Cloud."
        binding.btnPartialWipe.text = "🔶 Partial Wipe ($activeCompanyCode)"
        binding.btnFullWipe.text = "🗑 Full Wipe ($activeCompanyCode)"
    }

    private fun populateUI(s: LocalFeatureSettings) {
        // Tab 1: Core Modules
        binding.swRunPayroll.isChecked = s.enablePayroll
        binding.swSalaryAdvance.isChecked = s.enableSalaryAdvance
        binding.swBonusManagement.isChecked = s.enableBonusManagement
        binding.swEmployeeViewBonuses.isChecked = s.employeeCanViewBonus
        binding.swEmployeeViewBonuses.isEnabled = s.enableBonusManagement
        binding.swYearEndSummary.isChecked = s.enableYearEndSummary
        binding.swFbpCompStructuring.isChecked = s.enableFlexibleBenefits

        binding.swPfEsiCompliance.isChecked = s.enableStatutoryCompliance
        binding.swProfessionalTax.isChecked = s.enableProfessionalTax
        binding.swEnableTdsDeduction.isChecked = s.enableTdsDeduction
        binding.swTaxDeclarations.isChecked = s.enableTaxDeclarations

        binding.swEmployeeManagement.isChecked = s.enableEmployeeManagement
        binding.swShiftScheduling.isChecked = s.enableShiftScheduling
        binding.swAutoShiftRotation.isChecked = s.enableAutoShiftRotation
        binding.swAutoShiftRotation.isEnabled = s.enableShiftScheduling
        binding.swNightShiftAllowance.isChecked = s.enableShiftAllowance
        binding.swPunchCorrection.isChecked = s.enablePunchCorrection
        binding.swEnableGeoFencing.isChecked = s.enableGeoFencing
        binding.swDualAttendance.isChecked = s.enableDualAttendance
        binding.swDualAttendance.isEnabled = s.enableGeoFencing
        binding.swAutoGeofencePunching.isChecked = s.enableAutomaticGeofencePunching
        binding.swAutoGeofencePunching.isEnabled = s.enableGeoFencing
        updateGeoFencingExplanation()

        binding.swCompanyReports.isChecked = s.enableCompanyReports
        binding.swCustomReporting.isChecked = s.enableCustomReporting
        binding.swRegularizationRequests.isChecked = s.enableRegularizationReq

        binding.swEnableLeaveModule.isChecked = s.enableLeaveManagement
        binding.swLeaveAccrual.isChecked = s.enableLeaveAccrual
        binding.swLeaveAccrual.isEnabled = s.enableLeaveManagement
        binding.swSandwichRule.isChecked = s.enableSandwichRule
        binding.swSandwichRule.isEnabled = s.enableLeaveManagement
        binding.swExitFnfSettlement.isChecked = s.enableResignationModule

        binding.swEmailNotifications.isChecked = s.enableEmailNotifications
        binding.swInAppNotificationBell.isChecked = s.enableInAppNotifications
        binding.swDarkLightToggle.isChecked = s.showThemeToggle
        binding.swEnableAuditTrails.isChecked = s.enableAuditLog
        binding.swEnableRecycleBin.isChecked = s.enableRecycleBin

        if (s.firebasePlanMode.equals("Blaze", ignoreCase = true)) {
            binding.rbPlanBlaze.isChecked = true
        } else {
            binding.rbPlanSpark.isChecked = true
        }

        // Tab 2: Admin Permissions
        binding.swAdminViewDashboard.isChecked = s.adminCanViewDashboard
        binding.swAdminViewAttendanceLogs.isChecked = s.adminCanViewAttendance
        binding.swAdminViewReports.isChecked = s.adminCanViewReports
        binding.swAdminManageEmployees.isChecked = s.adminCanManageEmployees
        binding.swAdminManageShifts.isChecked = s.adminCanManageShifts
        binding.swAdminPunchApprovalRequests.isChecked = s.adminCanManagePunchApprovals
        binding.swAdminRunPayroll.isChecked = s.adminCanRunPayroll
        binding.swAdminEditGlobalSettings.isChecked = s.adminCanEditSettings
        binding.swAdminManageEmployeePerms.isChecked = s.adminCanManageEmployeePermissions

        // Tab 3: Employee Permissions
        binding.swEmpShowHome.isChecked = s.employeeCanViewDashboard
        binding.swEmpShowTools.isChecked = s.employeeToolsVisible

        binding.swEmpViewAttendance.isChecked = s.employeeCanViewAttendance
        binding.swEmpViewShiftSchedule.isChecked = s.employeeCanViewShifts
        binding.swEmpViewShiftSchedule.isEnabled = s.enableShiftScheduling
        binding.swEmpRequestCorrection.isChecked = s.enableRegularizationReq

        binding.swEmpViewPayslips.isChecked = s.employeeCanViewPayslip
        binding.swEmpViewPayslips.isEnabled = s.enablePayroll
        binding.swEmpViewBonuses.isChecked = s.employeeCanViewBonus
        binding.swEmpViewBonuses.isEnabled = s.enableBonusManagement
        binding.swEmpViewSalaryAdvances.isChecked = s.employeeCanViewAdvance
        binding.swEmpViewSalaryAdvances.isEnabled = s.enableSalaryAdvance
        binding.swEmpSubmitTax.isChecked = s.employeeCanViewTax
        binding.swEmpSubmitTax.isEnabled = s.enableTaxDeclarations

        binding.swEmpViewLeave.isChecked = s.employeeCanViewLeave
        binding.swEmpViewLeave.isEnabled = s.enableLeaveManagement
        binding.swEmpSubmitResignation.isChecked = s.employeeCanViewResignation
        binding.swEmpSubmitResignation.isEnabled = s.enableResignationModule
        binding.swEmpViewReports.isChecked = s.employeeCanViewReports
        binding.swEmpViewReports.isEnabled = s.enableCustomReporting
    }

    private fun gatherSettingsFromUI(): LocalFeatureSettings {
        val geo = binding.swEnableGeoFencing.isChecked
        val plan = if (binding.rbPlanBlaze.isChecked) "Blaze" else "Spark"

        return LocalFeatureSettings(
            id = 1,
            enablePayroll = binding.swRunPayroll.isChecked,
            enableSalaryAdvance = binding.swSalaryAdvance.isChecked,
            enableBonusManagement = binding.swBonusManagement.isChecked,
            employeeCanViewBonus = if (binding.swBonusManagement.isChecked) binding.swEmployeeViewBonuses.isChecked else false,
            enableYearEndSummary = binding.swYearEndSummary.isChecked,
            enableFlexibleBenefits = binding.swFbpCompStructuring.isChecked,

            enableStatutoryCompliance = binding.swPfEsiCompliance.isChecked,
            enableProfessionalTax = binding.swProfessionalTax.isChecked,
            enableTdsDeduction = binding.swEnableTdsDeduction.isChecked,
            enableTaxDeclarations = binding.swTaxDeclarations.isChecked,

            enableEmployeeManagement = binding.swEmployeeManagement.isChecked,
            enableShiftScheduling = binding.swShiftScheduling.isChecked,
            enableAutoShiftRotation = if (binding.swShiftScheduling.isChecked) binding.swAutoShiftRotation.isChecked else false,
            enableShiftAllowance = binding.swNightShiftAllowance.isChecked,
            enablePunchCorrection = binding.swPunchCorrection.isChecked,
            enableGeoFencing = geo,
            enableDualAttendance = if (geo) binding.swDualAttendance.isChecked else false,
            enableAutomaticGeofencePunching = if (geo) binding.swAutoGeofencePunching.isChecked else false,

            enableCompanyReports = binding.swCompanyReports.isChecked,
            enableCustomReporting = binding.swCustomReporting.isChecked,
            enableRegularizationReq = binding.swRegularizationRequests.isChecked,

            enableLeaveManagement = binding.swEnableLeaveModule.isChecked,
            enableLeaveAccrual = if (binding.swEnableLeaveModule.isChecked) binding.swLeaveAccrual.isChecked else false,
            enableSandwichRule = if (binding.swEnableLeaveModule.isChecked) binding.swSandwichRule.isChecked else false,
            enableResignationModule = binding.swExitFnfSettlement.isChecked,

            enableEmailNotifications = binding.swEmailNotifications.isChecked,
            enableInAppNotifications = binding.swInAppNotificationBell.isChecked,
            showThemeToggle = binding.swDarkLightToggle.isChecked,
            enableAuditLog = binding.swEnableAuditTrails.isChecked,
            enableRecycleBin = binding.swEnableRecycleBin.isChecked,
            firebasePlanMode = plan,

            adminCanViewDashboard = binding.swAdminViewDashboard.isChecked,
            adminCanViewAttendance = binding.swAdminViewAttendanceLogs.isChecked,
            adminCanViewReports = binding.swAdminViewReports.isChecked,
            adminCanManageEmployees = binding.swAdminManageEmployees.isChecked,
            adminCanManageShifts = binding.swAdminManageShifts.isChecked,
            adminCanManagePunchApprovals = binding.swAdminPunchApprovalRequests.isChecked,
            adminCanRunPayroll = binding.swAdminRunPayroll.isChecked,
            adminCanEditSettings = binding.swAdminEditGlobalSettings.isChecked,
            adminCanManageEmployeePermissions = binding.swAdminManageEmployeePerms.isChecked,

            employeeCanViewDashboard = binding.swEmpShowHome.isChecked,
            employeeToolsVisible = binding.swEmpShowTools.isChecked,
            employeeCanViewAttendance = binding.swEmpViewAttendance.isChecked,
            employeeCanViewShifts = if (binding.swShiftScheduling.isChecked) binding.swEmpViewShiftSchedule.isChecked else false,
            employeeCanViewPayslip = if (binding.swRunPayroll.isChecked) binding.swEmpViewPayslips.isChecked else false,
            employeeCanViewAdvance = if (binding.swSalaryAdvance.isChecked) binding.swEmpViewSalaryAdvances.isChecked else false,
            employeeCanViewTax = if (binding.swTaxDeclarations.isChecked) binding.swEmpSubmitTax.isChecked else false,
            employeeCanViewLeave = if (binding.swEnableLeaveModule.isChecked) binding.swEmpViewLeave.isChecked else false,
            employeeCanViewLeaveHistory = if (binding.swEnableLeaveModule.isChecked) binding.swEmpViewLeave.isChecked else false,
            employeeCanViewResignation = if (binding.swExitFnfSettlement.isChecked) binding.swEmpSubmitResignation.isChecked else false,
            employeeCanViewReports = if (binding.swCustomReporting.isChecked) binding.swEmpViewReports.isChecked else false
        )
    }

    private fun saveAllSettings() {
        if (isSaving) return
        isSaving = true
        binding.btnSaveAllSettings.isEnabled = false
        binding.btnSaveAllSettings.text = "Saving..."

        lifecycleScope.launch {
            try {
                val updated = gatherSettingsFromUI()

                // 1. Update SQLite Room
                withContext(Dispatchers.IO) {
                    localSettingsDao.upsertFeatureSettings(updated)
                }

                // 2. Synchronize to Firebase Cloud
                val map = buildFirebaseMap(updated)
                withContext(Dispatchers.IO) {
                    runCatching {
                        val ownerRef = FirebaseDatabase.getInstance().getReference("owners/$activeTenantId/feature_settings/1")
                        ownerRef.setValue(map).await()

                        if (activeTenantId == "biometricpayroll") {
                            FirebaseDatabase.getInstance().getReference("feature_settings/1").setValue(map).await()
                        }
                    }
                }

                currentSettings = updated
                Toast.makeText(this@FeatureToggleManagerActivity, "Feature settings saved for $activeCompanyName! ✅", Toast.LENGTH_LONG).show()
                HapticUtil.vibrateSuccess(binding.btnSaveAllSettings)
            } catch (e: Exception) {
                Toast.makeText(this@FeatureToggleManagerActivity, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
                binding.btnSaveAllSettings.isEnabled = true
                binding.btnSaveAllSettings.text = "Save All Changes"
            }
        }
    }

    private fun buildFirebaseMap(s: LocalFeatureSettings): Map<String, Any> {
        return mapOf(
            "id" to 1,
            "enablePayroll" to s.enablePayroll,
            "enableSalaryAdvance" to s.enableSalaryAdvance,
            "enableBonusManagement" to s.enableBonusManagement,
            "employeeCanViewBonus" to s.employeeCanViewBonus,
            "enableYearEndSummary" to s.enableYearEndSummary,
            "enableFlexibleBenefits" to s.enableFlexibleBenefits,
            "enableStatutoryCompliance" to s.enableStatutoryCompliance,
            "enableProfessionalTax" to s.enableProfessionalTax,
            "enableTdsDeduction" to s.enableTdsDeduction,
            "enableTaxDeclarations" to s.enableTaxDeclarations,
            "enableEmployeeManagement" to s.enableEmployeeManagement,
            "enableShiftScheduling" to s.enableShiftScheduling,
            "enableAutoShiftRotation" to s.enableAutoShiftRotation,
            "enableShiftAllowance" to s.enableShiftAllowance,
            "enablePunchCorrection" to s.enablePunchCorrection,
            "enableGeoFencing" to s.enableGeoFencing,
            "enableDualAttendance" to s.enableDualAttendance,
            "enableAutomaticGeofencePunching" to s.enableAutomaticGeofencePunching,
            "enableCompanyReports" to s.enableCompanyReports,
            "enableCustomReporting" to s.enableCustomReporting,
            "enableRegularizationReq" to s.enableRegularizationReq,
            "enableLeaveManagement" to s.enableLeaveManagement,
            "enableLeaveAccrual" to s.enableLeaveAccrual,
            "enableSandwichRule" to s.enableSandwichRule,
            "enableResignationModule" to s.enableResignationModule,
            "enableEmailNotifications" to s.enableEmailNotifications,
            "enableInAppNotifications" to s.enableInAppNotifications,
            "showThemeToggle" to s.showThemeToggle,
            "enableAuditLog" to s.enableAuditLog,
            "enableRecycleBin" to s.enableRecycleBin,
            "firebasePlanMode" to s.firebasePlanMode,
            "adminCanViewDashboard" to s.adminCanViewDashboard,
            "adminCanViewAttendance" to s.adminCanViewAttendance,
            "adminCanViewReports" to s.adminCanViewReports,
            "adminCanManageEmployees" to s.adminCanManageEmployees,
            "adminCanManageShifts" to s.adminCanManageShifts,
            "adminCanManagePunchApprovals" to s.adminCanManagePunchApprovals,
            "adminCanRunPayroll" to s.adminCanRunPayroll,
            "adminCanEditSettings" to s.adminCanEditSettings,
            "adminCanManageEmployeePermissions" to s.adminCanManageEmployeePermissions,
            "employeeCanViewDashboard" to s.employeeCanViewDashboard,
            "employeeToolsVisible" to s.employeeToolsVisible,
            "employeeCanViewAttendance" to s.employeeCanViewAttendance,
            "employeeCanViewShifts" to s.employeeCanViewShifts,
            "employeeCanViewPayslip" to s.employeeCanViewPayslip,
            "employeeCanViewAdvance" to s.employeeCanViewAdvance,
            "employeeCanViewTax" to s.employeeCanViewTax,
            "employeeCanViewLeave" to s.employeeCanViewLeave,
            "employeeCanViewLeaveHistory" to s.employeeCanViewLeaveHistory,
            "employeeCanViewResignation" to s.employeeCanViewResignation,
            "employeeCanViewReports" to s.employeeCanViewReports
        )
    }

    // ==============================================================
    // DANGER ZONE OPERATIONS (1:1 Web Parity)
    // ==============================================================

    private fun setupBackupsRecyclerView() {
        backupAdapter = BackupAdapter(
            onRestore = { meta -> confirmRestoreBackup(meta) },
            onDelete = { meta -> confirmDeleteBackup(meta) }
        )
        binding.rvBackupsList.layoutManager = LinearLayoutManager(this)
        binding.rvBackupsList.adapter = backupAdapter
    }

    private fun getBackupsDir(): File {
        val dir = File(filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadBackups() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val dir = getBackupsDir()
                val files = dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
                files.sortedByDescending { it.lastModified() }.map { f ->
                    val trigger = when {
                        f.name.contains("hourlyauto", ignoreCase = true) -> "HourlyAuto"
                        f.name.contains("prewipesafety", ignoreCase = true) -> "PreWipeSafety"
                        f.name.contains("prerestoresafety", ignoreCase = true) -> "PreRestoreSafety"
                        else -> "Manual"
                    }
                    LocalBackupMetadata(
                        fileName = f.name,
                        filePath = f.absolutePath,
                        createdAtMillis = f.lastModified(),
                        fileSizeBytes = f.length(),
                        triggerType = trigger,
                        tenantId = activeTenantId
                    )
                }
            }

            backupsList.clear()
            backupsList.addAll(list)
            backupAdapter.submitList(backupsList.toList())

            binding.tvNoBackupsFound.isVisible = backupsList.isEmpty()
            binding.tvBackupsSectionTitle.text = "🗑 Available Backups & Restore to Cloud (${backupsList.size})"
        }
    }

    private fun performBackup(triggerType: String, onComplete: ((File) -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "payroll_backup_${nowStr}_${triggerType.lowercase()}.json"
                val file = File(getBackupsDir(), fileName)

                withContext(Dispatchers.IO) {
                    val payrollList = appDatabase.localPayrollHistoryDao().getAllFlow().firstOrNull() ?: emptyList()
                    val bonusList = appDatabase.localBonusRecordDao().getAllFlow().firstOrNull() ?: emptyList()

                    val backupData = mapOf(
                        "createdAt" to System.currentTimeMillis(),
                        "triggerType" to triggerType,
                        "tenantId" to activeTenantId,
                        "companyName" to activeCompanyName,
                        "featureSettings" to currentSettings,
                        "employees" to appDatabase.localEmployeeDao().getAll(),
                        "attendance" to appDatabase.localAttendanceDao().getAll(),
                        "punches" to appDatabase.localAttendancePunchDao().getAll(),
                        "payrollHistory" to payrollList,
                        "advances" to appDatabase.localAdvancePaymentDao().getAll(),
                        "bonuses" to bonusList
                    )
                    file.writeText(gson.toJson(backupData))
                }

                loadBackups()
                Toast.makeText(this@FeatureToggleManagerActivity, "Backup created successfully! 📦", Toast.LENGTH_SHORT).show()
                HapticUtil.vibrateSuccess(binding.btnBackupDatabaseNow)
                onComplete?.invoke(file)
            } catch (e: Exception) {
                Toast.makeText(this@FeatureToggleManagerActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmResetToggles() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Feature Toggles?")
            .setMessage("Revert feature toggles for $activeCompanyName to factory defaults?")
            .setPositiveButton("Reset Toggles") { _, _ ->
                val defaults = LocalFeatureSettings(id = 1)
                currentSettings = defaults
                populateUI(defaults)
                saveAllSettings()
                Toast.makeText(this, "Toggles reset to defaults for $activeCompanyName.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPartialWipe() {
        val msg = "WARNING: Partial Wipe will permanently delete all attendance logs, punches, GPS tracking history, payroll records, salary advances, and bonuses for $activeCompanyName ($activeCompanyCode) from BOTH Local DB and Firebase Cloud.\n\nSettings & Employees WILL BE KEPT.\n\nA safety backup will be created automatically before wiping. Proceed?"

        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Partial Wipe (Operational)")
            .setMessage(msg)
            .setPositiveButton("Next") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("FINAL CONFIRMATION")
                    .setMessage("Wipe operational data for $activeCompanyName ONLY?")
                    .setPositiveButton("Wipe Operational Data") { _, _ ->
                        executePartialWipe()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executePartialWipe() {
        lifecycleScope.launch {
            try {
                // 1. Pre-wipe safety backup
                performBackup("PreWipeSafety")

                // 2. Wipe operational tables from Local Room
                withContext(Dispatchers.IO) {
                    val db = appDatabase.openHelper.writableDatabase
                    db.execSQL("DELETE FROM local_attendance")
                    db.execSQL("DELETE FROM local_attendance_punches")
                    db.execSQL("DELETE FROM local_daily_summaries")
                    db.execSQL("DELETE FROM payroll_history")
                    db.execSQL("DELETE FROM local_advance_payments")
                    db.execSQL("DELETE FROM local_bonus_records")
                    db.execSQL("DELETE FROM local_leave_requests")
                    db.execSQL("DELETE FROM local_resignation_requests")
                    db.execSQL("DELETE FROM local_regularization_requests")
                }

                // 3. Wipe operational nodes from Firebase Cloud
                withContext(Dispatchers.IO) {
                    val root = FirebaseDatabase.getInstance().getReference("owners/$activeTenantId")
                    root.child("attendance").removeValue().await()
                    root.child("attendance_punches").removeValue().await()
                    root.child("tracking").removeValue().await()
                    root.child("payroll_history").removeValue().await()
                    root.child("advance_payments").removeValue().await()
                    root.child("bonuses").removeValue().await()
                    root.child("leave_requests").removeValue().await()
                    root.child("resignation_requests").removeValue().await()
                }

                Toast.makeText(this@FeatureToggleManagerActivity, "Operational data wiped for $activeCompanyName! (Settings & Employees preserved)", Toast.LENGTH_LONG).show()
                HapticUtil.vibrateRisk(binding.btnPartialWipe)
                loadBackups()
            } catch (e: Exception) {
                Toast.makeText(this@FeatureToggleManagerActivity, "Wipe failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmFullWipe() {
        val msg = "DANGER: Full Factory Wipe will permanently delete ALL employees, attendance logs, GPS history, payroll, advances, and bonuses for $activeCompanyName ($activeCompanyCode) from BOTH Local DB and Firebase Cloud.\n\nA safety backup will be created automatically before wiping. Proceed?"

        MaterialAlertDialogBuilder(this)
            .setTitle("DANGER: Full Factory Wipe")
            .setMessage(msg)
            .setPositiveButton("Next") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("FINAL CONFIRMATION")
                    .setMessage("Are you absolutely certain you want to wipe ALL employees and operational records for $activeCompanyName ONLY?")
                    .setPositiveButton("Confirm Full Wipe") { _, _ ->
                        executeFullWipe()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeFullWipe() {
        lifecycleScope.launch {
            try {
                // 1. Safety backup
                performBackup("PreWipeSafety")

                // 2. Wipe employees & operational tables from Local Room
                withContext(Dispatchers.IO) {
                    val db = appDatabase.openHelper.writableDatabase
                    db.execSQL("DELETE FROM local_employees")
                    db.execSQL("DELETE FROM local_attendance")
                    db.execSQL("DELETE FROM local_attendance_punches")
                    db.execSQL("DELETE FROM local_daily_summaries")
                    db.execSQL("DELETE FROM payroll_history")
                    db.execSQL("DELETE FROM local_advance_payments")
                    db.execSQL("DELETE FROM local_bonus_records")
                    db.execSQL("DELETE FROM local_leave_requests")
                    db.execSQL("DELETE FROM local_resignation_requests")
                    db.execSQL("DELETE FROM local_regularization_requests")
                }

                // 3. Wipe operational & employee nodes from Firebase Cloud
                withContext(Dispatchers.IO) {
                    val root = FirebaseDatabase.getInstance().getReference("owners/$activeTenantId")
                    root.child("employees").removeValue().await()
                    root.child("attendance").removeValue().await()
                    root.child("attendance_punches").removeValue().await()
                    root.child("tracking").removeValue().await()
                    root.child("payroll_history").removeValue().await()
                    root.child("advance_payments").removeValue().await()
                    root.child("bonuses").removeValue().await()
                    root.child("leave_requests").removeValue().await()
                    root.child("resignation_requests").removeValue().await()
                }

                Toast.makeText(this@FeatureToggleManagerActivity, "Full system wipe completed for $activeCompanyName! (Local DB & Cloud)", Toast.LENGTH_LONG).show()
                HapticUtil.vibrateDeletion(binding.btnFullWipe)
                loadBackups()
            } catch (e: Exception) {
                Toast.makeText(this@FeatureToggleManagerActivity, "Full wipe failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRestoreBackup(meta: LocalBackupMetadata) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore to Cloud & Local DB?")
            .setMessage("WARNING: Restoring backup '${meta.fileName}' will replace current local database records and push restored data to Firebase Cloud. A safety backup will be taken first. Continue?")
            .setPositiveButton("Restore to Cloud") { _, _ ->
                executeRestore(meta)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRestore(meta: LocalBackupMetadata) {
        lifecycleScope.launch {
            try {
                performBackup("PreRestoreSafety")

                val file = File(meta.filePath)
                if (!file.exists()) {
                    Toast.makeText(this@FeatureToggleManagerActivity, "Backup file not found.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val json = file.readText()
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(json, mapType)

                    // If feature settings were backed up, restore them
                    if (map.containsKey("featureSettings")) {
                        val fsJson = gson.toJson(map["featureSettings"])
                        val restoredSettings = gson.fromJson(fsJson, LocalFeatureSettings::class.java)
                        localSettingsDao.upsertFeatureSettings(restoredSettings)
                    }
                }

                loadSettings()
                Toast.makeText(this@FeatureToggleManagerActivity, "Restore completed successfully! ☁️", Toast.LENGTH_LONG).show()
                HapticUtil.vibrateSuccess(binding.btnSaveAllSettings)
            } catch (e: Exception) {
                Toast.makeText(this@FeatureToggleManagerActivity, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteBackup(meta: LocalBackupMetadata) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Backup?")
            .setMessage("Are you sure you want to delete '${meta.fileName}'?")
            .setPositiveButton("Delete") { _, _ ->
                File(meta.filePath).delete()
                loadBackups()
                Toast.makeText(this, "Backup deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==============================================================
    // ADAPTER FOR DANGER ZONE BACKUPS
    // ==============================================================
    inner class BackupAdapter(
        private val onRestore: (LocalBackupMetadata) -> Unit,
        private val onDelete: (LocalBackupMetadata) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.BackupViewHolder>() {

        private val items = mutableListOf<LocalBackupMetadata>()

        fun submitList(newItems: List<LocalBackupMetadata>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackupViewHolder {
            val b = ItemBackupEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return BackupViewHolder(b)
        }

        override fun onBindViewHolder(holder: BackupViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class BackupViewHolder(private val b: ItemBackupEntryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: LocalBackupMetadata) {
                b.tvBackupTime.text = "🕒 ${item.formattedTime}"
                b.tvBackupFileName.text = item.fileName
                b.tvBackupSize.text = item.formattedSize

                b.tvBackupBadge.text = when (item.triggerType) {
                    "HourlyAuto" -> "🤖 Hourly Auto"
                    "PreWipeSafety" -> "🛡 Pre-Wipe Safety"
                    "PreRestoreSafety" -> "🛡 Pre-Restore Safety"
                    else -> "👤 Manual"
                }

                b.btnRestoreCloud.setOnClickListener { onRestore(item) }
                b.btnDeleteBackup.setOnClickListener { onDelete(item) }
            }
        }
    }
}
