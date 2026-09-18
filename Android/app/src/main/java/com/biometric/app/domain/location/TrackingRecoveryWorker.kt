package com.biometric.app.domain.location

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.core.content.ContextCompat
import androidx.work.*
import com.biometric.app.data.MobileSessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class TrackingRecoveryWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionStore: MobileSessionStore,
    private val trackingWindowResolver: TrackingWindowResolver
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!sessionStore.isLoggedIn()) return Result.success()

        
        // Background GPS recovery is an Employee/Staff feature only.
        val role = sessionStore.userRole().trim().uppercase()
        if (role !in setOf("STAFF", "EMPLOYEE")) {
            Log.d("TrackingRecovery", "Skipping GPS recovery for role=$role")
            return Result.success()
        }
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) {
            Log.w("TrackingRecovery", "Skipping GPS recovery: invalid employeeId=$employeeId")
            return Result.success()
        }
val prefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        val geoEnabled = prefs.getBoolean("enable_geo_fencing", true)

        if (!geoEnabled) {
            Log.i("TrackingRecovery", "Geo-Fencing is disabled by policy. Skipping service recovery.")
            return Result.success()
        }

        val window = trackingWindowResolver.resolve()
        if (!window.allowed) {
            prefs.edit().putBoolean("tracking_waiting_for_shift", true).apply()
            scheduleFollowUp(context)
            return Result.success()
        }
        prefs.edit().putBoolean("tracking_waiting_for_shift", false).apply()

        if (!isServiceRunning(TrackingService::class.java)) {
            Log.i("TrackingRecovery", "Service not running, restarting aggressively...")
            val intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
                putExtra(TrackingService.EXTRA_STAFF_ID, sessionStore.employeeId().toString())
            }
            try {
                ContextCompat.startForegroundService(context, intent)
                // Schedule a one-time immediate follow-up to ensure persistence
                scheduleFollowUp(context)
            } catch (e: Exception) {
                Log.e("TrackingRecovery", "Failed to restart service: ${e.message}")
            }
        } else {
            // Service is running, but let's schedule a follow-up anyway to keep the process alive
            scheduleFollowUp(context)
        }
        return Result.success()
    }

    private fun scheduleFollowUp(context: Context) {
        val followUp = OneTimeWorkRequestBuilder<TrackingRecoveryWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .addTag("tracking_recovery_followup")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "tracking_recovery_followup",
            ExistingWorkPolicy.REPLACE,
            followUp
        )
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        // Optimized: Check the shared preference flag first
        val trackingPrefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
        if (!trackingPrefs.getBoolean("is_service_active_intended", false)) return false
        
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(50)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .addTag("tracking_recovery")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tracking_recovery",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
