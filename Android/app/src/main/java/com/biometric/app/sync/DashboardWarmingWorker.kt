package com.biometric.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Compatibility worker for the existing dashboard warming schedule.
 *
 * Dashboard data is hydrated from Firebase/Room by FirebaseRoomHydrator.
 * This worker therefore performs no Payroll.Web/Render request.
 */
@HiltWorker
class DashboardWarmingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val firebaseSync: FirebaseSyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (firebaseSync.isAuthenticated()) {
            firebaseSync.startSync()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<DashboardWarmingWorker>(
                    15,
                    TimeUnit.MINUTES
                )
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
