package com.biometric.app.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.domain.attendance.AttendancePolicyRepository
import com.biometric.app.data.repository.TrackingConfigurationRepository
import com.google.firebase.database.ValueEventListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.biometric.app.R
import com.biometric.app.data.LocalLocation
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.GeofenceDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.OfflineTrackingEvent
import com.biometric.app.sync.FirebaseEmployeeSessionManager
import com.biometric.app.sync.FirebaseRoomHydrator
import com.biometric.app.ui.TroubleshootActivity
import java.util.UUID
import com.biometric.app.ui.EmployeeHomeActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import com.google.android.gms.location.*

@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var qualityManager: LocationQualityManager
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var syncManager: LocationSyncManager
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var geofenceDao: GeofenceDao
    @Inject lateinit var eventDao: OfflineTrackingEventDao
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var offlineMonitor: OfflineTrackingMonitor
    @Inject lateinit var geofenceManager: GeofenceManager
    @Inject lateinit var attendancePolicy: AttendancePolicyRepository
    @Inject lateinit var trackingWindowResolver: TrackingWindowResolver
    @Inject lateinit var trackingConfiguration: TrackingConfigurationRepository
    @Inject lateinit var firebaseRoomHydrator: FirebaseRoomHydrator
    @Inject lateinit var firebaseEmployeeSessionManager: FirebaseEmployeeSessionManager

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var isManualStopping = false
    private var staffId: String = "unknown"
    private var lastLocation: Location? = null
    private var currentInterval = 30_000L
    private var serverSessionStarted = false
    private var heartbeatJob: Job? = null
    private var sessionJob: Job? = null
    private var policyJob: Job? = null
    private var trackingConfigListener: ValueEventListener? = null
    private var locationHandlerThread: HandlerThread? = null
    private var isForeground = false
    private var locationUpdatesStarted = false
    private var signalRStarted = false
    private val sequenceLock = Any()
    private fun isEmployeeTrackingRole(): Boolean {
        val role = sessionStore.userRole().trim().uppercase()
        return role == "STAFF" || role == "EMPLOYEE"
    }



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
        const val ACTION_REFRESH_WINDOW = "ACTION_REFRESH_WINDOW"
        private const val SHIFT_BOUNDARY_REQUEST = 9913
        // A live GPS fix gets a bounded chance to reach Firebase/SSOT directly.
        // If Firebase does not acknowledge within this window, the same stable
        // event is durably queued in Room for automatic retry.
        private const val DIRECT_GPS_DELIVERY_TIMEOUT_MS = 15_000L
    }

    override fun onCreate() {
        super.onCreate()
        
        // Defensive role boundary. TrackingService may be invoked by recovery after Admin login.
        if (!isEmployeeTrackingRole()) {
            Log.w(
                "TrackingService",
                "Ignoring TrackingService creation for role=${sessionStore.userRole()} " +
                    "employeeId=${sessionStore.employeeId()}"
            )
            stopSelf()
            return
        }
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
        
        if (!isEmployeeTrackingRole()) {
            Log.w(
                "TrackingService",
                "Rejecting start request for non-employee role=${sessionStore.userRole()} " +
                    "employeeId=${sessionStore.employeeId()}"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
                
                // serverSessionStarted = false // REMOVED: Never reset if already true during recovery
                
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putString(KEY_ACTIVE_STAFF, staffId)
                    // putBoolean(KEY_SERVER_STARTED, false) // REMOVED: Preserve server session state
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
                
                startTrackingConfigurationGuard()
        startAttendancePolicyGuard()
                startShiftScheduleGuard()

                // TrackingWindowResolver.resolve() reads the employee shift
                // schedule from Room and is suspendable. Resolve it on the
                // service coroutine instead of blocking onStartCommand().
                serviceScope.launch {
                    val window = trackingWindowResolver.resolve()

                    if (!window.allowed) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                            putBoolean("is_service_active_intended", true)
                            putBoolean("tracking_waiting_for_shift", true)
                        }

                        withContext(Dispatchers.Main) {
                            stopTracking(
                                "OUTSIDE_TRACKING_WINDOW",
                                keepRecovery = true
                            )
                        }
                        return@launch
                    }

                    getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                        putBoolean("tracking_waiting_for_shift", false)
                    }

                    if (!locationUpdatesStarted) {
                        withContext(Dispatchers.Main) {
                            startLocationUpdates()
                        }
                    }

                    // Starting the service and location listener does not itself
                    // create an ACTIVE GPS session. The first genuinely current
                    // online GPS fix establishes/reconciles the authoritative session.

                    // Firebase is the independent realtime transport. Tracking
                    // does not require Payroll.Web, Render, or SignalR.
                    if (!signalRStarted) {
                        signalRStarted = true
                        firebaseSync.startSync()
                        firebaseRoomHydrator.start()
                    }

                    if (heartbeatJob?.isActive != true) {
                        startHeartbeatLoop()
                    }
                    
                    if (sessionJob?.isActive != true) {
                        startSessionGuard()
                    }

                    syncManager.schedulePeriodicSync()
                    scheduleRestartTick()

                    getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                        putBoolean("is_service_active_intended", true)
                    }
                }
            }
            ACTION_REFRESH_WINDOW -> {
                serviceScope.launch { refreshTrackingWindow() }
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

    private fun startTrackingConfigurationGuard() {
        trackingConfigListener = runCatching {
            trackingConfiguration.startRealtimeListener { config -> applyTrackingConfiguration(config) }
        }.getOrNull()
        serviceScope.launch {
            runCatching {
                val config = trackingConfiguration.load()
                applyTrackingConfiguration(config)
            }.onFailure { e ->
                Log.w("TrackingService", "Failed to load tracking config, using cached: ${e.message}")
                applyTrackingConfiguration(trackingConfiguration.cached())
            }
        }
    }

    private fun applyTrackingConfiguration(config: TrackingConfigurationRepository.Config) {
        val newInterval = config.intervalSeconds.coerceIn(15, 3600) * 1000L
        val intervalChanged = currentInterval != newInterval
        currentInterval = newInterval

        if (intervalChanged && locationUpdatesStarted) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { startLocationUpdates() }
            }
        }

        if (!config.enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean("is_service_active_intended", false) }
            serviceScope.launch { withContext(Dispatchers.Main) { stopTracking("TRACKING_DISABLED", keepRecovery = false) } }
            return
        }
        serviceScope.launch { refreshTrackingWindow() }
    }

    private fun startAttendancePolicyGuard() {
        policyJob?.cancel()
        policyJob = serviceScope.launch {
            attendancePolicy.observe().collect { policy ->
                val normalized = policy.normalized()
                if (!normalized.geoFencingEnabled) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                        putBoolean("is_service_active_intended", false)
                    }
                    withContext(Dispatchers.Main) { stopTracking("GEO_FENCING_DISABLED", keepRecovery = false) }
                    return@collect
                }
                val window = trackingWindowResolver.resolve()
                if (!window.allowed && locationUpdatesStarted) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                        putBoolean("is_service_active_intended", true)
                        putBoolean("tracking_waiting_for_shift", true)
                    }
                    scheduleShiftBoundary(window.start)
                    withContext(Dispatchers.Main) { stopTracking("OUTSIDE_TRACKING_WINDOW", keepRecovery = true) }
                } else if (window.allowed) {
                    scheduleShiftBoundary(window.end)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                        putBoolean("tracking_waiting_for_shift", false)
                    }
                }
            }
        }
    }

    private fun startShiftScheduleGuard() {
        serviceScope.launch {
            trackingWindowResolver.observeShiftChanges().collectLatest {
                refreshTrackingWindow()
            }
        }
    }

    private suspend fun refreshTrackingWindow() {
        if (!sessionStore.isLoggedIn()) return
        val window = trackingWindowResolver.resolve()
        if (window.allowed) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putBoolean("is_service_active_intended", true)
                putBoolean("tracking_waiting_for_shift", false)
            }
            scheduleShiftBoundary(window.end)
            if (!locationUpdatesStarted) {
                // Location monitoring may continue while the employee is
                // temporarily offline. A session is created only when a current
                // online GPS event is ready to become live.
                withContext(Dispatchers.Main) { startLocationUpdates() }
            }
        }
else if (locationUpdatesStarted) {
            scheduleShiftBoundary(window.start)
            withContext(Dispatchers.Main) { stopTracking("OUTSIDE_TRACKING_WINDOW", keepRecovery = true) }
        } else {
            scheduleShiftBoundary(window.start)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putBoolean("is_service_active_intended", true)
                putBoolean("tracking_waiting_for_shift", true)
            }
        }
    }

    private fun scheduleShiftBoundary(at: java.time.LocalDateTime?) {
        if (at == null) return
        val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return
        val intent = Intent(this, TrackingNotificationReceiver::class.java).apply {
            action = ACTION_REFRESH_WINDOW
        }
        val pending = PendingIntent.getBroadcast(
            this, SHIFT_BOUNDARY_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
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

    private fun cancelShiftBoundary() {
        val intent = Intent(this, TrackingNotificationReceiver::class.java).apply { action = ACTION_REFRESH_WINDOW }
        val pending = PendingIntent.getBroadcast(this, SHIFT_BOUNDARY_REQUEST, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pending)
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
            val channel = NotificationChannel(CHANNEL_ID, "Critical Performance Sync", NotificationManager.IMPORTANCE_HIGH)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.setSound(null, null)
            channel.enableLights(true)
            channel.lightColor = Color.BLUE
            channel.enableVibration(false)
            channel.setShowBadge(true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val troubleshootIntent = Intent(this, TroubleshootActivity::class.java)
        val troubleshootPendingIntent = PendingIntent.getActivity(this, 1, troubleshootIntent, PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = Intent(this, TrackingNotificationReceiver::class.java).apply {
            action = "ACTION_NOTIFICATION_DISMISSED"
        }
        val deletePendingIntent = PendingIntent.getBroadcast(this, 0, deleteIntent, PendingIntent.FLAG_IMMUTABLE)

        // Swiggy-level authoritative notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logs) 
            .setContentTitle("BioMetric: Continuous Sync Active ⚡")
            .setContentText("Keeping your workspace synchronization alive 24/7.")
            .setSubText("Tracking status: Authoritative 🛰️")
            .setPriority(NotificationCompat.PRIORITY_MAX) 
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) 
            .setOngoing(true)
            .setSilent(true) 
            .setLocalOnly(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .addAction(R.drawable.ic_warning_triangle, "Troubleshoot", troubleshootPendingIntent)
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
        currentInterval = trackingConfiguration.cached().intervalSeconds.coerceIn(15, 3600) * 1000L
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
        // Reject (0.0, 0.0) "Null Island" — device has not acquired a GPS fix yet.
        // Web admin's LiveStaffLocationPanel also drops (0,0), so never upload them.
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            offlineMonitor.record(OfflineTrackingMonitor.DATA_INTEGRITY_WARNING, OfflineTrackingMonitor.WARNING, "Null Island (0,0) coordinate rejected — GPS not yet fixed")
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
            putFloat("last_bearing", location.bearing)
        }
        
        processLocationCapture(location)
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

    private fun processLocationCapture(location: Location) {
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sessionId = sessionStore.gpsSessionId()
        val clientEventId = UUID.randomUUID().toString()

        serviceScope.launch {
            val nextSequence = nextGpsSequence(sessionId)
            val capture = LocalLocation(
                clientEventId = clientEventId,
                sessionId = sessionId,
                sequence = nextSequence,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed,
                bearing = location.bearing,
                batteryLevel = battery,
                // CRITICAL: preserve the original GPS event time.
                timestamp = location.time,
                capturedElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
                // A capture is only marked offline when it must enter the local
                // retry queue. Room is a temporary delivery queue, never SSOT.
                isOfflineCapture = false
            )

            val delivered = try {
                withTimeoutOrNull(DIRECT_GPS_DELIVERY_TIMEOUT_MS) {
                    deliverLocationDirectToSsot(capture)
                } ?: false
            } catch (e: Exception) {
                Log.w(
                    "TrackingService",
                    "Direct Firebase GPS delivery failed; queueing for retry",
                    e
                )
                false
            }

            if (delivered) {
                // Firebase/SSOT acknowledged the stable ClientEventId.
                // Do not route successful online GPS records through Room.
                return@launch
            }

            queueLocationForRetry(capture)
        }
    }

    /**
     * Primary GPS delivery path.
     *
     * Normal flow is always:
     * GPS -> Firebase/SSOT -> dashboards.
     * Room is entered only after this method fails to receive an acknowledgement.
     */
    private suspend fun deliverLocationDirectToSsot(location: LocalLocation): Boolean {
        val effectiveSessionId = ensureFirebaseGpsSessionStarted(location.sessionId)
            ?: return false

        val uploaded = firebaseSync.pushLiveLocation(
            employeeId = sessionStore.employeeId(),
            sessionId = effectiveSessionId,
            clientEventId = location.clientEventId,
            sequence = location.sequence,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            speed = location.speed.toDouble(),
            bearing = location.bearing.toDouble(),
            batteryLevel = location.batteryLevel,
            timestamp = location.timestamp,
            isOffline = false
        )

        if (uploaded) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putLong(KEY_LAST_SERVER_AT, System.currentTimeMillis())
                putString(KEY_LAST_STATUS, "Active")
            }

            offlineMonitor.record(
                OfflineTrackingMonitor.UPLOAD_SUCCESS,
                OfflineTrackingMonitor.INFO,
                "GPS fix ${location.sequence} published directly to Firebase/SSOT",
                sessionId = effectiveSessionId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                correlationId = location.clientEventId
            )
        }

        return uploaded
    }

    /**
     * Called only after Firebase/SSOT did not acknowledge the GPS record.
     * The same ClientEventId is retained so retrying is idempotent.
     */
    private suspend fun queueLocationForRetry(capture: LocalLocation) {
        val pending = capture.copy(
            syncState = LocalLocation.SYNC_PENDING,
            isOfflineCapture = true,
            lastError = "SSOT_NOT_ACKNOWLEDGED",
            createdAt = System.currentTimeMillis()
        )

        runCatching {
            val insertedId = locationDao.insert(pending)
            if (insertedId > 0L) {
                offlineMonitor.record(
                    OfflineTrackingMonitor.QUEUE_ENQUEUED,
                    OfflineTrackingMonitor.WARNING,
                    "Firebase/SSOT did not acknowledge GPS fix; temporarily queued in Room",
                    sessionId = pending.sessionId,
                    latitude = pending.latitude,
                    longitude = pending.longitude,
                    accuracy = pending.accuracy,
                    correlationId = pending.clientEventId
                )

                // WorkManager has the network constraint and automatic retry.
                // New GPS capture continues independently while this queue drains.
                OfflineSyncWorker.schedule(this@TrackingService)
            }
        }.onFailure { ex ->
            Log.e(
                "TrackingService",
                "CRITICAL: unable to persist undelivered GPS fix in temporary Room queue",
                ex
            )
            offlineMonitor.record(
                OfflineTrackingMonitor.DATA_INTEGRITY_WARNING,
                OfflineTrackingMonitor.ERROR,
                "Unable to persist undelivered GPS fix locally: ${ex.message}",
                sessionId = capture.sessionId,
                latitude = capture.latitude,
                longitude = capture.longitude,
                accuracy = capture.accuracy,
                correlationId = capture.clientEventId
            )
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                // Heartbeat ensures the authoritative employee_sessions node 
                // remains current even if the device is stationary.
                firebaseEmployeeSessionManager.touch()
                delay(60_000L)
            }
        }
    }

    private fun startSessionGuard() {
        sessionJob?.cancel()
        sessionJob = serviceScope.launch {
            firebaseEmployeeSessionManager.observeSessionActive().collectLatest { active ->
                if (!active && !isManualStopping) {
                    Log.w("TrackingService", "Authoritative device session mismatch; stopping background tracking.")
                    withContext(Dispatchers.Main) {
                        stopTracking("SESSION_CONFLICT", keepRecovery = false)
                    }
                }
            }
        }
    }

    private suspend fun ensureFirebaseGpsSessionStarted(sessionId: String): String? {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0 || sessionId.isBlank()) return null

        var effectiveSessionId = sessionId
        val remoteState = firebaseSync.getTrackingSessionState(
            employeeId = employeeId,
            sessionId = effectiveSessionId
        )
        val liveSessionId = firebaseSync.getTrackingLiveSessionId(employeeId)

        // Rotate only when the remote session is explicitly ENDED.
        // A "MISSING" state is expected before the session is first created in Firebase.
        if (remoteState.equals("ENDED", ignoreCase = true)) {
            effectiveSessionId = UUID.randomUUID().toString()
            sessionStore.setGpsSessionId(effectiveSessionId)
            serverSessionStarted = false

            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putBoolean(KEY_SERVER_STARTED, false)
            }

            Log.i(
                "TrackingService",
                "Rotating GPS session because remote state is ENDED. EmployeeId=$employeeId oldSession=$sessionId newSession=$effectiveSessionId remoteState=$remoteState"
            )

            if (sessionId.isNotBlank() && sessionId != effectiveSessionId) {
                firebaseSync.pushTrackingSessionEnded(employeeId, sessionId, "SESSION_ROTATED")
            }
        }

        val isAlreadyActive = remoteState.equals("ACTIVE", ignoreCase = true) && sessionId == effectiveSessionId
        if (isAlreadyActive) {
            return effectiveSessionId
        }

        val started = firebaseSync.pushTrackingSessionStarted(
            employeeId = employeeId,
            sessionId = effectiveSessionId
        )

        return if (started) effectiveSessionId else null
    }

    private fun queueTrackingLifecycleEvent(
        eventType: String,
        sessionId: String,
        message: String
    ) {
        if (sessionId.isBlank()) return

        serviceScope.launch {
            runCatching {
                eventDao.insert(
                    OfflineTrackingEvent(
                        eventId = UUID.randomUUID().toString(),
                        eventTime = System.currentTimeMillis(),
                        eventType = eventType,
                        severity = OfflineTrackingMonitor.WARNING,
                        message = message.take(500),
                        sessionId = sessionId,
                        networkAvailable = offlineMonitor.isOnline(),
                        queueDepth = locationDao.getPendingCount()
                    )
                )
                OfflineSyncWorker.schedule(this@TrackingService)
            }.onFailure {
                Log.w(
                    "TrackingService",
                    "Unable to durably queue tracking lifecycle event: $eventType",
                    it
                )
            }
        }
    }

    private fun stopTracking(endReason: String = "LOGGED_OUT", keepRecovery: Boolean = false) {
        isManualStopping = !keepRecovery
        heartbeatJob?.cancel()

        // REQUIREMENT: "never automatically session ended".
        // If keepRecovery is true (e.g. shift transition, restart tick), we 
        // DO NOT end the Firebase session. This keeps the session continuous 
        // in the Admin timeline. Only manual logout or conflict ends it.
        if (!keepRecovery) {
            val activeEmployeeId = sessionStore.employeeId()
            val activeSessionId = sessionStore.currentGpsSessionId()

            if (activeEmployeeId > 0 && !activeSessionId.isNullOrBlank()) {
                // Logout is authoritative. Wait for the Firebase session-end 
                // signal to be sent before terminating the background service.
                serviceScope.launch {
                    try {
                        firebaseSync.pushTrackingSessionEnded(
                            employeeId = activeEmployeeId,
                            sessionId = activeSessionId,
                            endReason = endReason
                        )
                    } catch (e: Exception) {
                        Log.w("TrackingService", "Tracking session end publish failed", e)
                        queueTrackingLifecycleEvent(
                            OfflineTrackingEvent.SESSION_ENDED,
                            activeSessionId,
                            "Tracking session end deferred. Reason: ${endReason.take(100)}"
                        )
                    } finally {
                        withContext(Dispatchers.Main) {
                            finalizeStop()
                        }
                    }
                }
            } else {
                finalizeStop()
            }
            sessionStore.clearGpsSession()
        } else {
            finalizeStop()
        }
    }

    private fun finalizeStop() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
            putBoolean("is_service_active_intended", !isManualStopping)
            putBoolean("tracking_waiting_for_shift", !isManualStopping)
        }
        if (isManualStopping) {
            cancelRestartTick()
            cancelShiftBoundary()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        trackingConfiguration.removeRealtimeListener(trackingConfigListener)
        trackingConfigListener = null
        policyJob?.cancel()
        heartbeatJob?.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesStarted = false
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        offlineMonitor.record(OfflineTrackingMonitor.TRACKING_STOPPED, OfflineTrackingMonitor.INFO, "Tracking service destroyed")
        offlineMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
