package com.biometric.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.airbnb.lottie.LottieAnimationView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.ColorMatrixColorFilter
import android.graphics.Path
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.EmployeeDashboardResponse
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.domain.attendance.EmployeeAttendanceStateMachine
import com.biometric.app.domain.attendance.AttendancePolicyRepository
import com.biometric.app.databinding.ActivityEmployeeHomeBinding
import com.biometric.app.domain.location.TrackingService
import com.biometric.app.sync.SignalRManager
import com.biometric.app.sync.FirebaseEmployeeSessionManager
import com.biometric.app.ui.selfservice.*
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.BatteryOptimizationHelper
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.OemBackgroundHelper
import com.biometric.app.util.PolylineDecoder
import com.biometric.app.util.MarkerAnimationHelper
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import org.osmdroid.tileprovider.cachemanager.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class EmployeeHomeActivity : MotionBaseActivity() {
    private var _binding: ActivityEmployeeHomeBinding? = null
    /**
     * Binding is accessed directly only from lifecycle-safe UI methods.
     * Asynchronous callbacks use _binding?. / lifecycle guards.
     *
     * Keeping this getter non-null preserves all existing direct binding calls
     * and prevents the Kotlin nullable-receiver compiler cascade introduced
     * by a nullable binding property.
     */
    private val binding: ActivityEmployeeHomeBinding
        get() = checkNotNull(_binding) {
            "EmployeeHomeActivity binding is not available"
        }

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository
    @Inject lateinit var firebaseEmployeeSessionManager: FirebaseEmployeeSessionManager
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var attendancePolicy: AttendancePolicyRepository
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var osrmApi: OsrmApiService

    private var officeMarker: Marker? = null
    private var userMarker: Marker? = null
    private var rangeCircle: Polygon? = null
    private var routePolyline: Polyline? = null
    private var routeCasing: Polyline? = null
    private var isAutoFocusEnabled = true
    private val iconCache = mutableMapOf<String, Drawable>()
    private var lastRoadRouteUpdate: Long = 0L

    private var showRouteToOffice = false

    private var officeLat: Double = 0.0
    private var officeLon: Double = 0.0
    private var geoRadius: Int = 100

    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentAccuracy: Float = 0f
    private var currentSpeed: Float = 0f

    private var uiUpdateJob: Job? = null
    private var initJob: Job? = null
    private var roadRouteJob: Job? = null
    private var dashboardJob: Job? = null
    private var policyJob: Job? = null
    private var dashboardRetryJob: Job? = null
    private var dashboardAuthRecoveryInProgress = false
    private var sessionStartTime: Long = 0L
    private var isPermissionDialogShowing = false
    private var isActivityUiActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            startTracking()
        } else {
            showLocationPermissionMessage()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestLocationPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Requirement: Offline Map Caching
        // Reduced cache size for better stability on lower-end devices
        OsmConfig.getInstance().tileDownloadThreads = 4
        OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200 * 1024 * 1024L // 200MB

        _binding = ActivityEmployeeHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clEmployeeHomeRoot, binding.appBar)

        if (!sessionStore.isLoggedIn()) {
            goToLogin()
            return
        }

        binding.tvGreeting.text = "Hello, ${sessionStore.employeeName().ifBlank { "Employee" }}! 👋 ✨"
        binding.tvMapUserLabel.text = sessionStore.employeeName().ifBlank { "Live Tracker 🛰️ 📍" }

        // Setup Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = null
        setupDualHeader(binding.toolbar, "Staff Portal 🏢 ✨", sessionStore.employeeName().ifBlank { "Employee Dashboard 👤 💎" })

        setupToolbar()
        setupClickListeners()
        setupEmployeeMapControls()

        // UX: Immediate local settings restoration
        restoreOfficeSettings()

        initJob?.cancel()
        initJob = lifecycleScope.launch {
            // Start UI/realtime work without artificial startup waits.
            loadDashboard()
        observeAttendancePolicy()
            checkBatteryOptimizations()
            setupMap()
            setupRealTimeSync()
            sharedViewModel.warmUpDashboard()

            // Low-priority animations last
            findViewById<LottieAnimationView>(R.id.backgroundParticles)?.let {
                it.visibility = View.VISIBLE
                it.playAnimation()
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = null
        setupDualHeader(binding.toolbar, "Staff Portal 🏢", sessionStore.employeeName().ifBlank { "Employee Dashboard" })
    }

    private fun restoreOfficeSettings() {
        val prefs = getSharedPreferences("office_settings", MODE_PRIVATE)
        officeLat = prefs.getFloat("lat", 0f).toDouble()
        officeLon = prefs.getFloat("lon", 0f).toDouble()
        geoRadius = prefs.getInt("radius", 0)

        if (officeLat != 0.0 && officeLon != 0.0 && geoRadius > 0) {
            updateMapMarkers()
            updateRangeStatus()
        }
    }

    private fun setupRealTimeSync() {
        signalR.start()
        
        // Single-Device Lock: Observe if another device logs in and take 
        // authoritative action to invalidate the current local session.
        lifecycleScope.launch {
            firebaseEmployeeSessionManager.observeSessionActive().collectLatest { active ->
                if (!active && sessionStore.isLoggedIn()) {
                    Log.w("EmployeeHome", "Authoritative device session mismatch: another device has taken ownership of this employee account. Logging out.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EmployeeHomeActivity, "Session expired: logged in on another device.", Toast.LENGTH_LONG).show()
                        logout()
                    }
                }
            }
        }

        lifecycleScope.launch {
            selfService.changesFlow().collectLatest {
                if (_binding != null) loadDashboard()
            }
        }
        lifecycleScope.launch {
            signalR.dataChangeEvents.collectLatest { event ->
                // Stable Flow API: coalesce a burst of realtime events without preview debounce API.
                delay(500L)
                Log.i("EmployeeHome", "Real-time update received from SignalR: $event 🛰️")
                _binding?.let {
                    when (event) {
                            is SignalRManager.SyncEvent.SessionEnded -> {
                                val currentEmpId = sessionStore.employeeId()
                                val currentSessionId = sessionStore.gpsSessionId()
                                Log.i("EmployeeHome", "SessionEnded event: emp=${event.employeeId}, session=${event.sessionId}. Local: emp=$currentEmpId, session=$currentSessionId")

                                if (event.employeeId > 0 && event.employeeId == currentEmpId) {
                                    val reason = event.endReason.orEmpty()
                                    val explicitTermination = reason.equals("FORCE_LOGGED_OUT", true) ||
                                        reason.equals("REPLACED_BY_NEW_DEVICE", true) ||
                                        reason.equals("MANUAL_LOGOUT", true)

                                    if (event.sessionId.equals(currentSessionId, true) && explicitTermination) {
                                        Log.w("EmployeeHome", "Authoritative session termination: $reason. Returning to login because this is an explicit manual/second-device termination.")
                                        goToLogin()
                                    } else {
                                        Log.i("EmployeeHome", "GPS session ended/rebased without authentication termination. reason=$reason. Keeping mobile login alive.")
                                    }
                                }
                            }
                            is SignalRManager.SyncEvent.GeoSettingsChanged -> {
                                val data = event.settings
                                if (data.geoRadiusMeters > 0) {
                                    if (data.officeLatitude != 0.0 && data.officeLongitude != 0.0) {
                                        officeLat = data.officeLatitude
                                        officeLon = data.officeLongitude
                                    }
                                    geoRadius = data.geoRadiusMeters

                                    getSharedPreferences("office_settings", MODE_PRIVATE).edit {
                                        putFloat("lat", officeLat.toFloat())
                                        putFloat("lon", officeLon.toFloat())
                                        putInt("radius", geoRadius)
                                    }

                                    Log.i("EmployeeHome", "Applied realtime geo settings: radius=${geoRadius}m 🏢")
                                    updateMapMarkers()
                                    updateRangeStatus()
                                }
                            }
                            is SignalRManager.SyncEvent.BonusChanged,
                            is SignalRManager.SyncEvent.TaxDeclarationChanged,
                            is SignalRManager.SyncEvent.LocationChanged -> {
                                val currentEmpId = sessionStore.employeeId()
                                signalR.liveLocations.value[currentEmpId]?.let { loc ->
                                    if (loc.allowedRadiusMeters > 0 && loc.allowedRadiusMeters != geoRadius) {
                                        Log.i("EmployeeHome", "Updating geo-radius from real-time event: ${loc.allowedRadiusMeters}m 🛰️")
                                        geoRadius = loc.allowedRadiusMeters
                                        updateMapMarkers()
                                    }
                                }
                                sharedViewModel.warmUpDashboard()
                                loadDashboard()
                            }
                            else -> {
                                Log.d("EmployeeHome", "Real-time refresh for dashboard: $event 🔄")
                                sharedViewModel.warmUpDashboard()
                                loadDashboard()
                            }
                        }
                    }
                }
        }
    }

    /**
     * Builds a BoundingBox directly to avoid osmdroid's deprecated
     * BoundingBox.fromGeoPoints(List<GeoPoint>) Java overload.
     */
    private fun createBoundingBox(points: List<GeoPoint>): BoundingBox? {
        if (points.isEmpty()) return null

        var north = points.first().latitude
        var south = north
        var east = points.first().longitude
        var west = east

        for (point in points.drop(1)) {
            north = maxOf(north, point.latitude)
            south = minOf(south, point.latitude)
            east = maxOf(east, point.longitude)
            west = minOf(west, point.longitude)
        }

        return BoundingBox(north, east, south, west)
    }

    private fun setupMap() {
        binding.mapview.apply {
            setTileSource(employeeOpenStreetMapSource())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.0)

            applyCurrentThemeToMap()
        }

        lifecycleScope.launch {
            binding.llMapLoading.visibility = View.GONE

            // Requirement: Silently pre-download and cache map tiles for the office zone
            if (officeLat != 0.0) {
                preCacheMapTiles()
            }
        }
    }

    private fun preCacheMapTiles() {
        val mapView = _binding?.mapview ?: return
        val cacheManager = CacheManager(mapView)
        val boundingBox = BoundingBox(officeLat + 0.02, officeLon + 0.02, officeLat - 0.02, officeLon - 0.02)

        // Requirement: Silently pre-download and cache map tiles for the office zone.
        // CacheManager must be invoked on a thread with a Looper for internal Handler.
        // We use the main thread for the initial call, and it handles its own workers.
        try {
            cacheManager.downloadAreaAsync(this@EmployeeHomeActivity, boundingBox, 15, 18)
        } catch (e: Exception) {
            Log.e("EmployeeHome", "Map pre-cache failed: ${e.message}")
        }
    }

    private fun applyCurrentThemeToMap() {
        val mapView = _binding?.mapview ?: return
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            // Refined Night Mode Filter: Inverted, slightly blue-tinted, lower contrast for premium feel
            mapView.overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(floatArrayOf(
                    0.25f, 0f, 0f, 0f, 0f,
                    0f, 0.25f, 0f, 0f, 0f,
                    0f, 0f, 0.25f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
    }

    private fun setupClickListeners() {
        binding.btnPunch.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            attemptPunch() 
        }
        binding.btnViewAttendanceLogs.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("attendance") 
        }
        binding.btnViewPayslips.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("payslips") 
        }

        binding.btnAttendance.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("attendance") 
        }
        binding.btnLeaves.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("leaves") 
        }
        binding.btnPayslips.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("payslips") 
        }
        binding.btnAdvances.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("advances") 
        }
        binding.btnBonuses.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("bonuses") 
        }
        binding.btnCorrection.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("correction") 
        }
        binding.btnResignation.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("resignation") 
        }
        binding.btnShifts.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("shifts") 
        }
        binding.btnTax.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("tax") 
        }
        binding.btnFbp.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("fbp") 
        }

        binding.btnSettings.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            openSelfService("profile") 
        }

        binding.cvTroubleshoot.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, TroubleshootActivity::class.java))
        }

        binding.btnLogout.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            confirmLogout() 
        }
    }

    private fun openSelfService(key: String) {
        val targetActivity = when(key) {
            "attendance" -> AttendanceLogsActivity::class.java
            "leaves" -> MyLeavesActivity::class.java
            "payslips" -> PayslipListActivity::class.java
            "advances" -> SalaryAdvancesActivity::class.java
            "bonuses" -> BonusesActivity::class.java
            "correction" -> MyRegularizationsActivity::class.java
            "resignation" -> ResignationActivity::class.java
            "shifts" -> ShiftScheduleActivity::class.java
            "tax" -> TaxDeclarationActivity::class.java
            "fbp" -> FbpDeclarationActivity::class.java
            "profile" -> MyReportsActivity::class.java // Generic container
            else -> AttendanceLogsActivity::class.java
        }

        startActivity(Intent(this, targetActivity).apply {
            putExtra("FRAGMENT_TYPE", key)
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, showShopSwitcher = false, activity = this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) {
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun employeeOpenStreetMapSource(): OnlineTileSourceBase =
        object : OnlineTileSourceBase(
            "OSM Standard",
            0,
            20,
            256,
            ".png",
            arrayOf("https://tile.openstreetmap.org/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return getBaseUrl() +
                        org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) +
                        mImageFilenameEnding
            }
        }

    private fun setupEmployeeMapControls() {
        binding.btnEmployeeMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
        binding.btnEmployeeMapFollow.setOnClickListener {
            isAutoFocusEnabled = !isAutoFocusEnabled
            binding.btnEmployeeMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
            Toast.makeText(this, if (isAutoFocusEnabled) "Auto-follow enabled ⦿" else "Auto-follow disabled ◌", Toast.LENGTH_SHORT).show()
            
            if (isAutoFocusEnabled && currentLat != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(currentLat, currentLon))
            }
        }
        binding.btnEmployeeMapRoute.setOnClickListener {
            isAutoFocusEnabled = false
            binding.btnEmployeeMapFollow.alpha = 0.4f
            showRouteToOffice = true
            if (currentLat != 0.0 && currentLon != 0.0 && officeLat != 0.0 && officeLon != 0.0) {
                val points = listOf(GeoPoint(currentLat, currentLon), GeoPoint(officeLat, officeLon))
                createBoundingBox(points)?.let { binding.mapview.zoomToBoundingBox(it, true, 120) }
                updateRoadRoute(GeoPoint(officeLat, officeLon), GeoPoint(currentLat, currentLon))
            }
        }
        binding.btnEmployeeMapOffice.setOnClickListener {
            isAutoFocusEnabled = false
            showRouteToOffice = true
            if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(officeLat, officeLon))
                binding.mapview.controller.setZoom(16.0)
                if (currentLat != 0.0) {
                    updateRoadRoute(GeoPoint(officeLat, officeLon), GeoPoint(currentLat, currentLon))
                }
            }
        }
        binding.btnEmployeeMapLayers.setOnClickListener {
            val next = ((binding.mapview.tag as? Int ?: 0) + 1) % 4
            binding.mapview.tag = next
            when (next) {
                0 -> {
                    binding.mapview.setTileSource(TileSourceFactory.MAPNIK)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                1 -> {
                    binding.mapview.setTileSource(TileSourceFactory.USGS_SAT)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                2 -> {
                    binding.mapview.setTileSource(TileSourceFactory.OpenTopo)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                else -> {
                    binding.mapview.setTileSource(TileSourceFactory.MAPNIK)
                    applyDarkThemeFilter(binding.mapview)
                }
            }
            binding.mapview.invalidate()
        }
        binding.btnEmployeeMapFullscreen.setOnClickListener {
            val intent = Intent(this, TrackingMapActivity::class.java)
            intent.putExtra("EMPLOYEE_ID", sessionStore.employeeId())
            startActivity(intent)
        }
        binding.mapview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.mapview.post {
                binding.mapview.invalidate()
            }
        }
    }

    private fun applyDarkThemeFilter(mapView: MapView) {
        mapView.overlayManager.tilesOverlay.setColorFilter(
            ColorMatrixColorFilter(
                floatArrayOf(
                    0.25f, 0f, 0f, 0f, 0f,
                    0f, 0.25f, 0f, 0f, 0f,
                    0f, 0f, 0.25f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    private fun updateMapMarkers() {
        _binding?.let { b ->
            val mapView = b.mapview
            if (officeLat == 0.0 || officeLon == 0.0) return

            val officePoint = GeoPoint(officeLat, officeLon)

            if (officeMarker == null) {
                officeMarker = Marker(mapView).apply {
                    position = officePoint
                    title = "🏢 Office Hub"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createPremiumOfficeIcon()
                }
                mapView.overlays.add(officeMarker)

                // Range Circle (Geofence)
                rangeCircle = Polygon(mapView).apply {
                    points = Polygon.pointsAsCircle(officePoint, geoRadius.toDouble())
                    fillPaint.color = 0x1510B981.toInt()
                    outlinePaint.color = 0x5010B981.toInt()
                    outlinePaint.strokeWidth = 2f
                }
                mapView.overlays.add(rangeCircle)

                mapView.controller.setCenter(officePoint)
            } else {
                officeMarker?.position = officePoint
                rangeCircle?.points = Polygon.pointsAsCircle(officePoint, geoRadius.toDouble())
            }

            if (currentLat != 0.0) {
                val userPoint = GeoPoint(currentLat, currentLon)
                val distanceResults = FloatArray(1)
                Location.distanceBetween(officeLat, officeLon, currentLat, currentLon, distanceResults)
                val distance = distanceResults[0]
                val within = distance <= geoRadius

                if (userMarker == null) {
                    userMarker = Marker(mapView).apply {
                        position = userPoint
                        title = sessionStore.employeeName()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(userMarker)
                } else {
                    animateMarkerMovement(userMarker!!, userPoint)
                }

                // Identity Pin with initials and status dot
                val initials = getInitials(sessionStore.employeeName())
                val cacheKey = "${initials}_${within}_Live"
                val icon = iconCache.getOrPut(cacheKey) {
                    createPremiumMarkerIcon(initials, within, "Live")
                }
                userMarker?.icon = icon
                userMarker?.snippet = "Speed: ${formatSpeed(currentSpeed.toDouble())}\nDist: ${formatDistance(distance.toDouble())}"

                // REQUIREMENT: Only show route if explicitly enabled
                if (showRouteToOffice) {
                    // Swiggy Style Polyline (Road Snapped)
                    updateRoadRoute(officePoint, userPoint)
                } else {
                    routePolyline?.let { it.outlinePaint.alpha = 0 }
                    routeCasing?.let { it.outlinePaint.alpha = 0 }
                }

                b.tvRemainingDist.text = if (distance < 1000) "${distance.toInt()} m" else String.format(Locale.US, "%.1f km", distance / 1000.0)
                val etaSec = (distance / 1.4).toInt()
                b.tvEta.text = if (etaSec < 60) "Soon" else "${etaSec / 60} min"

                // Do not refit the map on every GPS sample. Auto-follow already
                // moves the camera smoothly when enabled; repeated fitBounds-style
                // zooming causes visual jumping/flicker.
                if (isAutoFocusEnabled && isActivityUiActive) {
                    try {
                        mapView.controller.animateTo(userPoint)
                    } catch (_: Exception) {
                        // Ignore a lifecycle/map transition race.
                    }
                }
            }

            mapView.invalidate()
        }
    }

    private fun getInitials(name: String): String {
        val parts = name.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0][0]}${parts.last()[0]}".uppercase() else name.take(1).uppercase()
    }

    private fun createPremiumOfficeIcon(): Drawable {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Shadow
        paint.color = Color.parseColor("#40000000")
        canvas.drawRoundRect(10f, 10f, 75f, 75f, 18f, 18f, paint)

        // Background (Blue Gradient feel)
        paint.color = Color.parseColor("#4F46E5")
        canvas.drawRoundRect(5f, 5f, 70f, 70f, 18f, 18f, paint)

        // Border
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        canvas.drawRoundRect(5f, 5f, 70f, 70f, 18f, 18f, paint)

        // Building Icon
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(25f, 50f); path.lineTo(25f, 25f); path.lineTo(50f, 25f); path.lineTo(50f, 50f); path.close()
        path.moveTo(32f, 32f); path.lineTo(38f, 32f); path.lineTo(38f, 38f); path.lineTo(32f, 38f); path.close()
        canvas.drawPath(path, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun createPremiumMarkerIcon(initials: String, within: Boolean, status: String): Drawable {
        val color = if (within) "#3B82F6".toColorInt() else "#EF4444".toColorInt()
        val bitmap = Bitmap.createBitmap(100, 130, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Pin Shadow
        paint.color = Color.parseColor("#40000000")
        canvas.drawCircle(50f, 120f, 15f, paint)

        paint.color = color
        val path = Path()
        path.moveTo(50f, 130f); path.cubicTo(100f, 80f, 100f, 10f, 50f, 10f); path.cubicTo(0f, 10f, 0f, 80f, 50f, 130f); canvas.drawPath(path, paint)

        // Inner Circle
        paint.color = Color.WHITE
        canvas.drawCircle(50f, 55f, 35f, paint)

        // Initials
        paint.color = color
        paint.textSize = 34f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText(initials, 50f, 66f, paint)

        // Status Dot at Bottom-Right of Bulb
        val sColor = when(status) { "Live" -> "#22C55E".toColorInt(); "Stale" -> "#F59E0B".toColorInt(); else -> "#94A3B8".toColorInt() }
        paint.color = Color.WHITE
        canvas.drawCircle(82f, 82f, 14f, paint) // Outer glow
        paint.color = sColor
        canvas.drawCircle(82f, 82f, 10f, paint) // Actual dot

        return BitmapDrawable(resources, bitmap)
    }

    private fun formatSpeed(mps: Double): String {
        if (mps <= 0.15) return "Stationary"
        val kmh = mps * 3.6
        return if (kmh < 1) "Slow" else "${kmh.toInt()} km/h"
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()}m" else String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

    private fun animateMarkerMovement(marker: Marker, toPosition: GeoPoint) {
        if (!isActivityUiActive || isFinishing || isDestroyed || _binding == null) return

        val bearing = getSharedPreferences(
            "tracking_prefs",
            MODE_PRIVATE
        ).getFloat("last_bearing", 0f)

        MarkerAnimationHelper.animateMarker(
            marker,
            toPosition,
            bearing,
            sessionStore.employeeId()
        ) { animatedPoint ->
            // Animation frames can race Activity teardown. Never dereference
            // the ViewBinding unless the Activity is still active and attached.
            if (!isActivityUiActive || isFinishing || isDestroyed) return@animateMarker

            val b = _binding ?: return@animateMarker

            runCatching {
                routePolyline?.let { line ->
                    val pts = line.actualPoints.toMutableList()
                    if (pts.size >= 2) {
                        pts[0] = animatedPoint
                        line.setPoints(pts)
                    }
                }

                routeCasing?.let { line ->
                    val pts = line.actualPoints.toMutableList()
                    if (pts.size >= 2) {
                        pts[0] = animatedPoint
                        line.setPoints(pts)
                    }
                }

                b.mapview.invalidate()
            }.onFailure {
                // A lifecycle transition can still race the animation frame.
                Log.d("EmployeeHome", "Marker animation frame ignored: ${it.message}")
            }
        }

        if (isActivityUiActive && isAutoFocusEnabled && !isFinishing && !isDestroyed) {
            _binding?.mapview?.controller?.animateTo(toPosition)
        }
    }

    private fun updateRoadRoute(office: GeoPoint, user: GeoPoint) {
        val mapView = _binding?.mapview ?: return

        // Optimization: Throttle OSRM API calls to every 30s to avoid OOM and network overhead
        if (System.currentTimeMillis() - lastRoadRouteUpdate < 30000L) return

        if (routeCasing == null) {
            routeCasing = Polyline(mapView).apply {
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 18f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.alpha = 180
            }
            mapView.overlays.add(0, routeCasing)
        }

        if (routePolyline == null) {
            routePolyline = Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#4F46E5")
                outlinePaint.strokeWidth = 12f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            mapView.overlays.add(1, routePolyline)
        }

        roadRouteJob?.cancel()
        roadRouteJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Yield to check for cancellation immediately
                yield()

                // OSRM expects: longitude,latitude;longitude,latitude
                val coords = "${user.longitude},${user.latitude};${office.longitude},${office.latitude}"
                val response = osrmApi.getRoute(coords)

                if (!isActive) return@launch

                if (response.isSuccessful) {
                    val body = response.body()
                    val encoded = body?.routes?.firstOrNull()?.geometry
                    if (encoded != null) {
                        val decoded = PolylineDecoder.decode(encoded)
                        withContext(Dispatchers.Main) {
                            if (!isActivityUiActive || isFinishing || isDestroyed) return@withContext
                            _binding?.let { b ->
                                routeCasing?.setPoints(decoded)
                                routePolyline?.setPoints(decoded)

                                // Update Road Distance UI
                                body.routes.firstOrNull()?.distance?.let { distMeters ->
                                    b.tvRoadDistance.text = if (distMeters < 1000) "${distMeters.toInt()} m" else String.format(Locale.US, "%.2f km", distMeters / 1000.0)
                                }

                                b.mapview.invalidate()
                                lastRoadRouteUpdate = System.currentTimeMillis()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("EmployeeHome", "Road routing failed: ${e.message}")
                // Never render an office-to-employee straight-line fallback.
                // Hide the route until authentic road geometry is available.
                withContext(Dispatchers.Main) {
                    _binding?.let { b ->
                        routeCasing?.setPoints(emptyList())
                        routePolyline?.setPoints(emptyList())
                        b.mapview.invalidate()
                    }
                }
            }
        }
    }

    private fun startUIUpdateLoop() {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            var loopCount = 0
            while (isActive) {
                try {
                    // Optimization: Move preference reading to Dispatchers.IO to keep UI thread fluid
                    withContext(Dispatchers.IO) {
                        if (!isActive) return@withContext
                        val prefs = getSharedPreferences("tracking_prefs", MODE_PRIVATE)

                        val lat = prefs.getFloat("last_lat", 0f).toDouble()
                        val lon = prefs.getFloat("last_lon", 0f).toDouble()
                        val acc = prefs.getFloat("last_accuracy", 0f)
                        val speed = prefs.getFloat("last_speed", 0f)
                        val activeStaff = prefs.getString("active_staff_id", null)
                        val lastLocAt = prefs.getLong("last_location_at", System.currentTimeMillis())

                        withContext(Dispatchers.Main) {
                            _binding?.let { b ->
                                if (lat != 0.0) {
                                    currentLat = lat
                                    currentLon = lon
                                    currentAccuracy = acc
                                    currentSpeed = speed

                                    if (officeLat != 0.0 && officeLon != 0.0) {
                                        val distanceResults = FloatArray(1)
                                        Location.distanceBetween(officeLat, officeLon, currentLat, currentLon, distanceResults)
                                        val distance = distanceResults[0].toDouble()

                                        b.tvDistance.text = if (distance < 1000) "${distance.toInt()} m" else String.format(Locale.US, "%.2f km", distance / 1000.0)
                                        b.tvAccuracy.text = "±${acc.toInt()} m 🎯"
                                        b.tvAllowed.text = "$geoRadius m 🛡️"

                                        updateMapMarkers()
                                    } else {
                                        b.tvDistance.text = "--"
                                        b.tvAllowed.text = "Configuring... ⏳"
                                    }
                                }

                                // Update Range Status
                                updateRangeStatus()

                                // Update Session Duration
                                val active = activeStaff == sessionStore.employeeId().toString()
                                b.llLiveStatus.visibility = if (active) View.VISIBLE else View.GONE
                                if (active) {
                                    if (sessionStartTime == 0L) {
                                        sessionStartTime = lastLocAt
                                    }
                                    val duration = System.currentTimeMillis() - sessionStartTime
                                    val durationStr = formatDuration(duration)
                                    val startStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sessionStartTime))
                                    b.tvSessionInfo.text = "Started $startStr 🗓️ • Duration $durationStr ⚡"

                                    if (b.liveDot.animation == null) {
                                        b.liveDot.startAnimation(AnimationUtils.loadAnimation(this@EmployeeHomeActivity, R.anim.pulse))
                                    }
                                }
                            }
                        }
                    }

                    if (loopCount % 8 == 0 && dashboardJob?.isActive != true) {
                        loadDashboard()
                    }

                    loopCount++
                } catch (e: Exception) {
                    Log.e("EmployeeHome", "UI Update loop error: ${e.message} ⚠️")
                }
                delay(4000L)
            }
        }
    }

    private fun updateRangeStatus() {
        val b = _binding ?: return

        if (currentLat == 0.0 || currentLon == 0.0) {
            b.tvRangeStatus.text = "Locating device... 🛰️"
            b.btnPunch.isEnabled = false
            return
        }

        if (officeLat == 0.0 || officeLon == 0.0 || geoRadius <= 0) {
            b.tvRangeStatus.text = "Configuring office... 🏢"
            b.btnPunch.isEnabled = false
            return
        }

        val distanceResults = FloatArray(1)
        Location.distanceBetween(
            officeLat,
            officeLon,
            currentLat,
            currentLon,
            distanceResults
        )
        val distance = distanceResults[0]

        val prefs = getSharedPreferences("tracking_prefs", MODE_PRIVATE)
        val featureState = EmployeeAttendanceStateMachine.FeatureState(
            geoFencingEnabled = prefs.getBoolean("enable_geo_fencing", true),
            dualAttendanceEnabled = prefs.getBoolean("enable_dual_attendance", false),
            automaticGeofencePunchingEnabled = prefs.getBoolean("enable_auto_punch", false)
        )

        val locationState = EmployeeAttendanceStateMachine.LocationState(
            hasLocation = currentLat != 0.0 && currentLon != 0.0,
            distanceMeters = distance.toDouble(),
            allowedRadiusMeters = geoRadius
        )

        val withinRange = locationState.withinRadius

        b.tvRangeStatus.text =
            if (withinRange) "Within allowed range ✅ 💎"
            else "Outside allowed range ⚠️ ❌"

        b.tvRangeStatus.setBackgroundColor(
            if (withinRange) "#2010B981".toColorInt()
            else "#20EF4444".toColorInt()
        )
        b.tvRangeStatus.setTextColor(
            if (withinRange) "#10B981".toColorInt()
            else "#EF4444".toColorInt()
        )
        b.tvRangeStatus.setCompoundDrawablesWithIntrinsicBounds(
            if (withinRange) R.drawable.ic_check_circle else R.drawable.ic_cancel,
            0,
            0,
            0
        )

        b.btnPunch.isEnabled = EmployeeAttendanceStateMachine.ManualPunchState(
            feature = featureState,
            location = locationState
        ).canPunch
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", minutes % 60, seconds % 60)
    }

    private fun attemptPunch() {
        lifecycleScope.launch {
            _binding?.let { b ->
                b.btnPunch.isEnabled = false
                b.btnPunch.text = "Processing... ⏳"

                try {
                    val employeeId = sessionStore.employeeId()
                    if (employeeId <= 0) throw IllegalStateException("Employee session is missing")

                    val nextType = selfService.nextPunchType()
                    val punch = AttendancePunch(
                        punchId = UUID.randomUUID().toString(),
                        staffId = employeeId.toString(),
                        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        type = nextType,
                        timestamp = System.currentTimeMillis(),
                        latitude = currentLat,
                        longitude = currentLon,
                        accuracy = currentAccuracy,
                        deviceId = sessionStore.deviceId(),
                        source = "MANUAL",
                        status = "APPROVED"
                    )

                    // MainRepository persists locally first and Firebase queues the
                    // write through the native SDK, so an offline punch is retained
                    // and synchronized automatically when connectivity returns.
                    repository.insertPunch(punch)

                    Toast.makeText(
                        this@EmployeeHomeActivity,
                        "$nextType punch recorded successfully! 💎",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadDashboard()
                } catch (e: Exception) {
                    Log.e("EmployeeHome", "Firebase punch failed", e)
                    _binding?.let {
                        Toast.makeText(
                            this@EmployeeHomeActivity,
                            "Punch failed: ${e.message ?: "Firebase unavailable"} ⚠️",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    _binding?.let { bInner ->
                        bInner.btnPunch.text = "PUNCH IN / OUT 🏢"
                        updateRangeStatus()
                    }
                }
            }
        }
    }

    private fun loadDashboard() {
        if (!sessionStore.isLoggedIn()) {
            goToLogin()
            return
        }

        val cachePrefs = getSharedPreferences("dashboard_cache", MODE_PRIVATE)
        _binding?.let { b ->
            if (b.tvSalary.text == "₹ --") {
                val cachedSalary = cachePrefs.getFloat("salary", 0f)
                val cachedPaid = cachePrefs.getFloat("paid_leave", 0f)
                val cachedSick = cachePrefs.getFloat("sick_leave", 0f)
                if (cachedSalary > 0) {
                    b.tvSalary.text = "₹${String.format(Locale.US, "%,.0f", cachedSalary.toDouble())} 💰"
                    b.tvPaidLeaveCount.text = String.format(Locale.US, "%.1f", cachedPaid.toDouble())
                    b.tvSickLeaveCount.text = String.format(Locale.US, "%.1f", cachedSick.toDouble())
                    b.progressPaidLeave.progress = (cachedPaid / 12.0 * 100).toInt().coerceIn(0, 100)
                    b.progressSickLeave.progress = (cachedSick / 12.0 * 100).toInt().coerceIn(0, 100)
                }
            }
        }

        dashboardJob?.cancel()
        dashboardJob = lifecycleScope.launch {
            try {
                val data = selfService.dashboard()
                _binding?.let { b ->
                    val employeeName = data.name.trim().ifBlank { sessionStore.employeeName().ifBlank { "Employee" } }
                    b.tvGreeting.text = "Hello, $employeeName! 👋 ✨"
                    b.tvMapUserLabel.text = employeeName

                    // Keep the session's display name aligned with the canonical
                    // Firebase employee record. The email remains only the login
                    // identity, never the dashboard greeting.
                    if (employeeName != sessionStore.employeeName() && employeeName != "Employee") {
                        sessionStore.updateEmployeeName(employeeName)
                    }

                    b.tvSalary.text = "₹${String.format(Locale.US, "%,.0f", data.monthlySalary)} 💰 💎"
                    b.tvPaidLeaveCount.text = String.format(Locale.US, "%.1f", data.paidLeaveBalance)
                    b.tvSickLeaveCount.text = String.format(Locale.US, "%.1f", data.sickLeaveBalance)
                    b.progressPaidLeave.progress = (data.paidLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)
                    b.progressSickLeave.progress = (data.sickLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)

                    officeLat = data.officeLatitude
                    officeLon = data.officeLongitude
                    geoRadius = data.geoRadiusMeters

                    getSharedPreferences("office_settings", MODE_PRIVATE).edit {
                        putFloat("lat", officeLat.toFloat())
                        putFloat("lon", officeLon.toFloat())
                        putInt("radius", geoRadius)
                    }
                    cachePrefs.edit {
                        putFloat("salary", data.monthlySalary.toFloat())
                        putFloat("paid_leave", data.paidLeaveBalance.toFloat())
                        putFloat("sick_leave", data.sickLeaveBalance.toFloat())
                    }
                    getSharedPreferences("tracking_prefs", MODE_PRIVATE).edit {
                        putBoolean("enable_geo_fencing", data.enableGeoFencing)
                        putBoolean("enable_dual_attendance", data.enableDualAttendance)
                        putBoolean("enable_auto_punch", data.enableAutomaticGeofencePunching)
                    }
                    applyFeatureHierarchy(data)
                    updateMapMarkers()
                }
            } catch (e: Exception) {
                Log.e("EmployeeHome", "Firebase dashboard load failed", e)
                _binding?.let { b ->
                    // Keep cached/local UI visible during offline mode.
                    b.tvGreeting.text = "Hello, ${sessionStore.employeeName().ifBlank { "Employee" }}! 👋 ✨"
                }
            }
        }
    }

    private fun observeAttendancePolicy() {
        policyJob?.cancel()
        policyJob = lifecycleScope.launch {
            attendancePolicy.observe().collect { policy ->
                val p = policy.normalized()
                getSharedPreferences("tracking_prefs", MODE_PRIVATE).edit {
                    putBoolean("enable_geo_fencing", p.geoFencingEnabled)
                    putBoolean("enable_dual_attendance", p.dualAttendanceEnabled)
                    putBoolean("enable_auto_punch", p.automaticGeofencePunchingEnabled)
                }
                if (p.officeLatitude != 0.0 && p.officeLongitude != 0.0 && p.geoRadiusMeters > 0) {
                    officeLat = p.officeLatitude
                    officeLon = p.officeLongitude
                    geoRadius = p.geoRadiusMeters
                }
                val geoOn = p.geoFencingEnabled
                if (!geoOn) {
                    stopService(Intent(this@EmployeeHomeActivity, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
                }
                applyAttendancePolicy(p)
                updateRangeStatus()
            }
        }
    }

    private fun applyAttendancePolicy(policy: AttendancePolicyRepository.Policy) {
        _binding?.let { b ->
            val feature = EmployeeAttendanceStateMachine.FeatureState(
                geoFencingEnabled = policy.geoFencingEnabled,
                dualAttendanceEnabled = policy.dualAttendanceEnabled,
                automaticGeofencePunchingEnabled = policy.automaticGeofencePunchingEnabled
            )
            // Manual button is a presentation of the exact Web hierarchy.
            // Automatic geofence punching is intentionally NOT performed here.
            // The existing Web attendance engine remains authoritative for it.
            b.btnPunch.visibility = if (feature.manualPunchVisible) View.VISIBLE else View.GONE
        }
    }

    private fun scheduleDashboardRetry(delayMs: Long) {
        if (isFinishing || isDestroyed) return
        if (dashboardRetryJob?.isActive == true) return
        dashboardRetryJob = lifecycleScope.launch {
            delay(delayMs)
            if (isActive && dashboardJob?.isActive != true) loadDashboard()
        }
    }

    private fun applyFeatureHierarchy(data: EmployeeDashboardResponse) {
        _binding?.let { b ->
            // 1. Geo-Fencing (Master GPS Switch)
            if (data.enableGeoFencing) {
                b.cvMapContainer.visibility = View.VISIBLE
                b.tvTrackingStatus.text = "GPS tracking is active 🛰️. Max range: ${data.geoRadiusMeters} m."
                // Start service if permitted
                requestTrackingPermissions()
            } else {
                b.cvMapContainer.visibility = View.GONE
                b.llLiveStatus.visibility = View.GONE
                b.tvTrackingStatus.text = "GPS Geofencing is disabled by Admin 🛡️."
                // STOP tracking service immediately
                stopService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
            }

            // 2. Attendance Priority Logic for Manual Button
            val biometricIsActive = !data.enableGeoFencing || data.enableDualAttendance
            val autoPunchIsActive = data.enableGeoFencing && data.enableAutomaticGeofencePunching

            // REQUIREMENT: PUNCH button visible ONLY IF no higher-priority source is active
            if (!biometricIsActive && !autoPunchIsActive) {
                b.btnPunch.visibility = View.VISIBLE
                b.tvTrackingStatus.append(" Manual punching enabled 🏢.")
            } else {
                b.btnPunch.visibility = View.GONE
            }
        }
    }

    private fun animateDashboard() {
        val root = _binding?.tvGreeting?.parent as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = 30f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setStartDelay(i * 40L)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
    }

    private fun checkBatteryOptimizations() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val dealsWithAlready = prefs.getBoolean("battery_opt_dealt_with", false)

        if (dealsWithAlready) return

        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            // Show only once ever
            AlertDialog.Builder(this)
                .setTitle("🛡️ Continuous Sync ⚡")
                .setMessage("To ensure location tracking never stops, please allow 'Unrestricted' battery usage and enable 'Auto-start' if available on your device.")
                .setCancelable(false)
                .setPositiveButton("Configure") { dialog: DialogInterface, _: Int ->
                    prefs.edit { putBoolean("battery_opt_dealt_with", true) }
                    // Request battery exclusion
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                    // Also try to show OEM specific auto-start settings
                    OemBackgroundHelper.showAutoStartSettings(this)
                    dialog.dismiss()
                }
                .setNegativeButton("Later") { dialog: DialogInterface, _: Int ->
                    prefs.edit { putBoolean("battery_opt_dealt_with", true) }
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityUiActive = true
        _binding?.mapview?.onResume()
        startUIUpdateLoop()
        applyCurrentThemeToMap()

        lifecycleScope.launch {
            loadDashboard()
            signalR.start()

            // REQUIREMENT: Extreme 24/7 background check
            val prefs = getSharedPreferences("tracking_prefs", MODE_PRIVATE)
            val intended = prefs.getBoolean("is_service_active_intended", false)
            val geoEnabled = prefs.getBoolean("enable_geo_fencing", true)

            if (geoEnabled && !intended) {
                startTracking()
            }
        }
    }

    override fun onPause() {
        isActivityUiActive = false
        MarkerAnimationHelper.cancelAll()
        _binding?.mapview?.onPause()
        uiUpdateJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        isActivityUiActive = false
        MarkerAnimationHelper.cancelAll()
        initJob?.cancel()
        roadRouteJob?.cancel()
        dashboardJob?.cancel()
        dashboardRetryJob?.cancel()
        iconCache.clear()

        _binding?.mapview?.onDetach()

        super.onDestroy()
        _binding = null
    }

    private fun requestTrackingPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        if (isPermissionDialogShowing) return

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        if (!fine) {
            // UX Fix: If user clicked 'Later' for base location, don't nag every time
            if (prefs.getBoolean("location_permission_skipped", false)) {
                return
            }
            showLocationPermissionMessage()
        } else if (!background && Build.VERSION.SDK_INT >= 29) {
            // UX Fix: If user clicked 'Later' for background, don't nag again
            if (prefs.getBoolean("bg_permission_dialog_skipped", false)) {
                startTracking()
            } else {
                showBackgroundPermissionDialog()
            }
        } else {
            startTracking()
        }
    }

    private fun showBackgroundPermissionDialog() {
        if (isPermissionDialogShowing) return
        isPermissionDialogShowing = true

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        AlertDialog.Builder(this)
            .setTitle("Silent Background Tracking 🛰️")
            .setMessage("To track your location 24/7 even when the app is closed, please select 'Allow all the time' in the next screen.")
            .setCancelable(false)
            .setPositiveButton("Configure") { _, _ ->
                isPermissionDialogShowing = false
                if (Build.VERSION.SDK_INT >= 29) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
            .setNegativeButton("Later") { _, _ ->
                isPermissionDialogShowing = false
                prefs.edit { putBoolean("bg_permission_dialog_skipped", true) }
                startTracking()
            }
            .show()
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_STAFF_ID, sessionStore.employeeId().toString())
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to start GPS tracking: ${e.message ?: "permission required"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLocationPermissionMessage() {
        if (isPermissionDialogShowing) return
        isPermissionDialogShowing = true

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        AlertDialog.Builder(this)
            .setTitle("📍 Location Permission Required")
            .setMessage("BioMetric needs location permission to keep your work location updated while you are working.")
            .setPositiveButton("Open Settings") { dialog: DialogInterface, _: Int ->
                isPermissionDialogShowing = false
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                isPermissionDialogShowing = false
                prefs.edit { putBoolean("location_permission_skipped", true) }
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("🚪 Logout")
            .setMessage("This will stop your GPS session and release your active employee device session.")
            .setPositiveButton("Logout") { dialog: DialogInterface, _: Int ->
                logout()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun logout() {
        lifecycleScope.launch {
            // Logout remains Firebase-native. Do the same automatic OUT check
            // against the local/Firebase punch stream used by the Employee UI.
            runCatching {
                val currentId = sessionStore.employeeId()
                if (currentId > 0 && selfService.nextPunchType() == "OUT") {
                    val punch = AttendancePunch(
                        punchId = UUID.randomUUID().toString(),
                        staffId = currentId.toString(),
                        date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        type = "OUT",
                        timestamp = System.currentTimeMillis(),
                        latitude = currentLat,
                        longitude = currentLon,
                        accuracy = currentAccuracy,
                        geofenceId = null,
                        distanceFromGeofence = 0.0,
                        photoId = null,
                        deviceId = sessionStore.deviceId(),
                        source = "MANUAL",
                        status = "PENDING"
                    )
                    repository.insertPunch(punch)
                }
            }.onFailure {
                Log.e("EmployeeHome", "Firebase logout auto-OUT check skipped: ${it.message}")
            }

            runCatching { firebaseEmployeeSessionManager.release() }
            FirebaseAuth.getInstance().signOut()
            _binding?.let {
                // Logout is authoritative. Send a signaled stop to the service 
                // so it can notify Firebase of the session end before it terminates.
                val stopIntent = Intent(this@EmployeeHomeActivity, TrackingService::class.java).apply { 
                    action = TrackingService.ACTION_STOP 
                }
                startService(stopIntent)
                
                sessionStore.clearLogin()
                getSharedPreferences("user_prefs", MODE_PRIVATE).edit { putBoolean("is_logged_in", false) }
                applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit { putBoolean("is_locked", false) }
                goToLogin()
            }
        }
    }

    /**
     * Application-wide realtime invalidation endpoint.
     * Firebase and Room flows automatically handle data updates; this method
     * ensures the UI list and summary labels reflect changes immediately
     * after the central hydration bridge has updated the local database.
     */
    private fun refreshRealtime() {
        if (isFinishing || isDestroyed) return
        lifecycleScope.launch {
            loadDashboard()
        }
    }

    private fun goToLogin() {
        // Only callers that explicitly decide the session is invalid reach this
        // method. Clear the stale mobile session here so Launcher/Login cannot
        // bounce between an invalid token and the protected screen.
        sessionStore.clearLogin()
        SecurityBaseActivity.clearProcessAuthorization(applicationContext)
        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .edit(commit = true) { clear() }
        applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
            .edit(commit = true) { clear() }

        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun bitmapDescriptorFromVector(@DrawableRes vectorResId: Int): Bitmap? {
        return ContextCompat.getDrawable(this, vectorResId)?.run {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            bitmap
        }
    }
}