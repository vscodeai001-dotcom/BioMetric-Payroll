package com.biometric.app.domain.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class TrackingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.i("TrackingBootReceiver", "Boot action received: $action. Initiating auto-start recovery...")

        val trackingPrefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        val sessionPrefs = context.getSharedPreferences("mobile_session", Context.MODE_PRIVATE)
        val authPrefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

        val active = sessionPrefs.getBoolean("active", false)
        val token = sessionPrefs.getString("token", null)
        val employeeId = sessionPrefs.getInt("employee_id", 0)

        // Read staff ID from tracking prefs or session employee ID
        val activeStaff = trackingPrefs.getString("active_staff", null)
            ?: trackingPrefs.getString("active_staff_id", null)
            ?: employeeId.takeIf { it > 0 }?.toString()

        val role = (sessionPrefs.getString("user_role", null) ?: authPrefs.getString("user_role", null))
            .orEmpty().trim().uppercase()

        // Only recover an active session
        if (!active || token.isNullOrBlank() || activeStaff.isNullOrBlank() || employeeId <= 0) {
            Log.d("TrackingBootReceiver", "Skipping boot recovery: active=$active, employeeId=$employeeId, hasToken=${!token.isNullOrBlank()}")
            return
        }

        val geoEnabled = trackingPrefs.getBoolean("enable_geo_fencing", true)
        if (!geoEnabled) {
            Log.d("TrackingBootReceiver", "Geo-Fencing disabled by policy.")
            return
        }

        // 1. Persist intended active state
        trackingPrefs.edit()
            .putString("active_staff", activeStaff)
            .putString("active_staff_id", activeStaff)
            .putBoolean("is_service_active_intended", true)
            .apply()

        // 2. Schedule WorkManager persistent recovery worker
        try {
            TrackingRecoveryWorker.schedule(context)
            val immediateWork = OneTimeWorkRequestBuilder<TrackingRecoveryWorker>()
                .addTag("tracking_boot_immediate")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tracking_boot_immediate",
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
        } catch (e: Exception) {
            Log.w("TrackingBootReceiver", "WorkManager boot enqueue error: ${e.message}")
        }

        // 3. Attempt direct foreground service start with ACTION_START
        try {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                this.action = TrackingService.ACTION_START
                putExtra(TrackingService.EXTRA_STAFF_ID, activeStaff)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i("TrackingBootReceiver", "TrackingService started successfully on boot for staff $activeStaff")
        } catch (e: Exception) {
            Log.w("TrackingBootReceiver", "Direct startForegroundService restricted on boot (WorkManager will handle): ${e.message}")
        }
    }
}

