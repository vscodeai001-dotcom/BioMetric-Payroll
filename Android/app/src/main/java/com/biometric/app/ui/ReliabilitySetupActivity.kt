package com.biometric.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.biometric.app.R
import com.biometric.app.data.entity.UserRole
import com.biometric.app.databinding.ActivityReliabilitySetupBinding
import com.biometric.app.util.BatteryOptimizationHelper
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.OemSettingsHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ReliabilitySetupActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityReliabilitySetupBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateStatus()
    }

    override fun isSecurityBypass(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReliabilitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupClickListeners() {
        binding.btnConfigureLocation.setOnClickListener {
            HapticUtil.vibrateClick(it)
            requestLocation()
        }

        binding.btnConfigureBattery.setOnClickListener {
            HapticUtil.vibrateClick(it)
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            OemSettingsHelper.showOemSettingsDialog(this)
        }

        binding.btnConfigurePermission.setOnClickListener {
            HapticUtil.vibrateClick(it)
            // Send to App Info to disable "Remove permissions if app is unused"
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        binding.btnConfigureNotifications.setOnClickListener {
            HapticUtil.vibrateClick(it)
            if (Build.VERSION.SDK_INT >= 33) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                Toast.makeText(this, "Notifications are enabled for this device", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFinish.setOnClickListener {
            HapticUtil.vibrateSuccess(it)
            sessionStore.setReliabilitySetupDone(true)
            Toast.makeText(this, getString(R.string.msg_setup_complete) + " ✨ 🚀", Toast.LENGTH_SHORT).show()
            
            val role = getSharedPreferences("auth_prefs", MODE_PRIVATE).getString("user_role", UserRole.Employee.name)
            val destination = if (role == UserRole.Employee.name) EmployeeHomeActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, destination).apply {
                if (destination == EmployeeHomeActivity::class.java) {
                    putExtra("JUST_LOGGED_IN", intent.getBooleanExtra("JUST_LOGGED_IN", false))
                }
            })
            finish()
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= 29) {
            val background = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!background) {
                // For Background Location on API 30+, we must first explain then send to settings
                MaterialAlertDialogBuilder(this)
                    .setTitle("Always Tracking 🛰️")
                    .setMessage("To track your location accurately even when the app is closed, please select 'Allow all the time' in the system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun updateStatus() {
        // 1. Location
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        val locationDone = fine && background
        binding.ivLocationStatus.setImageResource(if (locationDone) R.drawable.ic_check_circle else R.drawable.ic_cancel)
        binding.ivLocationStatus.setColorFilter(ContextCompat.getColor(this, if (locationDone) R.color.green else R.color.red))

        // 2. Battery
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryDone = if (Build.VERSION.SDK_INT >= 23) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
        binding.ivBatteryStatus.setImageResource(if (batteryDone) R.drawable.ic_check_circle else R.drawable.ic_cancel)
        binding.ivBatteryStatus.setColorFilter(ContextCompat.getColor(this, if (batteryDone) R.color.green else R.color.red))

        // 3. Permission (Best effort check or just let user click)
        // We'll mark it as green once they return from resume if they have other perms, or just leave it for them to check.
        // For UX, we only block 'Finish' on Location and Battery which we can check perfectly.
        val permissionDone = true 
        binding.ivPermissionStatus.setImageResource(if (permissionDone) R.drawable.ic_check_circle else R.drawable.ic_cancel)
        binding.ivPermissionStatus.setColorFilter(ContextCompat.getColor(this, if (permissionDone) R.color.green else R.color.red))

        // 4. Notifications
        val notificationsDone = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        binding.ivNotificationStatus.setImageResource(if (notificationsDone) R.drawable.ic_check_circle else R.drawable.ic_cancel)
        binding.ivNotificationStatus.setColorFilter(ContextCompat.getColor(this, if (notificationsDone) R.color.green else R.color.red))

        binding.btnFinish.isEnabled = locationDone && batteryDone && notificationsDone
    }
}
