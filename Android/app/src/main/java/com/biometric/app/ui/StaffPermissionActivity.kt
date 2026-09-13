package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.databinding.ActivityStaffPermissionBinding
import com.biometric.app.domain.FeatureManager
import com.biometric.app.ui.adapter.FeatureToggleAdapter
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class StaffPermissionActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityStaffPermissionBinding
    @Inject lateinit var sharedViewModel: SharedViewModel

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var featureManager: FeatureManager

    private var selectedStaffProfile: UserProfile? = null
    private lateinit var featureAdapter: FeatureToggleAdapter
    private var adminFeatures: Set<FeatureManager.Feature> = emptySet()
    
    private var allActiveEmployees: List<Employee> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        
        binding = ActivityStaffPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupUI()
        loadAdminPermissions()
        observeViewModel()
        
        setupMotionFeedback(binding.btnSaveStaffPermissions, binding.tilStaffSelection)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        updateToolbarTitle("Shop")
    }

    private fun updateToolbarTitle(shopName: String) {
        setupDualHeader(binding.toolbar, shopName, "Staff Feature Control")
    }

    private fun setupUI() {
        binding.rvFeatureToggles.layoutManager = LinearLayoutManager(this)
        binding.btnSaveStaffPermissions.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            savePermissions() 
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            sharedViewModel.selectedShop.collectLatest { shop ->
                shop?.let {
                    updateToolbarTitle(it.name)
                    loadStaffList(it.shopId)
                }
            }
        }
    }

    private fun loadAdminPermissions() {
        lifecycleScope.launch {
            val adminProfile = sharedViewModel.userProfile.first()
            adminFeatures = if (adminProfile?.enabledFeatures == null) {
                FeatureManager.Feature.entries.toSet()
            } else {
                FeatureManager.Feature.entries.asSequence().filter { it.name in adminProfile.enabledFeatures!! }.toSet()
            }
            
            // Initial empty adapter
            featureAdapter = FeatureToggleAdapter(adminFeatures.toList(), emptySet()) { _, _ -> }
            binding.rvFeatureToggles.adapter = featureAdapter
        }
    }

    private fun loadStaffList(shopId: String) {
        lifecycleScope.launch {
            repository.getShopEmployees(shopId).collectLatest { employees ->
                // REQUIREMENT: Only list ACTIVE staff (not terminated)
                allActiveEmployees = employees.filter { it.isActive && it.terminateDate == null }
                updateStaffDropdown()
            }
        }
    }

    private fun updateStaffDropdown() {
        val names = allActiveEmployees.map { it.name }
        val adapter = ArrayAdapter(this, R.layout.item_simple_dropdown, names)
        binding.actvStaffName.setAdapter(adapter)
        
        binding.actvStaffName.setOnItemClickListener { _, _, position, _ ->
            val emp = allActiveEmployees[position]
            fetchStaffProfile(emp)
        }
        
        if (allActiveEmployees.isEmpty()) {
            binding.actvStaffName.setText("No Active Staff Found", false)
        } else {
            binding.actvStaffName.setText("Select Staff Member", false)
        }
    }

    private fun fetchStaffProfile(employee: Employee) {
        lifecycleScope.launch {
            try {
                PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.STAFF, lifecycleScope, immediate = true)
                
                val profile = repository.fetchProfileByPhone(employee.phone)
                PremiumLoader.hide(binding.brewingLoader)

                if (profile != null) {
                    selectedStaffProfile = profile
                    binding.tvConfiguringStaff.text = "Configuring: ${profile.name} 🛡️"
                    binding.tvStaffProfileStatus.text = "Profile linked successfully. Update access below."
                    binding.tvStaffProfileStatus.setTextColor(ContextCompat.getColor(this@StaffPermissionActivity, R.color.green))
                    updateFeatureToggles(profile)
                } else {
                    selectedStaffProfile = null
                    binding.tvConfiguringStaff.text = "Profile Missing ⚠️"
                    binding.tvStaffProfileStatus.text = "This staff member (${employee.name}) has not logged into the app yet."
                    binding.tvStaffProfileStatus.setTextColor(ContextCompat.getColor(this@StaffPermissionActivity, R.color.red))
                    
                    // Reset toggles to empty
                    featureAdapter = FeatureToggleAdapter(adminFeatures.toList(), emptySet()) { _, _ -> }
                    binding.rvFeatureToggles.adapter = featureAdapter
                    
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@StaffPermissionActivity)
                        .setTitle("Login Required ⚠️")
                        .setMessage("Permissions can only be managed for staff who have logged into the app at least once.\n\nPlease ask ${employee.name} to login first.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                PremiumLoader.hide(binding.brewingLoader)
                Log.e("StaffPermission", "Fetch failed", e)
                Toast.makeText(this@StaffPermissionActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFeatureToggles(profile: UserProfile) {
        val enabledStrings = profile.enabledFeatures
        val enabledEnums = if (enabledStrings == null) {
            adminFeatures // Default to all admin has
        } else {
            adminFeatures.filter { it.name in enabledStrings }.toSet()
        }

        featureAdapter = FeatureToggleAdapter(adminFeatures.toList(), enabledEnums) { _, _ -> }
        binding.rvFeatureToggles.adapter = featureAdapter
    }

    private fun savePermissions() {
        val profile = selectedStaffProfile ?: run {
            Toast.makeText(this, "Please select a staff member with a linked profile first", Toast.LENGTH_SHORT).show()
            return
        }
        val features = featureAdapter.getEnabledFeatures().map { it.name }
        
        lifecycleScope.launch {
            val updated = profile.copy(enabledFeatures = features)
            repository.pushProfileByUid(updated)
            HapticUtil.vibrateSuccess(binding.root)
            Toast.makeText(this@StaffPermissionActivity, "Access permissions updated for ${profile.name} ✨", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, activity = this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
