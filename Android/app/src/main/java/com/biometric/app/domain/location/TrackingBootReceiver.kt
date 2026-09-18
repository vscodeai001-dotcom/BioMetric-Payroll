package com.biometric.app.domain.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class TrackingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val trackingPrefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        val sessionPrefs = context.getSharedPreferences("mobile_session", Context.MODE_PRIVATE)
        val activeStaffId = trackingPrefs.getString("active_staff_id", null)
        val token = sessionPrefs.getString("token", null)
        val active = sessionPrefs.getBoolean("active", false)

        val geoEnabled = trackingPrefs.getBoolean("enable_geo_fencing", true)
        val trackingMode = trackingPrefs.getString("tracking_mode", TrackingWindowResolver.MODE_24_7)?.uppercase()

        
        val role = sessionPrefs.getString("user_role", "")?.trim()?.uppercase().orEmpty()
        val employeeId = sessionPrefs.getInt("employee_id", 0)
        // Boot recovery is package-wide. Only Employee/Staff sessions may resume GPS.
        if (role !in setOf("STAFF", "EMPLOYEE") || employeeId <= 0) return
// SHIFT/CUSTOM modes are resumed by TrackingRecoveryWorker, which can
        // safely resolve the current shift window using Room.
        if (trackingMode != TrackingWindowResolver.MODE_24_7) return

        // Only recover a tracking session that still has a valid local mobile session and Geo-Fencing is allowed.
        if (active && !token.isNullOrBlank() && !activeStaffId.isNullOrBlank() && geoEnabled) {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                action = "ACTION_RECOVERY"
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
