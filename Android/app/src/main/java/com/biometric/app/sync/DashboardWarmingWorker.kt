package com.biometric.app.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

@HiltWorker
class DashboardWarmingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val mobileApi: MobileApiService,
    private val sessionStore: MobileSessionStore
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!sessionStore.isLoggedIn()) return Result.success()

        val token = sessionStore.token() ?: return Result.success()
        
        return try {
            val response = mobileApi.dashboard("Bearer $token")
            if (response.isSuccessful) {
                response.body()?.let { data ->
                    val cachePrefs = context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE)
                    cachePrefs.edit { 
                        putFloat("salary", data.monthlySalary.toFloat())
                        putFloat("paid_leave", data.paidLeaveBalance.toFloat())
                        putFloat("sick_leave", data.sickLeaveBalance.toFloat())
                    }
                    Log.i("DashboardWarming", "Successfully warmed up dashboard data.")
                }
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("DashboardWarming", "Failed to warm up: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DashboardWarmingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("dashboard_warming")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dashboard_warming",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
