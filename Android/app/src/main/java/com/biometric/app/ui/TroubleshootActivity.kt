package com.biometric.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.databinding.ActivityTroubleshootBinding
import com.biometric.app.util.BatteryOptimizationHelper
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.NotificationUtil
import com.biometric.app.util.OemSettingsHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@AndroidEntryPoint
class TroubleshootActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityTroubleshootBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateStatus()
    }

    override fun isSecurityBypass(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTroubleshootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupItems()
        setupListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupItems() {
        // Critical
        configureItem(binding.itemNotifications.root, R.string.troubleshoot_step_notifications_title, R.string.troubleshoot_step_notifications_desc)
        configureItem(binding.itemGps.root, R.string.troubleshoot_step_gps_title, R.string.troubleshoot_step_gps_desc)
        configureItem(binding.itemBgLocation.root, R.string.troubleshoot_step_bg_location_title, R.string.troubleshoot_step_bg_location_desc)
        configureItem(binding.itemBgUsage.root, R.string.troubleshoot_step_bg_usage_title, R.string.troubleshoot_step_bg_usage_desc)
        configureItem(binding.itemAlarms.root, R.string.troubleshoot_step_alarms_title, R.string.troubleshoot_step_alarms_desc)
        configureItem(binding.itemBattery.root, R.string.troubleshoot_step_battery_title, R.string.troubleshoot_step_battery_desc)

        // Other
        configureItem(binding.itemCamera.root, R.string.troubleshoot_step_camera_title, R.string.troubleshoot_step_camera_desc)
        configureItem(binding.itemBluetooth.root, R.string.troubleshoot_step_bluetooth_title, R.string.troubleshoot_step_bluetooth_desc)
        configureItem(binding.itemOverlay.root, R.string.troubleshoot_step_overlay_title, R.string.troubleshoot_step_overlay_desc)
    }

    private fun configureItem(view: View, titleRes: Int, descRes: Int) {
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDesc).setText(descRes)
    }

    private fun setupListeners() {
        binding.itemNotifications.root.setOnClickListener {
            // ... (rest of code)
        }

        binding.itemGps.root.setOnClickListener {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }

        binding.itemBgLocation.root.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Always Tracking 🛰️")
                    .setMessage("To track your location accurately even when the app is closed, please select 'Allow all the time' in the system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "Background location is always enabled on this device", Toast.LENGTH_SHORT).show()
            }
        }

        binding.itemBgUsage.root.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Select 'Battery' then 'Unrestricted' and check 'Background usage' ⚙️", Toast.LENGTH_LONG).show()
        }

        binding.itemAlarms.root.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Exact alarms are allowed for this device", Toast.LENGTH_SHORT).show()
            }
        }

        binding.itemBattery.root.setOnClickListener {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            OemSettingsHelper.showOemSettingsDialog(this)
        }

        binding.itemCamera.root.setOnClickListener {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }

        binding.itemBluetooth.root.setOnClickListener {
            val perms = if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            }
            permissionLauncher.launch(perms)
        }

        binding.itemOverlay.root.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        binding.btnTestNotification.setOnClickListener {
            HapticUtil.vibrateClick(it)
            NotificationUtil.sendTestNotification(this)
            Toast.makeText(this, R.string.msg_test_notif_sent, Toast.LENGTH_SHORT).show()
        }

        binding.btnTestNotificationLock.setOnClickListener {
            HapticUtil.vibrateClick(it)
            Toast.makeText(this, R.string.msg_test_notif_lock_sent, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                delay(5000)
                NotificationUtil.sendTestNotification(this@TroubleshootActivity, isLockScreen = true)
            }
        }

        binding.btnRepairSession.setOnClickListener {
            HapticUtil.vibrateClick(it)
            repairSession()
        }
    }

    private fun repairSession() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Session Missing ❌")
                .setMessage("Your secure session has been cleared by the system. You must sign in again to restore tracking.")
                .setPositiveButton("Sign In") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.btnRepairSession.isEnabled = false
                binding.btnRepairSession.text = "Repairing... ⚡"
                
                // Force a fresh ID token from Firebase servers
                user.getIdToken(true).await()
                
                delay(1000)
                Toast.makeText(this@TroubleshootActivity, "Secure connection repaired successfully! ✨", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("Troubleshoot", "Session repair failed", e)
                Toast.makeText(this@TroubleshootActivity, "Repair failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnRepairSession.isEnabled = true
                binding.btnRepairSession.text = getString(R.string.btn_repair_session)
            }
        }
    }

    private fun updateStatus() {
        // Notifications
        val notifDone = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        setItemStatus(binding.itemNotifications.root, notifDone)

        // GPS
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsDone = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        setItemStatus(binding.itemGps.root, gpsDone)

        // Background Location
        val bgLocDone = if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        setItemStatus(binding.itemBgLocation.root, bgLocDone)

        // Background Usage (Restricted check)
        val bgUsageDone = !BatteryOptimizationHelper.isBackgroundRestricted(this)
        setItemStatus(binding.itemBgUsage.root, bgUsageDone)

        // Alarms
        val alarmsDone = if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else true
        setItemStatus(binding.itemAlarms.root, alarmsDone)

        // Battery
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryDone = if (Build.VERSION.SDK_INT >= 23) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
        setItemStatus(binding.itemBattery.root, batteryDone)

        // Camera
        val cameraDone = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setItemStatus(binding.itemCamera.root, cameraDone)

        // Bluetooth
        val btDone = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        setItemStatus(binding.itemBluetooth.root, btDone)

        // Overlay
        val overlayDone = if (Build.VERSION.SDK_INT >= 23) {
            Settings.canDrawOverlays(this)
        } else true
        setItemStatus(binding.itemOverlay.root, overlayDone)
    }

    private fun setItemStatus(view: View, done: Boolean) {
        val ivStatus = view.findViewById<ImageView>(R.id.ivStatus)
        ivStatus.setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_warning_triangle)
        ivStatus.setColorFilter(ContextCompat.getColor(this, if (done) R.color.green else R.color.red))
    }
}
