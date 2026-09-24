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
import com.google.firebase.auth.FirebaseAuth
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
    @Inject lateinit var signalRManager: com.biometric.app.sync.SignalRManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Firebase disk persistence MUST be configured before any FirebaseDatabase
        // reference/listener is touched. Hilt injection happens in super.onCreate()
        // and can trigger singleton constructors that touch Firebase.
        // Firebase disk persistence is intentionally disabled.
        // Room serves as the local cache; Firebase persistence on large RTDB datasets
        // triggers DefaultPersistenceManager.runInTransaction → ChildrenNode.getHashRepresentation
        // which allocates unbounded StringBuilder memory → OOM crash loop on startup.
        runCatching {
            FirebaseDatabase.getInstance().setPersistenceEnabled(false)
        }.onFailure { Log.w("BiometricApplication", "Firebase persistence setup skipped", it) }

        super.onCreate()

        // Keep one application-scoped realtime coordinator. Firebase Auth can
        // restore its persisted user a little after Application.onCreate().
        // Starting only from sessionStore.isLoggedIn() therefore creates a race:
        // the UI opens with the old Room snapshot while Firebase listeners are
        // not attached until the user logs out/in again. The AuthStateListener
        // closes that startup gap and restarts the realtime stack after login.
        val realtimeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun startRealtimeInfrastructure() {
            if (!sessionStore.isLoggedIn() || FirebaseAuth.getInstance().currentUser == null) return
            val role = sessionStore.userRole().trim().uppercase()
            val isAdmin = role in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
            realtimeScope.launch {
                delay(150)
                if (!sessionStore.isLoggedIn() || FirebaseAuth.getInstance().currentUser == null) return@launch

                if (isAdmin) {
                    adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                    firebaseRoomHydrator.start()
                    runCatching { firebaseReconnectCoordinator.start() }
                        .onFailure { Log.w("BiometricApplication", "Firebase reconnect coordinator start skipped", it) }
                }
                runCatching { signalRManager.start() }
                    .onFailure { Log.w("BiometricApplication", "Firebase realtime manager start skipped", it) }
            }
        }

        val authStateListener = FirebaseAuth.AuthStateListener {
            val firebaseUserUid = FirebaseAuth.getInstance().currentUser?.uid
            if (!firebaseUserUid.isNullOrBlank() && sessionStore.isLoggedIn()) {
                startRealtimeInfrastructure()
            } else {
                // Remove tenant listeners immediately on explicit sign-out so
                // the next account can never inherit the previous account's
                // cached realtime state.
                runCatching { signalRManager.stop() }
                runCatching { firebaseReconnectCoordinator.stop() }
                runCatching { firebaseRoomHydrator.stop() }
                runCatching { adminRealtimeCoordinator.stop() }
            }
        }
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)

        // Also cover the case where Firebase Auth is already restored before
        // the listener is registered.
        startRealtimeInfrastructure()

        // Auto-heal realtime listeners whenever the app returns to foreground after background idle.
        var foregroundActivityCount = 0
        var isActivityChangingConfigs = false
        var lastBackgroundTimestamp = 0L

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (foregroundActivityCount == 0 && !isActivityChangingConfigs) {
                    val idleTime = System.currentTimeMillis() - lastBackgroundTimestamp
                    if (sessionStore.isLoggedIn() && FirebaseAuth.getInstance().currentUser != null) {
                        Log.i("BiometricApplication", "App returned to foreground (idle: ${idleTime}ms) - healing realtime listeners")
                        realtimeScope.launch {
                            // Proactively refresh Firebase Auth token so expired tokens do not cause listener silence
                            runCatching {
                                FirebaseAuth.getInstance().currentUser?.getIdToken(false)
                            }
                            val role = sessionStore.userRole().trim().uppercase()
                            val isAdmin = role in setOf("ADMIN", "SUPERADMIN", "SUPER_ADMIN")
                            if (isAdmin) {
                                firebaseRoomHydrator.forceRebind("Foreground resume after idle")
                                adminRealtimeCoordinator.start { realtimeUiDispatcher.refreshVisible() }
                                runCatching { firebaseReconnectCoordinator.start() }
                            }
                            runCatching { signalRManager.start() }
                            runCatching { signalRManager.reconcileLiveLocationsNow() }
                        }
                    }
                }
                foregroundActivityCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                isActivityChangingConfigs = activity.isChangingConfigurations
                foregroundActivityCount--
                if (foregroundActivityCount <= 0) {
                    foregroundActivityCount = 0
                    lastBackgroundTimestamp = System.currentTimeMillis()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

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
