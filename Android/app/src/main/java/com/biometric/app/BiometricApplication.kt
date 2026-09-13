package com.biometric.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.biometric.app.backup.BackupWorker
import com.google.firebase.database.FirebaseDatabase
import com.biometric.app.domain.location.LocationSyncManager
import com.biometric.app.domain.location.OfflineSyncWorker
import com.biometric.app.domain.location.TrackingRecoveryWorker
import com.biometric.app.sync.DashboardWarmingWorker
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.NeonSyncWorker
import com.biometric.app.util.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BiometricApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var locationSyncManager: LocationSyncManager
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var mobileSessionStore: MobileSessionStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Keep application startup resilient. A failure in an optional background
        // subsystem must never crash the app before the first screen is shown.
        runCatching {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            FirebaseDatabase.getInstance().setPersistenceCacheSizeBytes(100 * 1024 * 1024)
        }.onFailure { Log.w("BiometricApplication", "Firebase persistence setup skipped", it) }

        runCatching {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                org.osmdroid.config.Configuration.getInstance()
                    .load(this@BiometricApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
                org.osmdroid.config.Configuration.getInstance().userAgentValue =
                    "BioMetricPayroll_Android_" + packageName
            }
        }.onFailure { Log.w("BiometricApplication", "OSMDroid initialization async start failed", it) }

        // Keep the cross-device realtime bus alive for the lifetime of the
        // authenticated app process. Individual screens continue to own their
        // existing UI/layout logic; this only keeps their authoritative cache
        // current in the background.
        runCatching {
            if (mobileSessionStore.isLoggedIn()) {
                realtimeCoordinator.start()
            }
        }.onFailure { Log.w("BiometricApplication", "Realtime coordinator startup skipped", it) }

        runCatching {
            ThemeManager.applyTheme(this)
        }.onFailure { Log.w("BiometricApplication", "Theme initialization skipped", it) }

        // Background jobs are useful, but they are not required to render/login.
        // Isolate each scheduler so a WorkManager/provider/configuration problem
        // cannot bring down the whole application process on a fresh install.
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "automated_business_backup_periodic",
                ExistingPeriodicWorkPolicy.REPLACE,
                backupRequest
            )
        }.onFailure { Log.e("BiometricApplication", "Backup scheduling failed", it) }

        runCatching {
            locationSyncManager.schedulePeriodicSync()
        }.onFailure { Log.e("BiometricApplication", "Location sync scheduling failed", it) }

        runCatching {
            val neonSyncRequest = PeriodicWorkRequestBuilder<NeonSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "neon_db_sync_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                neonSyncRequest
            )
        }.onFailure { Log.e("BiometricApplication", "Neon sync scheduling failed", it) }

        runCatching {
            TrackingRecoveryWorker.schedule(this)
        }.onFailure { Log.e("BiometricApplication", "Tracking recovery scheduling failed", it) }

        runCatching {
            OfflineSyncWorker.schedule(this)
        }.onFailure { Log.e("BiometricApplication", "Offline sync scheduling failed", it) }

        runCatching {
            DashboardWarmingWorker.schedule(this)
        }.onFailure { Log.e("BiometricApplication", "Dashboard warming scheduling failed", it) }
    }
}
