
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
import com.biometric.app.api.EmployeePunchRequest
import com.biometric.app.api.GpsSessionRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.ActivityEmployeeHomeBinding
import com.biometric.app.domain.location.TrackingService
import com.biometric.app.sync.SignalRManager
import com.biometric.app.ui.selfservice.*
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.BatteryOptimizationHelper
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.OemBackgroundHelper
import com.biometric.app.util.PolylineDecoder
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
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class EmployeeHomeActivity : MotionBaseActivity() {
    private var _binding: ActivityEmployeeHomeBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var osrmApi: OsrmApiService

    private var officeMarker: Marker? = null
    private var userMarker: Marker? = null
    private var rangeCircle: Polygon? = null
    private var routePolyline: Polyline? = null
    private var routeCasing: Polyline? = null
    private var userMarkerAnimator: ValueAnimator? = null
    private val iconCache = mutableMapOf<String, Drawable>()
    private var lastRoadRouteUpdate: Long = 0L

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
    private var dashboardRetryJob: Job? = null
    private var dashboardAuthRecoveryInProgress = false
    private var sessionStartTime: Long = 0L
    private var isPermissionDialogShowing = false

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

        applyWindowInsets(binding.main, binding.appBar)

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
        geoRadius = prefs.getInt("radius", 100)

        if (officeLat != 0.0) {
            updateMapMarkers()
            updateRangeStatus()
        }
    }

    private fun setupRealTimeSync() {
        signalR.start()
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
        val mapView = binding.mapview
        val cacheManager = CacheManager(mapView)
        val boundingBox = BoundingBox(officeLat + 0.02, officeLon + 0.02, officeLat - 0.02, officeLon - 0.02)

        // Cache zoom levels 15 to 18 (the most common for workforce detail)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                cacheManager.downloadAreaAsync(this@EmployeeHomeActivity, boundingBox, 15, 18)
            } catch (e: Exception) {
                Log.e("EmployeeHome", "Map pre-cache failed: ${e.message}")
            }
        }
    }

    private fun applyCurrentThemeToMap() {
        val mapView = binding.mapview
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
            override fun getTileURLString(pMapTileIndex: org.osmdroid.util.MapTileIndex): String =
                baseUrl + pMapTileIndex.zoom + "/" + pMapTileIndex.x + "/" + pMapTileIndex.y + mImageFilenameEnding
        }

    private fun setupEmployeeMapControls() {
        binding.btnEmployeeMapRoute.setOnClickListener {
            if (currentLat != 0.0 && currentLon != 0.0 && officeLat != 0.0 && officeLon != 0.0) {
                val points = listOf(GeoPoint(currentLat, currentLon), GeoPoint(officeLat, officeLon))
                createBoundingBox(points)?.let { binding.mapview.zoomToBoundingBox(it, true, 120) }
            }
        }
        binding.btnEmployeeMapOffice.setOnClickListener {
            if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(officeLat, officeLon))
                binding.mapview.controller.setZoom(16.0)
            }
        }
        binding.btnEmployeeMapLayers.setOnClickListener {
            val next = ((binding.mapview.tag as? Int ?: 0) + 1) % 3
            binding.mapview.tag = next
            when (next) {
                0 -> {
                    binding.mapview.setTileSource(employeeOpenStreetMapSource())
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                1 -> {
                    binding.mapview.setTileSource(TileSourceFactory.USGS_SAT)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                else -> {
                    binding.mapview.setTileSource(employeeOpenStreetMapSource())
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(floatArrayOf(
                        0.25f, 0f, 0f, 0f, 0f,
                        0f, 0.25f, 0f, 0f, 0f,
                        0f, 0f, 0.25f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    )))
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
            binding.mapview.post { binding.mapview.invalidate() }
        }
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

                // Swiggy Style Polyline (Road Snapped)
                updateRoadRoute(officePoint, userPoint)

                b.tvRemainingDist.text = if (distance < 1000) "${distance.toInt()} m" else String.format(Locale.US, "%.1f km", distance / 1000.0)
                val etaSec = (distance / 1.4).toInt()
                b.tvEta.text = if (etaSec < 60) "Soon" else "${etaSec / 60} min"

                try {
                    val list = listOf(officePoint, userPoint)
                    createBoundingBox(list)?.let { mapView.zoomToBoundingBox(it, true, 180) }
                } catch (_: Exception) {}
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
        userMarkerAnimator?.cancel()

        val startPosition = marker.position
        if (startPosition.latitude == 0.0) {
            marker.position = toPosition
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1500L // 1.5s smooth glide
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float

            val lat = t * toPosition.latitude + (1 - t) * startPosition.latitude
            val lng = t * toPosition.longitude + (1 - t) * startPosition.longitude

            marker.position = GeoPoint(lat, lng)
            binding.mapview.invalidate()
        }

        userMarkerAnimator = animator
        animator.start()
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
                // Fallback to straight line if API fails
                withContext(Dispatchers.Main) {
                    _binding?.let { b ->
                        val pts = listOf(office, user)
                        routeCasing?.setPoints(pts)
                        routePolyline?.setPoints(pts)
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
        if (currentLat == 0.0) {
            binding.tvRangeStatus.text = "Locating device... 🛰️"
            return
        }

        if (officeLat == 0.0 || officeLon == 0.0) {
            binding.tvRangeStatus.text = "Configuring office... 🏢"
            return
        }

        val distanceResults = FloatArray(1)
        Location.distanceBetween(officeLat, officeLon, currentLat, currentLon, distanceResults)
        val distance = distanceResults[0]

        val withinRange = distance <= (geoRadius + 1)

        binding.tvRangeStatus.text = if (withinRange) "Within allowed range ✅ 💎" else "Outside allowed range ⚠️ ❌"
        binding.tvRangeStatus.setBackgroundColor(if (withinRange) "#2010B981".toColorInt() else "#20EF4444".toColorInt())
        binding.tvRangeStatus.setTextColor(if (withinRange) "#10B981".toColorInt() else "#EF4444".toColorInt())
        binding.tvRangeStatus.setCompoundDrawablesWithIntrinsicBounds(
            if (withinRange) R.drawable.ic_check_circle else R.drawable.ic_cancel, 0, 0, 0
        )

        binding.btnPunch.isEnabled = withinRange
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", minutes % 60, seconds % 60)
    }

    private fun attemptPunch() {
        val token = sessionStore.token() ?: return
        lifecycleScope.launch {
            _binding?.let { b ->
                b.btnPunch.isEnabled = false
                b.btnPunch.text = "Processing... ⏳"

                try {
                    val response = mobileApi.punch(
                        "Bearer $token",
                        EmployeePunchRequest(
                            type = "AUTO",
                            latitude = currentLat,
                            longitude = currentLon,
                            accuracy = currentAccuracy.toDouble()
                        )
                    )
                    _binding?.let { bRes ->
                        if (response.isSuccessful) {
                            val result = response.body()
                            if (result?.success == true) {
                                Toast.makeText(this@EmployeeHomeActivity, "Punch Successful! 💎 (${result.nextType} next) ✅", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@EmployeeHomeActivity, "Punch Failed: ${result?.message ?: "Unknown error"} ⚠️", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(this@EmployeeHomeActivity, "Server Error: ${response.code()} ❌", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    _binding?.let {
                        Toast.makeText(this@EmployeeHomeActivity, "Network Error: ${e.message} 🌐 ⚠️", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    _binding?.let { bInner ->
                        bInner.btnPunch.isEnabled = true
                        bInner.btnPunch.text = "PUNCH IN / OUT 🏢"
                    }
                }
            }
        }
    }

    private fun loadDashboard() {
        val token = sessionStore.token()
        if (token.isNullOrBlank()) {
            goToLogin()
            return
        }

        // Authoritative Sync: If just logged in (even punches), perform Auto-IN
        // Only trigger if we are within range or dual attendance is enabled.
        if (intent.getBooleanExtra("JUST_LOGGED_IN", false)) {
            intent.removeExtra("JUST_LOGGED_IN")
            lifecycleScope.launch {
                try {
                    val statusRes = mobileApi.punchStatus("Bearer $token")
                    if (statusRes.isSuccessful && statusRes.body()?.nextType == "IN") {
                        Log.i("EmployeeHome", "Authoritative Login: Performing automatic IN punch...")
                        mobileApi.punch(
                            "Bearer $token",
                            EmployeePunchRequest(
                                type = "IN",
                                latitude = currentLat,
                                longitude = currentLon,
                                accuracy = currentAccuracy.toDouble()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("EmployeeHome", "Authoritative Login auto-punch failed: ${e.message}")
                }
            }
        }

        // UX: Immediate local data fallback
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
                val response = mobileApi.dashboard("Bearer $token")
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        response.body()?.let { data ->
                            b.tvGreeting.text = "Hello, ${sessionStore.employeeName().ifBlank { "Employee" }}! 👋 ✨"
                            b.tvSalary.text = "₹${String.format(Locale.US, "%,.0f", data.monthlySalary)} 💰 💎"
                            b.tvPaidLeaveCount.text = String.format(Locale.US, "%.1f", data.paidLeaveBalance)
                            b.tvSickLeaveCount.text = String.format(Locale.US, "%.1f", data.sickLeaveBalance)

                            b.progressPaidLeave.progress = (data.paidLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)
                            b.progressSickLeave.progress = (data.sickLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)

                            officeLat = data.officeLatitude
                            officeLon = data.officeLongitude
                            geoRadius = data.geoRadiusMeters

                            // Persist office settings for zero-lag restoration next time
                            getSharedPreferences("office_settings", MODE_PRIVATE).edit {
                                putFloat("lat", officeLat.toFloat())
                                putFloat("lon", officeLon.toFloat())
                                putInt("radius", geoRadius)
                            }

                            // Cache for next instant load
                            cachePrefs.edit {
                                putFloat("salary", data.monthlySalary.toFloat())
                                putFloat("paid_leave", data.paidLeaveBalance.toFloat())
                                putFloat("sick_leave", data.sickLeaveBalance.toFloat())
                            }

                            // Persist feature settings for background recovery worker
                            getSharedPreferences("tracking_prefs", MODE_PRIVATE).edit {
                                putBoolean("enable_geo_fencing", data.enableGeoFencing)
                                putBoolean("enable_dual_attendance", data.enableDualAttendance)
                                putBoolean("enable_auto_punch", data.enableAutomaticGeofencePunching)
                            }

                            applyFeatureHierarchy(data)
                            updateMapMarkers()
                        }
                    } else if (response.code() == 401) {
                        // A 401 is an authentication/session signal, not a network
                        // failure. Do one guarded authoritative check. Never let
                        // concurrent realtime refreshes recurse into an auth/login
                        // loop.
                        if (dashboardAuthRecoveryInProgress) {
                            Log.w("EmployeeHome", "Dashboard 401 while auth recovery is already running; keeping session state stable.")
                            return@launch
                        }

                        dashboardAuthRecoveryInProgress = true
                        try {
                            Log.w("EmployeeHome", "Dashboard returned 401. Verifying the existing mobile session without clearing local login state... 🛰️")
                            val recoverResponse = mobileApi.me("Bearer $token")
                            if (recoverResponse.isSuccessful) {
                                Log.i("EmployeeHome", "Authoritative mobile session is valid. Retrying dashboard after a short delay. ✅")
                                delay(750L)
                                if (sessionStore.isLoggedIn()) {
                                    loadDashboard()
                                }
                            } else if (recoverResponse.code() == 401 || recoverResponse.code() == 403) {
                                // A server-authenticated 401/403 is different from
                                // a network failure. Preserve the current session
                                // unless the server explicitly reports that this
                                // mobile session was revoked/replaced.
                                val state = recoverResponse.headers()["X-Mobile-Session-State"] ?: ""
                                if (state.equals("SESSION_REVOKED", true) ||
                                    state.equals("REAUTH_REQUIRED", true)) {
                                    Log.e("EmployeeHome", "Mobile session was explicitly rejected by the server (${state}). Returning to login.")
                                    goToLogin()
                                } else {
                                    Log.w("EmployeeHome", "Temporary authentication challenge without revocation state. Keeping persisted session and retrying.")
                                    scheduleDashboardRetry(10_000L)
                                }
                            } else {
                                // 5xx and other server responses are availability
                                // failures, not logout decisions.
                                Log.w("EmployeeHome", "Dashboard returned HTTP ${recoverResponse.code()}. Keeping session and retrying.")
                                scheduleDashboardRetry(10_000L)
                            }
                        } finally {
                            dashboardAuthRecoveryInProgress = false
                        }
                    } else {
                        Log.e("EmployeeHome", "Dashboard error: ${response.code()} ❌")
                        if (response.code() >= 500) scheduleDashboardRetry(5_000L)
                    }
                }
            } catch (e: Exception) {
                Log.e("EmployeeHome", "Dashboard load failed: ${e.message} ⚠️")
                scheduleDashboardRetry(10_000L)
            }
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
        val root = binding.tvGreeting.parent as ViewGroup
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
        super.onPause()
        _binding?.mapview?.onPause()
        uiUpdateJob?.cancel()
    }

    override fun onDestroy() {
        initJob?.cancel()
        roadRouteJob?.cancel()
        dashboardJob?.cancel()
        dashboardRetryJob?.cancel()
        userMarkerAnimator?.cancel()
        userMarkerAnimator = null
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
            val token = sessionStore.token()
            if (!token.isNullOrBlank()) {
                // Authoritative Sync: Perform Auto-OUT punch if the user is currently IN
                try {
                    val statusResponse = mobileApi.punchStatus("Bearer $token")
                    if (statusResponse.isSuccessful) {
                        val status = statusResponse.body()
                        if (status?.nextType == "OUT") {
                            Log.i("EmployeeHome", "Authoritative Logout: Performing automatic OUT punch...")
                            mobileApi.punch(
                                "Bearer $token",
                                EmployeePunchRequest(
                                    type = "OUT",
                                    latitude = currentLat,
                                    longitude = currentLon,
                                    accuracy = currentAccuracy.toDouble()
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("EmployeeHome", "Authoritative Logout: Auto-punch failed: ${e.message}")
                }

                runCatching { mobileApi.endGps("Bearer $token", GpsSessionRequest(sessionStore.gpsSessionId())) }
                runCatching { mobileApi.logout("Bearer $token") }
            }
            _binding?.let {
                stopService(Intent(this@EmployeeHomeActivity, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
                sessionStore.clearLogin()
                getSharedPreferences("user_prefs", MODE_PRIVATE).edit { putBoolean("is_logged_in", false) }
                applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit { putBoolean("is_locked", false) }
                goToLogin()
            }
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
