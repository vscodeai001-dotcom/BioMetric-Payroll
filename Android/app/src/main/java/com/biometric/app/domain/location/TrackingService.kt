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
    private val latestLocationChannel = Channel<Location>(Channel.CONFLATED)
    private var gpsUploadJob: Job? = null
    private var heartbeatJob: Job? = null
    private var locationHandlerThread: HandlerThread? = null
    private var isForeground = false
    private var locationUpdatesStarted = false
    private var signalRStarted = false

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
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        
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
        if (qualityManager.isMockLocation(location)) return
        
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

    private fun saveLocationToLocalQueue(location: Location) {
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // The location callback must return immediately. Room/network work stays
        // entirely off the callback/main thread, and server uploads are coalesced
        // to one worker so slow network calls cannot pile up.
        serviceScope.launch {
            runCatching {
                locationDao.insert(
                    LocalLocation(
                        sessionId = sessionStore.gpsSessionId(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        batteryLevel = battery
                    )
                )
            }.onFailure { ex ->
                Log.d("TrackingService", "Local GPS save deferred: ${ex.message}")
            }
        }

        // Non-blocking. If several fixes arrive while the network is busy, only
        // the newest fix is retained for the server upload.
        latestLocationChannel.trySend(location)
        startGpsUploadWorker()
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
                            Log.d("TrackingService", "Heartbeat success 💓")
                        } else if (response.code() == 401) {
                            Log.w("TrackingService", "Heartbeat 401: Authoritative session lost ⚠️")
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

    private suspend fun uploadLocationToServer(location: Location) {
        if (!ensureServerSession()) return

        val token = sessionStore.token() ?: return
        var sessionId = sessionStore.gpsSessionId()
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        try {
            var response = mobileApi.updateGps(
                "Bearer $token",
                GpsUpdateRequest(
                    sessionId = sessionId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    speed = location.speed.toDouble(),
                    timestamp = System.currentTimeMillis(),
                    batteryLevel = battery
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

                if (!ensureServerSession()) return

                response = mobileApi.updateGps(
                    "Bearer $token",
                    GpsUpdateRequest(
                        sessionId = sessionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        speed = location.speed.toDouble(),
                        timestamp = System.currentTimeMillis(),
                        batteryLevel = battery
                    )
                )
            }

            if (response.isSuccessful) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putLong(KEY_LAST_SERVER_AT, System.currentTimeMillis())
                    putString(KEY_LAST_STATUS, "Active")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
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
        serviceScope.cancel()
        super.onDestroy()
    }
}
