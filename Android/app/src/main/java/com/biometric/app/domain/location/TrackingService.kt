package com.biometric.app.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.biometric.app.sync.SignalRManager
import com.biometric.app.sync.FirebaseSyncManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.biometric.app.R
import com.biometric.app.api.GpsSessionRequest
import com.biometric.app.api.GpsUpdateRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.LocalLocation
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import java.util.UUID
import com.biometric.app.ui.EmployeeHomeActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import com.google.android.gms.location.*

@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var qualityManager: LocationQualityManager
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var syncManager: LocationSyncManager
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var offlineMonitor: OfflineTrackingMonitor

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var isManualStopping = false
    private var staffId: String = "unknown"
    private var lastLocation: Location? = null
    private var currentInterval = 30_000L
    private var serverSessionStarted = false
    // Keep only the newest GPS fix for server upload. This prevents callback bursts
    // from building an unbounded queue of network coroutines and starving the app.
    private val latestLocationChannel = Channel<LocalLocation>(Channel.CONFLATED)
    private var gpsUploadJob: Job? = null
    private var heartbeatJob: Job? = null
    private var locationHandlerThread: HandlerThread? = null
    private var isForeground = false
    private var locationUpdatesStarted = false
    private var signalRStarted = false
    private val sequenceLock = Any()

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "tracking_prefs"
        private const val KEY_ACTIVE_STAFF = "active_staff_id"
        private const val KEY_SERVER_STARTED = "server_session_started"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_STAFF_ID = "EXTRA_STAFF_ID"
        private const val KEY_LAST_LOCATION_AT = "last_location_at"
        private const val KEY_LAST_SERVER_AT = "last_server_at"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_SEQUENCE = "gps_sequence"
        private const val KEY_SEQUENCE_SESSION = "gps_sequence_session"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        offlineMonitor.start()
        offlineMonitor.record(OfflineTrackingMonitor.SERVICE_RECOVERED, OfflineTrackingMonitor.INFO, "Tracking service initialized/recovered")
        
        // Start foreground immediately in onCreate to satisfy the system 5s rule
        startForegroundSafe()
        isForeground = true

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioMetric:TrackingWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only start foreground if not already started
        if (!isForeground) {
            startForegroundSafe()
            isForeground = true
        }

        when (intent?.action) {
            ACTION_START, "ACTION_RECOVERY" -> {
                staffId = intent.getStringExtra(EXTRA_STAFF_ID)
                    ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACTIVE_STAFF, null)
                    ?: sessionStore.employeeId().toString()
                
                serverSessionStarted = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putString(KEY_ACTIVE_STAFF, staffId)
                    putBoolean(KEY_SERVER_STARTED, false)
                }

                // REQUIREMENT: Extreme 24/7 background persistence
                // Ensure WakeLock is held as long as service is running
                if (wakeLock == null) {
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioMetric:TrackingWakeLock")
                }
                if (wakeLock?.isHeld == false) {
                    // Holding a partial wake lock ensures the CPU stays on
                    // even if the screen is off or the app is in the background.
                    wakeLock?.acquire()
                }
                
                if (!locationUpdatesStarted) {
                    startLocationUpdates()
                }
                if (!signalRStarted) {
                    signalRStarted = true
                    serviceScope.launch {
                        ensureServerSession()
                        signalR.start()
                    }
                }

                if (heartbeatJob?.isActive != true) {
                    startHeartbeatLoop()
                }

                syncManager.schedulePeriodicSync()
                scheduleRestartTick()
                
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putBoolean("is_service_active_intended", true)
                }
            }
            ACTION_STOP -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putBoolean("is_service_active_intended", false)
                }
                cancelRestartTick()
                stopTracking()
            }
        }
        return START_STICKY
    }

    private fun scheduleRestartTick() {
        val intent = Intent(this, TrackingNotificationReceiver::class.java).apply {
            action = "ACTION_SERVICE_RESTART_TICK"
        }
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // 5 minute recurring tick to ensure the service is ALWAYS up
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 300_000, 300_000, pendingIntent)
    }

    private fun cancelRestartTick() {
        val intent = Intent(this, TrackingNotificationReceiver::class.java).apply {
            action = "ACTION_SERVICE_RESTART_TICK"
        }
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Persistent Sync Status", NotificationManager.IMPORTANCE_DEFAULT)
            channel.apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                description = "Authoritative background synchronization service"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafe() {
        val mainIntent = Intent(this, EmployeeHomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "System Synchronization", NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            channel.setSound(null, null)
            channel.enableLights(false)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val deleteIntent = Intent(this, TrackingNotificationReceiver::class.java).apply {
            action = "ACTION_NOTIFICATION_DISMISSED"
        }
        val deletePendingIntent = PendingIntent.getBroadcast(this, 0, deleteIntent, PendingIntent.FLAG_IMMUTABLE)

        // Extreme strategy: inform user and OS that this is an authoritative process
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logs) 
            .setContentTitle("BioMetric: Sync Active 🛰️")
            .setContentText("Authoritative background synchronization is active.")
            .setSubText("24/7 Connectivity")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) 
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) 
            .setOngoing(true)
            .setSilent(true) 
            .setLocalOnly(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
            
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                 ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                
                if (hasLocation) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    // Fallback to non-location type if permission is missing to avoid security exception
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("TrackingService", "Failed to start FGS: ${e.message}")
            // CRITICAL FALLBACK: Try basic startForeground
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackEx: Exception) {
                Log.e("TrackingService", "CRITICAL: Final FGS attempt failed: ${fallbackEx.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentInterval)
            .setMinUpdateIntervalMillis(10_000L)
            .setWaitForAccurateLocation(false)
            .build()
        
        locationHandlerThread?.quitSafely()
        val handlerThread = HandlerThread("LocationUpdatesThread")
        locationHandlerThread = handlerThread
        handlerThread.start()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, handlerThread.looper)
        locationUpdatesStarted = true
        
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(24 * 60 * 60 * 1000L /*24 hours*/)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocationUpdate(it) }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        if (qualityManager.isMockLocation(location)) {
            offlineMonitor.record(OfflineTrackingMonitor.DATA_INTEGRITY_WARNING, OfflineTrackingMonitor.WARNING, "Mock location rejected by existing quality policy")
            return
        }
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            offlineMonitor.record(OfflineTrackingMonitor.DATA_INTEGRITY_WARNING, OfflineTrackingMonitor.ERROR, "Invalid GPS coordinate rejected")
            return
        }
        if (qualityManager.isSuspiciousMovement(lastLocation, location)) {
            offlineMonitor.record(OfflineTrackingMonitor.DATA_INTEGRITY_WARNING, OfflineTrackingMonitor.WARNING, "Suspicious movement detected; preserving GPS fix for audit")
        }

        lastLocation = location
        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
            putLong(KEY_LAST_LOCATION_AT, System.currentTimeMillis())
            putFloat(KEY_LAST_ACCURACY, location.accuracy)
            putFloat(KEY_LAST_LAT, location.latitude.toFloat())
            putFloat(KEY_LAST_LON, location.longitude.toFloat())
            putFloat("last_speed", location.speed)
        }
        
        saveLocationToLocalQueue(location)
    }

    private fun nextGpsSequence(sessionId: String): Long {
        synchronized(sequenceLock) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val previousSession = prefs.getString(KEY_SEQUENCE_SESSION, null)
            val current = if (previousSession == sessionId) prefs.getLong(KEY_SEQUENCE, 0L) else 0L
            val next = current + 1L
            prefs.edit {
                putString(KEY_SEQUENCE_SESSION, sessionId)
                putLong(KEY_SEQUENCE, next)
            }
            return next
        }
    }

    private fun saveLocationToLocalQueue(location: Location) {
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sessionId = sessionStore.gpsSessionId()
        val clientEventId = UUID.randomUUID().toString()

        serviceScope.launch {
            runCatching {
                val nextSequence = nextGpsSequence(sessionId)
                val local = LocalLocation(
                    clientEventId = clientEventId,
                    sessionId = sessionId,
                    sequence = nextSequence,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    batteryLevel = battery,
                    // Preserve the GPS event time, not the upload time.
                    timestamp = location.time,
                    capturedElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
                    isOfflineCapture = !offlineMonitor.isOnline()
                )
                val insertedId = locationDao.insert(local)
                if (insertedId > 0L) {
                    offlineMonitor.record(
                        OfflineTrackingMonitor.QUEUE_ENQUEUED,
                        if (local.isOfflineCapture) OfflineTrackingMonitor.WARNING else OfflineTrackingMonitor.INFO,
                        if (local.isOfflineCapture) "GPS fix captured offline and durably queued" else "GPS fix durably queued for authoritative upload",
                        sessionId = sessionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        correlationId = clientEventId
                    )
                    latestLocationChannel.trySend(local)
                    startGpsUploadWorker()
                }
            }.onFailure { ex ->
                Log.d("TrackingService", "Local GPS save deferred: ${ex.message}")
                offlineMonitor.record(OfflineTrackingMonitor.DATA_INTEGRITY_WARNING, OfflineTrackingMonitor.ERROR, "Unable to persist GPS fix locally: ${ex.message}")
            }
        }
    }

    private fun startGpsUploadWorker() {
        if (gpsUploadJob?.isActive == true) return

        gpsUploadJob = serviceScope.launch {
            for (location in latestLocationChannel) {
                uploadLocationToServer(location)
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    val token = sessionStore.token()
                    if (!token.isNullOrBlank()) {
                        // The me() endpoint refreshes LastSeenAtUtc on the server,
                        // keeping the Admin Dashboard status "Live" even if stationary.
                        val response = mobileApi.me("Bearer $token")
                        if (response.isSuccessful) {
                            // Authentication can remain valid while the server-side
                            // GPS session is recreated after a process restart.
                            // Restore only the GPS session here; never log out.
                            if (!serverSessionStarted) {
                                ensureServerSession()
                            }
                            Log.d("TrackingService", "Heartbeat success 💓")
                        } else if (response.code() == 401 || response.code() == 403) {
                            val state = response.headers()["X-Mobile-Session-State"] ?: ""
                            if (state.equals("SESSION_REVOKED", true) ||
                                state.equals("REAUTH_REQUIRED", true)) {
                                // Do not clear the persisted login here. The
                                // foreground service must remain alive long
                                // enough for the UI/session owner to process an
                                // authoritative revocation. Network failures
                                // never enter this branch.
                                Log.w("TrackingService", "Heartbeat: server explicitly rejected this mobile session (${state}); preserving local session until authoritative UI handling.")
                            } else {
                                Log.w("TrackingService", "Heartbeat HTTP ${response.code()} without explicit revocation. Tracking/session state retained.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("TrackingService", "Heartbeat deferred: ${e.message}")
                }
                // Periodic ping every 5 minutes
                delay(300_000L)
            }
        }
    }

    private suspend fun uploadLocationToServer(location: LocalLocation) {
        var sessionId = location.sessionId

        var firebaseUploaded = false
        try {
            locationDao.markAttempt(location.id, LocalLocation.SYNC_IN_FLIGHT, location.attemptCount + 1, System.currentTimeMillis(), null)

            // PRIMARY LIVE TRANSPORT: Firebase is independent of Render.
            // Keep the original server upload below as the authoritative Neon
            // persistence path and compatibility layer.
            firebaseUploaded = firebaseSync.pushLiveLocation(
                employeeId = sessionStore.employeeId(),
                sessionId = location.sessionId,
                clientEventId = location.clientEventId,
                sequence = location.sequence,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.toDouble(),
                speed = location.speed.toDouble(),
                batteryLevel = location.batteryLevel,
                timestamp = location.timestamp
            )

            if (firebaseUploaded) {
                locationDao.markAttempt(
                    location.id,
                    LocalLocation.FIREBASE_SYNCED,
                    location.attemptCount + 1,
                    System.currentTimeMillis(),
                    null
                )
                offlineMonitor.record(
                    OfflineTrackingMonitor.UPLOAD_SUCCESS,
                    OfflineTrackingMonitor.INFO,
                    "GPS fix ${location.sequence} published to Firebase realtime transport",
                    sessionId = location.sessionId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    correlationId = location.clientEventId
                )
            }

            // Neon/server persistence is best-effort from the tracking
            // service. It is deliberately attempted only after Firebase has
            // accepted the fix, so Render availability cannot stop tracking.
            val token = sessionStore.token()
            if (token.isNullOrBlank() || !ensureServerSession()) {
                locationDao.markAttempt(
                    location.id,
                    if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalLocation.SYNC_FAILED,
                    location.attemptCount + 1,
                    System.currentTimeMillis(),
                    "SERVER_UNAVAILABLE"
                )
                return
            }

            var response = mobileApi.updateGps(
                "Bearer $token",
                GpsUpdateRequest(
                    sessionId = sessionId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    speed = location.speed.toDouble(),
                    timestamp = location.timestamp,
                    batteryLevel = location.batteryLevel,
                    clientEventId = location.clientEventId,
                    sequence = location.sequence
                )
            )

            // The server remains authoritative. If this client session was ended
            // elsewhere, create one fresh session and retry this fix once.
            if (response.code() == 409) {
                Log.w(
                    "TrackingService",
                    "GPS Session 409: authoritative session ended. Creating a fresh session."
                )

                serverSessionStarted = false
                sessionStore.clearGpsSession()
                sessionId = sessionStore.gpsSessionId()
                locationDao.rebindSession(location.id, sessionId)

                if (!ensureServerSession()) return

                response = mobileApi.updateGps(
                    "Bearer $token",
                    GpsUpdateRequest(
                        sessionId = sessionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        speed = location.speed.toDouble(),
                        timestamp = location.timestamp,
                        batteryLevel = location.batteryLevel,
                        clientEventId = location.clientEventId,
                        sequence = location.sequence
                    )
                )
            }

            if (response.isSuccessful) {
                locationDao.markSynced(location.id, System.currentTimeMillis())
                offlineMonitor.record(
                    OfflineTrackingMonitor.UPLOAD_SUCCESS,
                    OfflineTrackingMonitor.INFO,
                    "GPS fix ${location.sequence} acknowledged by server",
                    sessionId = sessionId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    correlationId = location.clientEventId
                )
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putLong(KEY_LAST_SERVER_AT, System.currentTimeMillis())
                    putString(KEY_LAST_STATUS, "Active")
                }
                OfflineSyncWorker.schedule(this@TrackingService)
            } else {
                locationDao.markAttempt(
                    location.id,
                    if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalLocation.SYNC_FAILED,
                    location.attemptCount + 1,
                    System.currentTimeMillis(),
                    "HTTP_${response.code()}"
                )
                offlineMonitor.record(
                    OfflineTrackingMonitor.UPLOAD_FAILED,
                    OfflineTrackingMonitor.WARNING,
                    "GPS upload deferred with HTTP ${response.code()}; local evidence retained",
                    sessionId = sessionId,
                    correlationId = location.clientEventId
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            // If Firebase already accepted the point, retain FIREBASE_SYNCED
            // rather than losing the durable local-to-cloud handoff state.
            locationDao.markAttempt(
                location.id,
                if (firebaseUploaded) LocalLocation.FIREBASE_SYNCED else LocalLocation.SYNC_FAILED,
                location.attemptCount + 1,
                System.currentTimeMillis(),
                ex.message?.take(500)
            )
            offlineMonitor.record(
                OfflineTrackingMonitor.UPLOAD_FAILED,
                OfflineTrackingMonitor.WARNING,
                "GPS upload deferred: ${ex.message ?: "network unavailable"}",
                sessionId = location.sessionId,
                correlationId = location.clientEventId
            )
            Log.d("TrackingService", "GPS upload deferred: ${ex.message}")
        }
    }

    private suspend fun ensureServerSession(): Boolean {
        if (serverSessionStarted) return true
        val token = sessionStore.token() ?: return false
        return try {
            val response = mobileApi.startGps("Bearer $token", GpsSessionRequest(sessionStore.gpsSessionId()))
            if (response.isSuccessful && response.body()?.success == true) {
                serverSessionStarted = true
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun stopTracking() {
        isManualStopping = true
        serviceScope.launch {
            val token = sessionStore.token()
            if (!token.isNullOrBlank()) {
                runCatching { mobileApi.endGps("Bearer $token", GpsSessionRequest(sessionStore.gpsSessionId())) }
            }
            sessionStore.clearGpsSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isManualStopping) return
        
        // Aggressively restart the service if swiped away
        Log.i("TrackingService", "Task removed, triggering immediate recovery...")
        val restartIntent = Intent(applicationContext, this.javaClass).apply {
            action = ACTION_START
        }
        restartIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getService(applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesStarted = false
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        latestLocationChannel.close()
        offlineMonitor.record(OfflineTrackingMonitor.TRACKING_STOPPED, OfflineTrackingMonitor.INFO, "Tracking service destroyed")
        offlineMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
