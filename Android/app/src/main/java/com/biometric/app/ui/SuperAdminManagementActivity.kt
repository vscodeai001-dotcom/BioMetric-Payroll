package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.databinding.ActivitySuperAdminManagementBinding
import com.biometric.app.domain.BrandingManager
import com.biometric.app.domain.FeatureManager
import com.biometric.app.ui.adapter.FeatureToggleAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.biometric.app.sync.ImageUploadManager
import com.biometric.app.sync.AdminRealtimeCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SuperAdminManagementActivity : MotionBaseActivity() {

    private lateinit var binding: ActivitySuperAdminManagementBinding

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var brandingManager: BrandingManager
    @Inject lateinit var imageUploadManager: ImageUploadManager
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator

    private var selectedUser: UserProfile? = null
    private var selectedImageUri: android.net.Uri? = null
    private lateinit var adapter: FeatureToggleAdapter

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            Glide.with(this).load(uri).into(binding.ivSelectedLogo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        
        binding = ActivitySuperAdminManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "SuperAdmin Management"

        setupRecyclerView()
        setupListeners()

        realtimeCoordinator.start {
            val phone = binding.etUserPhone.text.toString().trim()
            if (!isFinishing && !isDestroyed && phone.length == 10 && selectedUser != null) {
                fetchUser(phone)
            }
        }
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        val allFeatures = featureManager.getAllFeatures()
        adapter = FeatureToggleAdapter(allFeatures, emptySet()) { _, _ ->
            // Handle individual toggle if needed
        }
        binding.rvFeatureToggles.layoutManager = LinearLayoutManager(this)
        binding.rvFeatureToggles.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnFetchUser.setOnClickListener {
            val phone = binding.etUserPhone.text.toString().trim()
            if (phone.length == 10) {
                fetchUser(phone)
            } else {
                Toast.makeText(this, "Enter valid 10-digit phone", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnSelectLogo.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun fetchUser(phone: String) {
        lifecycleScope.launch {
            try {
                val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SuperAdminManagementActivity)
                    .setTitle("Searching... 🔍")
                    .setMessage("Looking for user profile with phone: $phone")
                    .setCancelable(false)
                    .show()

                val profile = repository.fetchProfileByPhone(phone)
                
                if (!isFinishing && progressDialog.isShowing) {
                    progressDialog.dismiss()
                }

                if (profile != null) {
                    selectedUser = profile
                    updateUIForUser(profile)
                } else {
                    if (!isFinishing) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SuperAdminManagementActivity)
                            .setTitle("User Not Found ❌")
                            .setMessage("Could not find a user profile for $phone. \n\nPlease ensure:\n1. The user has logged into the app at least once.\n2. The number is correct.\n3. The profile exists in the root 'user_profiles' node in Firebase.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("SuperAdmin", "Fetch failed", e)
                Toast.makeText(this@SuperAdminManagementActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUIForUser(profile: UserProfile) {
        // Load enabled features into adapter
        val enabledStrings = profile.enabledFeatures
        val allFeatures = FeatureManager.Feature.entries
        
        val enabledEnums = if (enabledStrings == null) {
            // Default: All enabled if list is null (new user)
            allFeatures.toSet()
        } else {
            allFeatures.asSequence().filter { it.name in enabledStrings }.toSet()
        }
        
        adapter = FeatureToggleAdapter(featureManager.getAllFeatures(), enabledEnums) { _, _ -> }
        binding.rvFeatureToggles.adapter = adapter
        
        // Load branding
        binding.etBrandingName.setText(profile.brandingName ?: "")
        binding.etBrandingLogoUrl.setText(profile.brandingLogoUrl ?: "")
        
        profile.brandingLogoUrl?.let { url ->
            Glide.with(this).load(url).into(binding.ivSelectedLogo)
        } ?: run {
            binding.ivSelectedLogo.setImageResource(com.biometric.app.R.drawable.ic_assignment)
        }
        
        Toast.makeText(this, "User loaded: ${profile.phone}", Toast.LENGTH_SHORT).show()
    }

    private fun saveSettings() {
        val user = selectedUser ?: run {
            Toast.makeText(this, "Select a user first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val progressToast = Toast.makeText(this@SuperAdminManagementActivity, "Saving settings...", Toast.LENGTH_LONG)
            progressToast.show()

            var logoUrl = binding.etBrandingLogoUrl.text.toString().trim()
            
            // Upload image if selected
            selectedImageUri?.let { uri ->
                imageUploadManager.uploadLogo(user.uid, uri)?.let {
                    logoUrl = it
                }
            }

            val enabledFeatures = adapter.getEnabledFeatures().map { it.name }
            val brandingName = binding.etBrandingName.text.toString().trim()

            val updatedProfile = user.copy(
                enabledFeatures = enabledFeatures,
                brandingName = brandingName.ifBlank { null },
                brandingLogoUrl = logoUrl.ifBlank { null },
            )
            repository.pushProfileByUid(updatedProfile)
            
            progressToast.cancel()
            Toast.makeText(this@SuperAdminManagementActivity, "Settings saved successfully", Toast.LENGTH_SHORT).show()
            
            // Update local state if needed
            selectedUser = updatedProfile
            binding.etBrandingLogoUrl.setText(logoUrl)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
