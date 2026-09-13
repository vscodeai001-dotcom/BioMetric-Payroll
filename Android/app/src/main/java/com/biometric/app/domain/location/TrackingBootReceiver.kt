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

        // Only recover a tracking session that still has a valid local mobile session and Geo-Fencing is allowed.
        if (active && !token.isNullOrBlank() && !activeStaffId.isNullOrBlank() && geoEnabled) {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                action = "ACTION_RECOVERY"
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
