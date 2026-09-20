package com.biometric.app

import android.app.Activity
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
import com.biometric.app.sync.FirebaseRoomHydrator
import com.biometric.app.sync.RealtimeUiDispatcher
import com.biometric.app.util.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BiometricApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var locationSyncManager: LocationSyncManager
    @Inject lateinit var adminRealtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var firebaseRoomHydrator: FirebaseRoomHydrator
    @Inject lateinit var realtimeUiDispatcher: RealtimeUiDispatcher
    @Inject lateinit var sessionStore: com.biometric.app.data.MobileSessionStore
    @Inject lateinit var themePreferenceSync: com.biometric.app.sync.ThemePreferenceSync
    @Inject lateinit var firebaseReconnectCoordinator: com.biometric.app.sync.FirebaseReconnectCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Firebase disk persistence MUST be configured before any FirebaseDatabase
        // reference/listener is touched. Hilt injection happens in super.onCreate()
        // and can trigger singleton constructors that touch Firebase.
        runCatching {
            val firebaseDatabase = FirebaseDatabase.getInstance()
            firebaseDatabase.setPersistenceEnabled(true)
            firebaseDatabase.setPersistenceCacheSizeBytes(100 * 1024 * 1024)
        }.onFailure { Log.w("BiometricApplication", "Firebase persistence setup skipped", it) }

        super.onCreate()

        // Keep one application-scoped realtime coordinator. Activities and fragments

        // Start only when a persisted authenticated session exists. Login/logout
        // continue to own authentication state; this is realtime infrastructure only.
        if (sessionStore.isLoggedIn()) {
            CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                delay(3000) // 3s staggered start for heavy Firebase listeners
                if (!sessionStore.isLoggedIn()) return@launch
                
                // Requirement: Start both the invalidation coordinator AND 
                // the database hydrator as soon as a session is active.
                adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                firebaseRoomHydrator.start()
                
                runCatching { firebaseReconnectCoordinator.start() }
                    .onFailure { Log.w("BiometricApplication", "Firebase reconnect coordinator start skipped", it) }
            }
        }

        // OSMDroid must be initialized BEFORE any Activity creates a MapView.
        // Moved to IO thread to prevent main-thread blockage during startup.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val osm = org.osmdroid.config.Configuration.getInstance()
                osm.load(this@BiometricApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
                osm.userAgentValue = "BioMetricPayroll_Android_" + packageName
                osm.tileDownloadThreads = 4
                osm.tileFileSystemCacheMaxBytes = 200L * 1024L * 1024L
            }.onFailure { Log.w("BiometricApplication", "OSMDroid initialization failed", it) }
        }

        runCatching {
            ThemeManager.applyTheme(this, sessionStore.userThemeKey())
            if (sessionStore.isLoggedIn()) {
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    runCatching { themePreferenceSync.refreshFromServer() }
                        .onFailure { Log.w("BiometricApplication", "Persisted user theme refresh skipped", it) }
                }
            }
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
                ExistingPeriodicWorkPolicy.KEEP,
                backupRequest
            )
        }.onFailure { Log.e("BiometricApplication", "Backup scheduling failed", it) }

        runCatching {
            locationSyncManager.schedulePeriodicSync()
        }.onFailure { Log.e("BiometricApplication", "Location sync scheduling failed", it) }


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
