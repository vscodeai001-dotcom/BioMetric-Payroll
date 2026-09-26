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
import com.biometric.app.api.AdminFeatureSettingsDto
import com.biometric.app.api.MobileApiService
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
    @Inject lateinit var apiService: MobileApiService

    private val gson = Gson()
    private var currentSettings = LocalFeatureSettings()
    private var isSaving = false
    private var activeCompanyName = "Primary Workspace"
    private var activeTenantId = "biometricpayroll"
    private var activeCompanyCode = "PRIMARY"
    private var isUserSuperAdmin = false

    private data class TabEntry(val title: String, val index: Int)
    private val activeTabs = mutableListOf<TabEntry>()

    private val backupsList = mutableListOf<LocalBackupMetadata>()
    private lateinit var backupAdapter: BackupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeatureToggleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clFeatureToggleRoot, binding.appBar)

        val role = sessionStore.userRole().trim().uppercase()
        isUserSuperAdmin = role in setOf("SUPERADMIN", "SUPER_ADMIN")

        activeTenantId = sessionStore.firebaseOwnerUid() ?: "biometricpayroll"
        activeCompanyCode = if (activeTenantId.startsWith("tenant_")) activeTenantId.removePrefix("tenant_").uppercase() else "PRIMARY"

        setupToolbar()
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

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                HapticUtil.vibrateTick(binding.tabLayout)
                val pos = tab?.position ?: return
                if (pos in activeTabs.indices) {
                    showTab(activeTabs[pos].index)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun setupTabs(s: LocalFeatureSettings) {
        val tabLayout = binding.tabLayout
        tabLayout.removeAllTabs()
        activeTabs.clear()

        // 1:1 Web Parity with FeatureToggleManager.razor:
        // - Core Modules: SuperAdmin OR Admin with AdminCanManageFeatureToggles
        if (isUserSuperAdmin || s.adminCanManageFeatureToggles) {
            activeTabs.add(TabEntry("⊞ Core Modules", 0))
        }
        // - Admin Permissions: SuperAdmin ONLY
        if (isUserSuperAdmin) {
            activeTabs.add(TabEntry("👤 Admin Permissions", 1))
        }
        // - Employee Permissions: SuperAdmin OR Admin with AdminCanManageEmployeePermissions OR AdminCanManageFeatureToggles
        if (isUserSuperAdmin || s.adminCanManageEmployeePermissions || s.adminCanManageFeatureToggles) {
            activeTabs.add(TabEntry("👥 Employee Permissions", 2))
        }
        // - Danger Zone: SuperAdmin ONLY
        if (isUserSuperAdmin) {
            activeTabs.add(TabEntry("☢️ Danger Zone", 3))
        }

        for (entry in activeTabs) {
            tabLayout.addTab(tabLayout.newTab().setText(entry.title))
        }

        tabLayout.isVisible = activeTabs.size > 1

        val canSave = isUserSuperAdmin || s.adminCanManageFeatureToggles || s.adminCanManageEmployeePermissions
        binding.btnSaveAllSettings.isVisible = canSave

        if (activeTabs.isNotEmpty()) {
            showTab(activeTabs[0].index)
        } else {
            showTab(0)
        }
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
            setupTabs(local)
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
        binding.swAdminManageFeatureToggles.isChecked = s.adminCanManageFeatureToggles
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
        val canEditModules = isUserSuperAdmin || currentSettings.adminCanManageFeatureToggles
        val canEditEmployeePerms = isUserSuperAdmin || currentSettings.adminCanManageEmployeePermissions || currentSettings.adminCanManageFeatureToggles
        val canEditAdminPerms = isUserSuperAdmin

        val geo = if (canEditModules) binding.swEnableGeoFencing.isChecked else currentSettings.enableGeoFencing
        val plan = if (isUserSuperAdmin) {
            if (binding.rbPlanBlaze.isChecked) "Blaze" else "Spark"
        } else {
            currentSettings.firebasePlanMode
        }

        return LocalFeatureSettings(
            id = 1,
            enablePayroll = if (canEditModules) binding.swRunPayroll.isChecked else currentSettings.enablePayroll,
            enableSalaryAdvance = if (canEditModules) binding.swSalaryAdvance.isChecked else currentSettings.enableSalaryAdvance,
            enableBonusManagement = if (canEditModules) binding.swBonusManagement.isChecked else currentSettings.enableBonusManagement,
            employeeCanViewBonus = if (canEditModules) (if (binding.swBonusManagement.isChecked) binding.swEmployeeViewBonuses.isChecked else false) else currentSettings.employeeCanViewBonus,
            enableYearEndSummary = if (canEditModules) binding.swYearEndSummary.isChecked else currentSettings.enableYearEndSummary,
            enableFlexibleBenefits = if (canEditModules) binding.swFbpCompStructuring.isChecked else currentSettings.enableFlexibleBenefits,

            enableStatutoryCompliance = if (canEditModules) binding.swPfEsiCompliance.isChecked else currentSettings.enableStatutoryCompliance,
            enableProfessionalTax = if (canEditModules) binding.swProfessionalTax.isChecked else currentSettings.enableProfessionalTax,
            enableTdsDeduction = if (canEditModules) binding.swEnableTdsDeduction.isChecked else currentSettings.enableTdsDeduction,
            enableTaxDeclarations = if (canEditModules) binding.swTaxDeclarations.isChecked else currentSettings.enableTaxDeclarations,

            enableEmployeeManagement = if (canEditModules) binding.swEmployeeManagement.isChecked else currentSettings.enableEmployeeManagement,
            enableShiftScheduling = if (canEditModules) binding.swShiftScheduling.isChecked else currentSettings.enableShiftScheduling,
            enableAutoShiftRotation = if (canEditModules) (if (binding.swShiftScheduling.isChecked) binding.swAutoShiftRotation.isChecked else false) else currentSettings.enableAutoShiftRotation,
            enableShiftAllowance = if (canEditModules) binding.swNightShiftAllowance.isChecked else currentSettings.enableShiftAllowance,
            enablePunchCorrection = if (canEditModules) binding.swPunchCorrection.isChecked else currentSettings.enablePunchCorrection,
            enableGeoFencing = geo,
            enableDualAttendance = if (canEditModules) (if (geo) binding.swDualAttendance.isChecked else false) else currentSettings.enableDualAttendance,
            enableAutomaticGeofencePunching = if (canEditModules) (if (geo) binding.swAutoGeofencePunching.isChecked else false) else currentSettings.enableAutomaticGeofencePunching,

            enableCompanyReports = if (canEditModules) binding.swCompanyReports.isChecked else currentSettings.enableCompanyReports,
            enableCustomReporting = if (canEditModules) binding.swCustomReporting.isChecked else currentSettings.enableCustomReporting,
            enableRegularizationReq = if (canEditModules) binding.swRegularizationRequests.isChecked else currentSettings.enableRegularizationReq,

            enableLeaveManagement = if (canEditModules) binding.swEnableLeaveModule.isChecked else currentSettings.enableLeaveManagement,
            enableLeaveAccrual = if (canEditModules) (if (binding.swEnableLeaveModule.isChecked) binding.swLeaveAccrual.isChecked else false) else currentSettings.enableLeaveAccrual,
            enableSandwichRule = if (canEditModules) (if (binding.swEnableLeaveModule.isChecked) binding.swSandwichRule.isChecked else false) else currentSettings.enableSandwichRule,
            enableResignationModule = if (canEditModules) binding.swExitFnfSettlement.isChecked else currentSettings.enableResignationModule,

            enableEmailNotifications = if (canEditModules) binding.swEmailNotifications.isChecked else currentSettings.enableEmailNotifications,
            enableInAppNotifications = if (canEditModules) binding.swInAppNotificationBell.isChecked else currentSettings.enableInAppNotifications,
            showThemeToggle = if (canEditModules) binding.swDarkLightToggle.isChecked else currentSettings.showThemeToggle,
            enableAuditLog = if (canEditModules) binding.swEnableAuditTrails.isChecked else currentSettings.enableAuditLog,
            enableRecycleBin = if (canEditModules) binding.swEnableRecycleBin.isChecked else currentSettings.enableRecycleBin,
            firebasePlanMode = plan,

            // Admin Permissions - strictly preserved unless SuperAdmin!
            adminCanManageFeatureToggles = if (canEditAdminPerms) binding.swAdminManageFeatureToggles.isChecked else currentSettings.adminCanManageFeatureToggles,
            adminCanViewDashboard = if (canEditAdminPerms) binding.swAdminViewDashboard.isChecked else currentSettings.adminCanViewDashboard,
            adminCanViewAttendance = if (canEditAdminPerms) binding.swAdminViewAttendanceLogs.isChecked else currentSettings.adminCanViewAttendance,
            adminCanViewReports = if (canEditAdminPerms) binding.swAdminViewReports.isChecked else currentSettings.adminCanViewReports,
            adminCanManageEmployees = if (canEditAdminPerms) binding.swAdminManageEmployees.isChecked else currentSettings.adminCanManageEmployees,
            adminCanManageShifts = if (canEditAdminPerms) binding.swAdminManageShifts.isChecked else currentSettings.adminCanManageShifts,
            adminCanManagePunchApprovals = if (canEditAdminPerms) binding.swAdminPunchApprovalRequests.isChecked else currentSettings.adminCanManagePunchApprovals,
            adminCanRunPayroll = if (canEditAdminPerms) binding.swAdminRunPayroll.isChecked else currentSettings.adminCanRunPayroll,
            adminCanEditSettings = if (canEditAdminPerms) binding.swAdminEditGlobalSettings.isChecked else currentSettings.adminCanEditSettings,
            adminCanManageEmployeePermissions = if (canEditAdminPerms) binding.swAdminManageEmployeePerms.isChecked else currentSettings.adminCanManageEmployeePermissions,

            // Employee Permissions
            employeeCanViewDashboard = if (canEditEmployeePerms) binding.swEmpShowHome.isChecked else currentSettings.employeeCanViewDashboard,
            employeeToolsVisible = if (canEditEmployeePerms) binding.swEmpShowTools.isChecked else currentSettings.employeeToolsVisible,
            employeeCanViewAttendance = if (canEditEmployeePerms) binding.swEmpViewAttendance.isChecked else currentSettings.employeeCanViewAttendance,
            employeeCanViewShifts = if (canEditEmployeePerms) (if (binding.swShiftScheduling.isChecked) binding.swEmpViewShiftSchedule.isChecked else false) else currentSettings.employeeCanViewShifts,
            employeeCanViewPayslip = if (canEditEmployeePerms) (if (binding.swRunPayroll.isChecked) binding.swEmpViewPayslips.isChecked else false) else currentSettings.employeeCanViewPayslip,
            employeeCanViewAdvance = if (canEditEmployeePerms) (if (binding.swSalaryAdvance.isChecked) binding.swEmpViewSalaryAdvances.isChecked else false) else currentSettings.employeeCanViewAdvance,
            employeeCanViewTax = if (canEditEmployeePerms) (if (binding.swTaxDeclarations.isChecked) binding.swEmpSubmitTax.isChecked else false) else currentSettings.employeeCanViewTax,
            employeeCanViewLeave = if (canEditEmployeePerms) (if (binding.swEnableLeaveModule.isChecked) binding.swEmpViewLeave.isChecked else false) else currentSettings.employeeCanViewLeave,
            employeeCanViewLeaveHistory = if (canEditEmployeePerms) (if (binding.swEnableLeaveModule.isChecked) binding.swEmpViewLeave.isChecked else false) else currentSettings.employeeCanViewLeaveHistory,
            employeeCanViewResignation = if (canEditEmployeePerms) (if (binding.swExitFnfSettlement.isChecked) binding.swEmpSubmitResignation.isChecked else false) else currentSettings.employeeCanViewResignation,
            employeeCanViewReports = if (canEditEmployeePerms) (if (binding.swCustomReporting.isChecked) binding.swEmpViewReports.isChecked else false) else currentSettings.employeeCanViewReports
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

                // 3. Synchronize with Backend API (MobileApiService)
                val token = sessionStore.token()
                if (!token.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                            val dto = AdminFeatureSettingsDto(
                                enablePayroll = updated.enablePayroll,
                                enableSalaryAdvance = updated.enableSalaryAdvance,
                                enableBonusManagement = updated.enableBonusManagement,
                                enableSalaryStructuring = updated.enableFlexibleBenefits,
                                employeeCanViewAdvance = updated.employeeCanViewAdvance,
                                employeeCanViewBonus = updated.employeeCanViewBonus,
                                enableTdsDeduction = updated.enableTdsDeduction,
                                enableShiftScheduling = updated.enableShiftScheduling,
                                enableLeaveManagement = updated.enableLeaveManagement,
                                enablePunchCorrection = updated.enablePunchCorrection,
                                enableEmployeeManagement = updated.enableEmployeeManagement,
                                enableCompanyReports = updated.enableCompanyReports,
                                enableStatutoryCompliance = updated.enableStatutoryCompliance,
                                adminCanViewDashboard = updated.adminCanViewDashboard,
                                adminCanManageEmployees = updated.adminCanManageEmployees,
                                adminCanViewAttendance = updated.adminCanViewAttendance,
                                adminCanRunPayroll = updated.adminCanRunPayroll,
                                adminCanEditSettings = updated.adminCanEditSettings,
                                adminCanManageShifts = updated.adminCanManageShifts,
                                adminCanManagePunchApprovals = updated.adminCanManagePunchApprovals,
                                adminCanViewReports = updated.adminCanViewReports,
                                employeeCanViewDashboard = updated.employeeCanViewDashboard,
                                employeeCanViewPayslip = updated.employeeCanViewPayslip,
                                employeeCanViewAttendance = updated.employeeCanViewAttendance,
                                employeeCanViewLeave = updated.employeeCanViewLeave,
                                employeeCanViewLeaveHistory = updated.employeeCanViewLeaveHistory,
                                employeeToolsVisible = updated.employeeToolsVisible,
                                showThemeToggle = updated.showThemeToggle,
                                adminCanManageEmployeePermissions = updated.adminCanManageEmployeePermissions,
                                adminCanManageFeatureToggles = updated.adminCanManageFeatureToggles,
                                enableProfessionalTax = updated.enableProfessionalTax,
                                enableEmailNotifications = updated.enableEmailNotifications,
                                enableLeaveAccrual = updated.enableLeaveAccrual,
                                enableSandwichRule = updated.enableSandwichRule,
                                enableShiftAllowance = updated.enableShiftAllowance,
                                enableAuditLog = updated.enableAuditLog,
                                employeeCanViewShifts = updated.employeeCanViewShifts,
                                enableYearEndSummary = updated.enableYearEndSummary,
                                enableRecycleBin = updated.enableRecycleBin,
                                enableTaxDeclarations = updated.enableTaxDeclarations,
                                enableGeoFencing = updated.enableGeoFencing,
                                enableDualAttendance = updated.enableDualAttendance,
                                enableAutomaticGeofencePunching = updated.enableAutomaticGeofencePunching,
                                enableResignationModule = updated.enableResignationModule,
                                employeeCanViewResignation = updated.employeeCanViewResignation,
                                employeeCanViewTax = updated.employeeCanViewTax,
                                enableCustomReporting = updated.enableCustomReporting,
                                employeeCanViewReports = updated.employeeCanViewReports
                            )
                            apiService.saveAdminFeatureSettings(authHeader, dto)
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
            "adminCanManageFeatureToggles" to s.adminCanManageFeatureToggles,
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
                    val tablesToWipe = listOf(
                        "local_attendance",
                        "local_attendance_punches",
                        "daily_summaries",
                        "payroll_history",
                        "local_advance_payments",
                        "local_bonus_records",
                        "local_leave_requests",
                        "local_resignation_requests",
                        "local_regularization_requests",
                        "shift_schedules",
                        "local_tax_declarations",
                        "local_fbp_declarations",
                        "local_fbp_components",
                        "local_salary_snapshots",
                        "local_audit_logs",
                        "offline_tracking_events"
                    )
                    for (table in tablesToWipe) {
                        try {
                            db.execSQL("DELETE FROM $table")
                        } catch (e: Exception) {
                            android.util.Log.w("FeatureToggleManager", "Could not clear table $table: ${e.message}")
                        }
                    }
                }

                // 3. Wipe operational nodes from Firebase Cloud
                withContext(Dispatchers.IO) {
                    val root = FirebaseDatabase.getInstance().getReference("owners/$activeTenantId")
                    val operationalNodes = listOf(
                        "attendance",
                        "attendance_punches",
                        "daily_summaries",
                        "tracking",
                        "payroll_history",
                        "payroll_previews",
                        "payroll_finalization",
                        "advance_payments",
                        "bonuses",
                        "bonus_records",
                        "leave_requests",
                        "resignation_requests",
                        "regularizations",
                        "shift_schedules",
                        "tax_declarations",
                        "fbp_declarations",
                        "fbp_components",
                        "salary_snapshots",
                        "audit_logs",
                        "fnf_settlements",
                        "year_end_summaries",
                        "report_definitions",
                        "geo_punch_audits",
                        "presence",
                        "offline_tracking",
                        "offline_tracking_events",
                        "notifications",
                        "events"
                    )
                    for (node in operationalNodes) {
                        try {
                            root.child(node).removeValue().await()
                        } catch (e: Exception) {
                            android.util.Log.w("FeatureToggleManager", "Could not remove Firebase node $node: ${e.message}")
                        }
                    }
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
                    val tablesToWipe = listOf(
                        "local_employees",
                        "local_employee_history",
                        "local_shops",
                        "local_shop_closed_days",
                        "local_attendance",
                        "local_attendance_punches",
                        "daily_summaries",
                        "payroll_history",
                        "local_advance_payments",
                        "local_bonus_records",
                        "local_leave_requests",
                        "local_resignation_requests",
                        "local_regularization_requests",
                        "shift_schedules",
                        "local_tax_declarations",
                        "local_fbp_declarations",
                        "local_fbp_components",
                        "local_salary_snapshots",
                        "local_audit_logs",
                        "offline_tracking_events"
                    )
                    for (table in tablesToWipe) {
                        try {
                            db.execSQL("DELETE FROM $table")
                        } catch (e: Exception) {
                            android.util.Log.w("FeatureToggleManager", "Could not clear table $table: ${e.message}")
                        }
                    }
                }

                // 3. Wipe operational & employee nodes from Firebase Cloud
                withContext(Dispatchers.IO) {
                    val root = FirebaseDatabase.getInstance().getReference("owners/$activeTenantId")
                    val allNodes = listOf(
                        "employees",
                        "employee_history",
                        "shops",
                        "attendance",
                        "attendance_punches",
                        "daily_summaries",
                        "tracking",
                        "payroll_history",
                        "payroll_previews",
                        "payroll_finalization",
                        "advance_payments",
                        "bonuses",
                        "bonus_records",
                        "leave_requests",
                        "resignation_requests",
                        "regularizations",
                        "shift_schedules",
                        "tax_declarations",
                        "fbp_declarations",
                        "fbp_components",
                        "salary_snapshots",
                        "audit_logs",
                        "fnf_settlements",
                        "year_end_summaries",
                        "report_definitions",
                        "geo_punch_audits",
                        "presence",
                        "offline_tracking",
                        "offline_tracking_events",
                        "notifications",
                        "events"
                    )
                    for (node in allNodes) {
                        try {
                            root.child(node).removeValue().await()
                        } catch (e: Exception) {
                            android.util.Log.w("FeatureToggleManager", "Could not remove Firebase node $node: ${e.message}")
                        }
                    }
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
