package com.biometric.app.domain.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.biometric.app.data.MobileSessionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TrackingNotificationReceiver : BroadcastReceiver() {
    
    @Inject lateinit var sessionStore: MobileSessionStore

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("TrackingNotifReceiver", "Received event: ${intent.action}")
        
        if (intent.action == "ACTION_NOTIFICATION_DISMISSED" || 
            intent.action == "ACTION_SERVICE_RESTART_TICK" ||
            intent.action == TrackingService.ACTION_REFRESH_WINDOW) {
            
            val trackingPrefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            val geoEnabled = trackingPrefs.getBoolean("enable_geo_fencing", true)
            
            if (sessionStore.isLoggedIn() && geoEnabled) {
                Log.i("TrackingNotifReceiver", "Aggressively restoring tracking service...")
                val serviceIntent = Intent(context, TrackingService::class.java).apply {
                    action = if (intent.action == TrackingService.ACTION_REFRESH_WINDOW) TrackingService.ACTION_REFRESH_WINDOW else TrackingService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("TrackingNotifReceiver", "Restoration failed: ${e.message}")
                }
            }
        }
    }
}
