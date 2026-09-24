package com.biometric.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.Geocoder
import android.util.Log
import com.biometric.app.data.entity.*
import com.airbnb.lottie.LottieAnimationView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.razorpay.PaymentResultListener
import com.biometric.app.R
import com.biometric.app.backup.BackupWorker
import com.biometric.app.backup.GoogleDriveManager
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.Shop
import com.biometric.app.databinding.ActivityMainBinding
import com.biometric.app.databinding.DialogAddShopBinding
import com.biometric.app.domain.BrandingManager
import com.biometric.app.util.PremiumLoader
import com.biometric.app.ui.adapter.ShopAdapter
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import android.widget.TextView
import com.biometric.app.sync.SignalRManager
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.sync.FirebaseRoomHydrator
import com.biometric.app.util.BatteryOptimizationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.*
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.TimeUnit

import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.util.BoundingBox
import android.graphics.ColorMatrixColorFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.toColorInt
import com.biometric.app.api.AdminFeatureSettingsDto
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.OemBackgroundHelper
import com.biometric.app.util.PolylineDecoder
import com.biometric.app.util.MarkerAnimationHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.text.NumberFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class MainActivity : MotionBaseActivity(), PaymentResultListener {

    private companion object {
        private const val ACTION_ADMIN_MODULES = 0x4D01
    }
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var brandingManager: BrandingManager
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var adminRealtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var firebaseRoomHydrator: FirebaseRoomHydrator
    @Inject lateinit var osrmApi: OsrmApiService

    private val markers = mutableMapOf<Int, Marker>()
    private val roadLines = mutableMapOf<Int, Polyline>()
    private val roadCasings = mutableMapOf<Int, Polyline>()
    private val collisionConnectors = mutableMapOf<Int, Polyline>()
    private val lastRouteUpdate = mutableMapOf<Int, Long>()
    private val markerAnimations = mutableMapOf<Int, ValueAnimator>()
    private var lastRenderedLiveSignature: String? = null
    private val adminRoadRouteJobs = mutableMapOf<Int, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private var officeMarker: Marker? = null
    private var geofenceCircle: Polygon? = null
    private var currentGeofenceRadiusMeters: Int = 0
    private var statusFilter = "All"
    private var adminMapAutoCentered = false
    private var adminInfoWindow: InfoWindow? = null
    private var adminMapLayerIndex = 0
    private var adminZoneVisible = true
    private var adminTrailsVisible = true
    private var isAdminAutoFocusEnabled = false
    private var adminFollowingEmployeeId: Int? = null

    private val mapControlsHandler = Handler(Looper.getMainLooper())
    private var adminMapControlsVisible = false
    private var adminMapControlsHideRunnable: Runnable? = null
    private var adminMapTouchDownX = 0f
    private var adminMapTouchDownY = 0f
    private var adminMapTouchDownAt = 0L

    private val adminSelectedRailHandler = Handler(Looper.getMainLooper())
    private var adminSelectedRailPaused = false
    private var adminSelectedRailRunnable: Runnable? = null
    // Address-resolution state for the selected employee rail.
    private var selectedRailAddressEmployeeId: Int? = null
    private var selectedRailAddressLat: Double? = null
    private var selectedRailAddressLon: Double? = null
    private var selectedRailAddressJob: Job? = null
    private var standbyRefreshJob: Job? = null

    private val driveManager by lazy { GoogleDriveManager(this) }
    private var tvLastSynced: TextView? = null
    private var refreshJob: Job? = null
    private var liveSnapshotJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.main, binding.appBar)
        binding.tvLiveDate.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())

        if (viewModel.isLoading.value) {
            PremiumLoader.show(binding.loadingLayout, PremiumLoader.ScreenType.GENERIC, lifecycleScope, immediate = true)
        } else {
            PremiumLoader.hide(binding.loadingLayout)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = null
        val headers = setupDualHeader(binding.toolbar, "Dashboard Overview 🏢", "Real-time Operations 🛡️")
        headers.btnShop?.visibility = View.GONE
        tvLastSynced = headers.status
        tvLastSynced?.visibility = View.VISIBLE

        setupListeners()
        setupSwipeRefresh()
        observeViewModel()

        lifecycleScope.launch {
            // High-priority UI components first
            delay(500)
            viewModel.startRealtimeSync()
            applyRolePermissions()

            // Map next
            delay(1200)
            setupRealTimeSync()
            setupAdminMap()
            setupAdminFilters()
            setupAdminSelectedEmployeeRail()

            // Firebase realtime source: listeners remain active without manual refresh.
            delay(2000)
            viewModel.triggerRefresh()

            // Low-priority animations last
            findViewById<LottieAnimationView>(R.id.backgroundParticles)?.let {
                it.visibility = View.VISIBLE
                it.playAnimation()
            }
            binding.liveDotAdmin.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse))
        }

        if (driveManager.isUserSignedIn()) {
            scheduleDailyBackup()
        }
        checkBatteryOptimizations()
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

    private fun adminOpenStreetMapSource(): OnlineTileSourceBase =
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

    private fun setupAdminMap() {
        // Configure only the visible dashboard map during startup. The hidden
        // command-center map is initialized on demand when Tracking is opened.
        runCatching {
            OsmConfig.getInstance().userAgentValue = "BioMetricPayroll_Android_" + packageName
            OsmConfig.getInstance().tileDownloadThreads = 4
            OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200L * 1024L * 1024L
        }

        configureAdminMap(binding.adminMapView, allowNetwork = true)

        setupAdminDashboardMapControls()

        lifecycleScope.launch {
            delay(800)
            _binding?.let { b ->
                b.llAdminMapLoading.visibility = View.GONE
                observeLiveLocations()
                setAdminMapControlsVisible(false)
            }
        }
    }

    private fun setAdminMapControlsVisible(visible: Boolean) {
        val controls = _binding?.adminMapControls ?: return
        adminMapControlsVisible = visible
        controls.visibility = if (visible) View.VISIBLE else View.GONE

        adminMapControlsHideRunnable?.let {
            mapControlsHandler.removeCallbacks(it)
        }

        if (visible) {
            val hide = Runnable {
                if (!isFinishing && !isDestroyed) {
                    adminMapControlsVisible = false
                    _binding?.adminMapControls?.visibility = View.GONE
                }
            }
            adminMapControlsHideRunnable = hide
            mapControlsHandler.postDelayed(hide, 5000L)
        }
    }

    private fun configureAdminMap(map: MapView, allowNetwork: Boolean) {
        map.setUseDataConnection(allowNetwork)
        map.setTileSource(adminOpenStreetMapSource())
        map.setMultiTouchControls(true)
        map.setBackgroundColor(Color.TRANSPARENT)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(11.9139, 79.8145))
        applyCurrentThemeToMap(map)

        // REQUIREMENT: Robustly prevent parent NestedScrollView from intercepting map touches (pinch-to-zoom fix)
        map.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    adminMapTouchDownX = event.x
                    adminMapTouchDownY = event.y
                    adminMapTouchDownAt = SystemClock.elapsedRealtime()
                }
                MotionEvent.ACTION_UP -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)

                    if (map.id == R.id.adminMapView) {
                        val moved = kotlin.math.hypot(
                            (event.x - adminMapTouchDownX).toDouble(),
                            (event.y - adminMapTouchDownY).toDouble()
                        )
                        val quickTap =
                            SystemClock.elapsedRealtime() - adminMapTouchDownAt < 600L

                        if (moved < 18.0 && quickTap) {
                            setAdminMapControlsVisible(!adminMapControlsVisible)
                        }
                    }
                }
            }
            false
        }

        map.post {
            map.invalidate()
            map.requestLayout()
        }
    }


    /**
     * Live map data comes directly from Firebase through the existing
     * SignalRManager compatibility facade. No polling of Payroll.Web is used.
     */
    private fun hydrateLiveLocationsOnce() {
        val current = signalR.liveLocations.value.values.toList()
        if (!isFinishing && !isDestroyed) {
            updateAdminMarkers(current)
        }
    }

    private fun mapCenterFallback(map: MapView): GeoPoint {
        val current = map.mapCenter

        return if (
            current.latitude.isFinite() &&
            current.longitude.isFinite() &&
            kotlin.math.abs(current.latitude) <= 90.0 &&
            kotlin.math.abs(current.longitude) <= 180.0 &&
            !(current.latitude == 0.0 && current.longitude == 0.0)
        ) {
            GeoPoint(
                current.latitude,
                current.longitude
            )
        } else {
            GeoPoint(11.9139, 79.8145)
        }
    }

    private fun setupAdminDashboardMapControls() {
        binding.btnAdminMapFollow.setOnClickListener {
            isAdminAutoFocusEnabled = !isAdminAutoFocusEnabled
            adminFollowingEmployeeId = null
            
            binding.btnAdminMapFollow.alpha = if (isAdminAutoFocusEnabled) 1.0f else 0.4f
            Toast.makeText(this, if (isAdminAutoFocusEnabled) "Auto-follow enabled ⦿" else "Auto-follow disabled ◌", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefreshMap.setOnClickListener {
            isAdminAutoFocusEnabled = false
            adminFollowingEmployeeId = null
            binding.btnAdminMapFollow.alpha = 0.4f
            
            val points = signalR.liveLocations.value.values
                .map { GeoPoint(it.latitude, it.longitude) }
            val map = binding.adminMapView
            if (points.isNotEmpty()) {
                createBoundingBox(points)?.let { map.zoomToBoundingBox(it, true, 120) }
            }
        }

        binding.btnAdminMapLayer.setOnClickListener {
            adminMapLayerIndex = (adminMapLayerIndex + 1) % 4
            val map = binding.adminMapView
            val filter = when (adminMapLayerIndex) {
                0 -> {
                    map.setTileSource(TileSourceFactory.MAPNIK)
                    null
                }
                1 -> {
                    map.setTileSource(TileSourceFactory.USGS_SAT)
                    null
                }
                2 -> {
                    map.setTileSource(TileSourceFactory.OpenTopo)
                    null
                }
                else -> {
                    map.setTileSource(TileSourceFactory.MAPNIK)
                    ColorMatrixColorFilter(floatArrayOf(
                        0.25f, 0f, 0f, 0f, 0f,
                        0f, 0.25f, 0f, 0f, 0f,
                        0f, 0f, 0.25f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
            map.overlayManager.tilesOverlay.setColorFilter(filter)
            map.invalidate()
        }

        binding.btnAdminMapZone.setOnClickListener {
            adminZoneVisible = !adminZoneVisible
            val alpha = if (adminZoneVisible) 0x40 else 0
            geofenceCircle?.fillPaint?.alpha = alpha
            geofenceCircle?.outlinePaint?.alpha = if (adminZoneVisible) 0xA0 else 0
            binding.adminMapView.invalidate()
            Toast.makeText(this, if (adminZoneVisible) "Office Zone Visible 🏢" else "Office Zone Hidden 🛡️", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapTrail.setOnClickListener {
            adminTrailsVisible = !adminTrailsVisible
            val alpha = if (adminTrailsVisible) 255 else 0
            val casingAlpha = if (adminTrailsVisible) 150 else 0
            
            roadLines.values.forEach { it.outlinePaint.alpha = alpha }
            roadCasings.values.forEach { it.outlinePaint.alpha = casingAlpha }
            
            binding.adminMapView.invalidate()
            Toast.makeText(this, if (adminTrailsVisible) "Trails Enabled ↝" else "Trails Disabled 📍", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapFullscreen.setOnClickListener {
            startActivity(Intent(this, TrackingMapActivity::class.java))
        }
    }

    private fun setupAdminSelectedEmployeeRail() {
        binding.cardAdminSelectedEmployeeRail.visibility = View.GONE
        binding.adminSelectedEmployeeRailScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> adminSelectedRailPaused = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    adminSelectedRailPaused = true
                    adminSelectedRailHandler.postDelayed({ adminSelectedRailPaused = false }, 500L)
                }
            }
            false
        }
    }

    private fun startAdminSelectedRailAutoScroll() {
        if (adminSelectedRailRunnable != null) return
        adminSelectedRailRunnable = object : Runnable {
            override fun run() {
                val scroll = _binding?.adminSelectedEmployeeRailScroll
                if (scroll != null && !adminSelectedRailPaused && scroll.visibility == View.VISIBLE) {
                    val child = scroll.getChildAt(0)
                    val max = ((child?.width ?: 0) - scroll.width).coerceAtLeast(0)
                    if (max > 8) {
                        val next = scroll.scrollX + 2
                        scroll.scrollTo(if (next >= max) 0 else next, 0)
                    }
                }
                adminSelectedRailHandler.postDelayed(this, 24L)
            }
        }
        adminSelectedRailHandler.post(adminSelectedRailRunnable!!)
    }

    private fun stopAdminSelectedRailAutoScroll() {
        adminSelectedRailRunnable?.let { adminSelectedRailHandler.removeCallbacks(it) }
        adminSelectedRailRunnable = null
        adminSelectedRailPaused = false
    }

    private fun updateAdminSelectedEmployeeRail(
        loc: SignalRManager.LiveLocation,
        employeeName: String,
        distanceFromOffice: Double,
        withinCurrentRadius: Boolean,
        status: String
    ) {
        val employee = sharedViewModel.allEmployees.value.firstOrNull { it.employeeId == loc.employeeId.toString() }
        binding.cardAdminSelectedEmployeeRail.visibility = View.VISIBLE
        binding.tvAdminRailEmployee.text = employeeName
        binding.tvAdminRailEmployeeMeta.text = "#${loc.employeeId} · ${employee?.role?.takeIf { it.isNotBlank() } ?: "Staff"}"
        binding.tvAdminRailLocation.text = "Address unavailable"
        binding.tvAdminRailLocationMeta.text = "Lat ${String.format(Locale.US, "%.6f", loc.latitude)} · Long ${String.format(Locale.US, "%.6f", loc.longitude)} • ${status.uppercase(Locale.getDefault())}"
        val needsAddress = selectedRailAddressEmployeeId != loc.employeeId ||
            selectedRailAddressLat == null || selectedRailAddressLon == null ||
            distanceBetween(GeoPoint(selectedRailAddressLat ?: loc.latitude, selectedRailAddressLon ?: loc.longitude), GeoPoint(loc.latitude, loc.longitude)).toDouble() >= 80.0
        if (needsAddress) {
            selectedRailAddressEmployeeId = loc.employeeId
            selectedRailAddressLat = loc.latitude
            selectedRailAddressLon = loc.longitude
            selectedRailAddressJob?.cancel()
            selectedRailAddressJob = lifecycleScope.launch(Dispatchers.IO) {
                val address = reverseGeocodeAdminRail(loc.latitude, loc.longitude)
                withContext(Dispatchers.Main) {
                    if (adminFollowingEmployeeId == loc.employeeId &&
                        selectedRailAddressLat == loc.latitude && selectedRailAddressLon == loc.longitude) {
                        binding.tvAdminRailLocation.text = address
                    }
                }
            }
        }
        binding.tvAdminRailSpeed.text = formatSpeed(loc.speedMps)
        binding.tvAdminRailMovement.text = loc.movementState.ifBlank { "Stopped" }
        binding.tvAdminRailAccuracy.text = if (loc.accuracyMeters > 0) "±${loc.accuracyMeters.toInt()} m" else "Unknown"
        binding.tvAdminRailDistance.text = formatDistance(distanceFromOffice)
        binding.tvAdminRailRadius.text = if (withinCurrentRadius) "Within ${currentGeofenceRadiusMeters} m" else "Outside ${currentGeofenceRadiusMeters} m"
        binding.tvAdminRailRadius.setTextColor(if (withinCurrentRadius) "#16A34A".toColorInt() else "#DC2626".toColorInt())
        binding.tvAdminRailUpdated.text = formatAdminLocationTime(loc.timestamp)
        binding.tvAdminRailStatus.text = status
        startAdminSelectedRailAutoScroll()
    }

    private fun formatAdminLocationTime(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "No timestamp"
        return try {
            val instant = java.time.Instant.parse(timestamp)
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
            formatter.format(Date.from(instant))
        } catch (_: Exception) { timestamp }
    }

    private fun clearAdminSelectedEmployeeRail() {
        binding.cardAdminSelectedEmployeeRail.visibility = View.GONE
        binding.adminSelectedEmployeeRailScroll.scrollTo(0, 0)
        stopAdminSelectedRailAutoScroll()
        selectedRailAddressJob?.cancel()
        selectedRailAddressJob = null
        selectedRailAddressEmployeeId = null
        selectedRailAddressLat = null
        selectedRailAddressLon = null
    }

    private suspend fun reverseGeocodeAdminRail(latitude: Double, longitude: Double): String {
        return try {
            if (!Geocoder.isPresent()) {
                return "Address unavailable"
            }

            val geocoder = Geocoder(this@MainActivity, Locale.getDefault())

            suspendCancellableCoroutine { continuation ->
                try {
                    geocoder.getFromLocation(
                        latitude,
                        longitude,
                        1,
                        object : Geocoder.GeocodeListener {

                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                val address = addresses.firstOrNull()

                                if (address == null) {
                                    continuation.resume("Address unavailable")
                                    return
                                }

                                val road = address.thoroughfare
                                    ?.takeIf { it.isNotBlank() }

                                val area = address.subLocality
                                    ?.takeIf { it.isNotBlank() }

                                val city = address.locality
                                    ?.takeIf { it.isNotBlank() }
                                    ?: address.subAdminArea
                                        ?.takeIf { it.isNotBlank() }

                                val district = address.subAdminArea
                                    ?.takeIf { it.isNotBlank() }

                                val state = address.adminArea
                                    ?.takeIf { it.isNotBlank() }

                                val pin = address.postalCode
                                    ?.takeIf { it.isNotBlank() }

                                val parts = listOfNotNull(
                                    road,
                                    area,
                                    city,
                                    district,
                                    state,
                                    pin
                                ).distinct()

                                val result = parts
                                    .joinToString(", ")
                                    .ifBlank {
                                        address.getAddressLine(0)
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "Address unavailable"
                                    }

                                continuation.resume(result)
                            }

                            override fun onError(errorMessage: String?) {
                                continuation.resume("Address unavailable")
                            }
                        }
                    )
                } catch (_: Exception) {
                    if (continuation.isActive) {
                        continuation.resume("Address unavailable")
                    }
                }
            }
        } catch (_: Exception) {
            "Address unavailable"
        }
    }

    private fun setupAdminFilters() {
        val listener = { id: Int ->
            statusFilter = when(id) {
                R.id.chipAdminLive -> "Live"
                R.id.chipAdminStale -> "Stale"
                R.id.chipAdminOffline -> "Offline"
                else -> "All"
            }
            updateAdminMarkers(signalR.liveLocations.value.values.toList())
        }
        binding.chipGroupAdminStatus.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { listener(it) } }
    }

    private fun observeLiveLocations() {
        lifecycleScope.launch {
            signalR.liveLocations
                .collectLatest { liveMap ->
                    // StateFlow already publishes the authoritative Firebase
                    // snapshot. Render immediately for responsive Admin updates.
                    _binding?.let { updateAdminMarkers(liveMap.values.toList()) }
                }
        }
        lifecycleScope.launch {
            viewModel.companySettings.collectLatest { settings ->
                settings?.let { s ->
                    _binding?.let { updateOfficeOnMap(s.officeLatitude, s.officeLongitude, s.geoRadiusMeters) }
                }
            }
        }

        /*
         * Live GPS can arrive before the Firebase employee cache finishes
         * hydrating. updateAdminMarkers intentionally rejects an unknown
         * EmployeeID, so re-render whenever the owner employee master changes.
         * This removes the startup race where the dashboard shows the correct
         * workforce count but the already-received live employee marker is lost.
         */
        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest {
                delay(50L)
                _binding?.let {
                    updateAdminMarkers(signalR.liveLocations.value.values.toList())
                }
            }
        }
        lifecycleScope.launch {
            signalR.ownerEmployees.collectLatest {
                _binding?.let {
                    updateAdminMarkers(signalR.liveLocations.value.values.toList())
                }
            }
        }
    }

    private fun updateOfficeOnMap(lat: Double, lon: Double, radius: Int) {
        if (!lat.isFinite() || !lon.isFinite() || lat == 0.0 && lon == 0.0) return

        currentGeofenceRadiusMeters = radius.coerceAtLeast(0)

        _binding?.let { b ->
            val point = GeoPoint(lat, lon)
            val safeRadius = currentGeofenceRadiusMeters.toDouble()

            if (officeMarker == null) {
                officeMarker = Marker(b.adminMapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createPremiumOfficeIcon()
                    title = "Office Hub 🏢"
                    snippet = "Office location • Radius ${currentGeofenceRadiusMeters} m"
                }
                b.adminMapView.overlays.add(officeMarker)
            }

            if (geofenceCircle == null) {
                geofenceCircle = Polygon(b.adminMapView).apply {
                    fillPaint.color = 0x153B82F6
                    outlinePaint.color = 0x803B82F6.toInt()
                    outlinePaint.strokeWidth = 3f
                }
                b.adminMapView.overlays.add(0, geofenceCircle)
            }

            officeMarker?.apply {
                position = point
                isEnabled = true
                alpha = 1f
                snippet = "Office location • Radius ${currentGeofenceRadiusMeters} m"
            }

            val circlePoints = Polygon.pointsAsCircle(point, safeRadius)
            geofenceCircle?.apply {
                points = circlePoints
                isEnabled = adminZoneVisible && currentGeofenceRadiusMeters > 0
                fillPaint.alpha = if (adminZoneVisible) 0x40 else 0
                outlinePaint.alpha = if (adminZoneVisible) 0xA0 else 0
            }

            b.adminMapView.post {
                b.adminMapView.invalidate()
                b.adminMapView.requestLayout()
            }
            if (!adminMapAutoCentered) {
                b.adminMapView.controller.setCenter(point)
                b.adminMapView.controller.setZoom(16.0)
            }
        }
    }

    private fun createPremiumOfficeIcon(): Drawable {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Shadow
        paint.color = Color.parseColor("#40000000")
        canvas.drawRoundRect(RectF(10f, 10f, 75f, 75f), 18f, 18f, paint)

        // Background (Blue Gradient feel)
        paint.color = Color.parseColor("#4F46E5")
        canvas.drawRoundRect(RectF(5f, 5f, 70f, 70f), 18f, 18f, paint)

        // Border
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        canvas.drawRoundRect(RectF(5f, 5f, 70f, 70f), 18f, 18f, paint)

        // Building Icon (Simplified drawing)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(25f, 50f); path.lineTo(25f, 25f); path.lineTo(50f, 25f); path.lineTo(50f, 50f); path.close()
        path.moveTo(32f, 32f); path.lineTo(38f, 32f); path.lineTo(38f, 38f); path.lineTo(32f, 38f); path.close()
        canvas.drawPath(path, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun updateAdminMarkers(locations: List<SignalRManager.LiveLocation>) {
        _binding?.let { b ->
            val dashboardMap = b.adminMapView
            val employeeData = sharedViewModel.allEmployees.value
            val firebaseEmployeeData = signalR.ownerEmployees.value

            val liveOpCount = locations.count { getLocStatus(it) == "Live" }
            val totalCount = if (employeeData.isNotEmpty()) employeeData.size else (firebaseEmployeeData.size.takeIf { it > 0 } ?: locations.size.coerceAtLeast(1))
            b.tvAdminMapLiveCount.text = "$liveOpCount Live / $totalCount"

            // Firebase can repeat the same parent snapshot. Avoid rebuilding
            // marker windows/routes and invalidating OSMDroid surface when
            // the visible state has not changed.
            val renderSignature = buildString {
                append(statusFilter).append('|').append(currentGeofenceRadiusMeters).append('|').append(adminFollowingEmployeeId).append('|')
                locations.sortedBy { it.employeeId }.forEach { loc ->
                    val emp = employeeData.find { it.employeeId == loc.employeeId.toString() }
                    val fEmp = firebaseEmployeeData[loc.employeeId]
                    append(loc.employeeId).append(':')
                        .append("%.5f".format(Locale.US, loc.latitude)).append(',')
                        .append("%.5f".format(Locale.US, loc.longitude)).append(':')
                        .append(getLocStatus(loc)).append(':')
                        .append(emp?.name ?: fEmp?.name ?: "").append(';')
                }
            }
            if (renderSignature == lastRenderedLiveSignature) return@let
            lastRenderedLiveSignature = renderSignature
            val geoPoints = mutableListOf<GeoPoint>()
            val currentIds = locations.map { it.employeeId }

            // Collision handling: group by approximate location to apply offset
            val locationsByCoord = locations.groupBy { 
                "%.5f:%.5f".format(Locale.US, it.latitude, it.longitude)
            }

            // Cleanup removed markers
            markers.keys.filter { !currentIds.contains(it) }.forEach { id ->
                dashboardMap.overlays.remove(markers[id]); markers.remove(id)
                dashboardMap.overlays.remove(roadLines[id]); roadLines.remove(id)
                dashboardMap.overlays.remove(roadCasings[id]); roadCasings.remove(id)
                dashboardMap.overlays.remove(collisionConnectors[id]); collisionConnectors.remove(id)
            }

            locations.forEach { loc ->
                if (loc.employeeId <= 0) return@forEach
                val emp = employeeData.find { it.employeeId == loc.employeeId.toString() }
                val firebaseEmp = firebaseEmployeeData[loc.employeeId]
                
                val employeeName = emp?.name ?: firebaseEmp?.name ?: "Employee #${loc.employeeId}"
                val status = getLocStatus(loc)
                val isFilteredOut = statusFilter != "All" && statusFilter != status
                
                if (isFilteredOut) {
                    markers[loc.employeeId]?.alpha = 0f
                    collisionConnectors[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    return@forEach
                }

                // Preserve the canonical GPS coordinate exactly. When multiple
                // employees occupy the same point, fan the visual pins and connect
                // each pin back to its true GPS coordinate.
                val actualPoint = GeoPoint(loc.latitude, loc.longitude)
                val coordKey = "%.5f:%.5f".format(Locale.US, loc.latitude, loc.longitude)
                val group = locationsByCoord[coordKey] ?: emptyList()
                val point = if (group.size > 1) {
                    val index = group.indexOf(loc)
                    val angle = 2.0 * Math.PI * index / group.size
                    val radius = 0.00008 // visual fan only, data remains actualPoint
                    GeoPoint(
                        loc.latitude + radius * Math.cos(angle),
                        loc.longitude + radius * Math.sin(angle)
                    )
                } else {
                    actualPoint
                }

                if (group.size > 1) {
                    val connector = collisionConnectors.getOrPut(loc.employeeId) {
                        Polyline(dashboardMap).apply {
                            outlinePaint.color = "#94A3B8".toColorInt()
                            outlinePaint.strokeWidth = 2f
                            outlinePaint.alpha = 190
                            dashboardMap.overlays.add(0, this)
                        }
                    }
                    connector.setPoints(listOf(actualPoint, point))
                } else {
                    collisionConnectors.remove(loc.employeeId)?.let {
                        dashboardMap.overlays.remove(it)
                    }
                }

                val m1 = markers.getOrPut(loc.employeeId) {
                    Marker(dashboardMap).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        dashboardMap.overlays.add(this)
                        
                        setOnMarkerClickListener { clicked, map ->
                            adminFollowingEmployeeId = loc.employeeId
                            isAdminAutoFocusEnabled = true
                            map.controller.animateTo(clicked.position)
                            val selectedDistance = officeMarker?.position?.let { office ->
                                distanceBetween(office, actualPoint).toDouble()
                            } ?: loc.distanceMeters.coerceAtLeast(0.0)
                            val selectedWithin = currentGeofenceRadiusMeters > 0 && selectedDistance <= currentGeofenceRadiusMeters.toDouble()
                            updateAdminSelectedEmployeeRail(loc, employeeName, selectedDistance, selectedWithin, status)
                            updateAdminRoadRoute(loc.employeeId, actualPoint)
                            true
                        }
                    }
                }
                
                m1.alpha = 1f
                m1.title = employeeName

                MarkerAnimationHelper.animateMarker(
                    m1, 
                    point, 
                    loc.bearing.toFloat(), 
                    loc.employeeId
                ) { animatedPoint ->
                    dashboardMap.invalidate()

                    collisionConnectors[loc.employeeId]?.setPoints(
                        listOf(actualPoint, animatedPoint))

                    // Update road lines synchronously with marker movement
                    runCatching {
                        val isSelected = adminFollowingEmployeeId == loc.employeeId
                        val trailAlpha = if (adminTrailsVisible && isSelected) 255 else 0
                        val casingAlpha = if (adminTrailsVisible && isSelected) 150 else 0

                        roadLines[loc.employeeId]?.let { l ->
                            val pts = l.actualPoints.toMutableList()
                            if (pts.size >= 2) {
                                pts[0] = animatedPoint
                                l.setPoints(pts)
                            }
                            l.outlinePaint.alpha = trailAlpha
                        }
                        roadCasings[loc.employeeId]?.let { c ->
                            val pts = c.actualPoints.toMutableList()
                            if (pts.size >= 2) {
                                pts[0] = animatedPoint
                                c.setPoints(pts)
                            }
                            c.outlinePaint.alpha = casingAlpha
                        }
                    }
                }

                // Smoothly follow the selected employee.
                if (isAdminAutoFocusEnabled && adminFollowingEmployeeId == loc.employeeId) {
                    dashboardMap.controller.animateTo(point)
                }

                val office = officeMarker?.position
                    ?: viewModel.companySettings.value?.let { s ->
                        if (s.officeLatitude != 0.0 && s.officeLongitude != 0.0) GeoPoint(s.officeLatitude, s.officeLongitude) else null
                    }
                val liveDistanceMeters = if (office != null) {
                    distanceBetween(office, point).toDouble()
                } else {
                    loc.distanceMeters.coerceAtLeast(0.0)
                }
                val withinCurrentRadius = currentGeofenceRadiusMeters > 0 &&
                    liveDistanceMeters <= currentGeofenceRadiusMeters.toDouble()

                val initials = getInitials(employeeName)
                val cacheKey = "${initials}_${withinCurrentRadius}_$status"
                val icon = iconCache.getOrPut(cacheKey) {
                    createPremiumMarkerIcon(initials, withinCurrentRadius, status)
                }
                m1.icon = icon
                m1.snippet = ""

                // Only show route for selected employee
                if (adminFollowingEmployeeId == loc.employeeId) {
                    updateAdminRoadRoute(loc.employeeId, actualPoint)
                } else {
                    roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                }
                geoPoints.add(point)
            }
            
            // Camera fit only if workforce markers grow or initial fit.
            val markerCount = geoPoints.size
            val prevCount = dashboardMap.tag as? Int ?: 0
            if (markerCount > 0 && (markerCount > prevCount || !adminMapAutoCentered)) {
                createBoundingBox(geoPoints)?.let { box ->
                    dashboardMap.zoomToBoundingBox(box, true, 100)
                }
                adminMapAutoCentered = true
                dashboardMap.tag = markerCount
            }

            val selectedRailLoc = adminFollowingEmployeeId?.let { id -> locations.firstOrNull { it.employeeId == id } }
            if (selectedRailLoc != null) {
                val railStatus = getLocStatus(selectedRailLoc)
                val railDistance = officeMarker?.position?.let { distanceBetween(it, GeoPoint(selectedRailLoc.latitude, selectedRailLoc.longitude)).toDouble() }
                    ?: selectedRailLoc.distanceMeters.coerceAtLeast(0.0)
                val railWithin = currentGeofenceRadiusMeters > 0 && railDistance <= currentGeofenceRadiusMeters.toDouble()
                updateAdminSelectedEmployeeRail(
                    selectedRailLoc,
                    employeeData.firstOrNull { it.employeeId == selectedRailLoc.employeeId.toString() }?.name ?: "Staff #${selectedRailLoc.employeeId}",
                    railDistance,
                    railWithin,
                    railStatus
                )
            } else {
                clearAdminSelectedEmployeeRail()
            }
            
            dashboardMap.invalidate()
        }
    }

    private fun updateAdminRoadRoute(empId: Int, userPoint: GeoPoint) {
        val now = System.currentTimeMillis()
        val last = lastRouteUpdate[empId] ?: 0L
        if (now - last < 30000L) return
        if (adminRoadRouteJobs[empId]?.isActive == true) return

        // Mark request time before starting it. A fast GPS stream must not
        // repeatedly cancel/restart the same OSRM request.
        lastRouteUpdate[empId] = now
        adminRoadRouteJobs[empId] = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = viewModel.companySettings.value ?: return@launch
                val coords = "${userPoint.longitude},${userPoint.latitude};${settings.officeLongitude},${settings.officeLatitude}"
                val response = osrmApi.getRoute(coords)
                if (response.isSuccessful) {
                    response.body()?.routes?.firstOrNull()?.geometry?.let { encoded ->
                        val decoded = PolylineDecoder.decode(encoded)
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                drawAdminRoute(empId, decoded)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun drawAdminRoute(empId: Int, points: List<GeoPoint>) {
        val map1 = binding.adminMapView

        val trailAlpha = if (adminTrailsVisible) 255 else 0
        val casingAlpha = if (adminTrailsVisible) 150 else 0

        val c1 = roadCasings.getOrPut(empId) { Polyline(map1).apply { outlinePaint.color = Color.WHITE; outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.alpha = casingAlpha; map1.overlays.add(0, this) } }
        val l1 = roadLines.getOrPut(empId) { Polyline(map1).apply { outlinePaint.color = "#4F46E5".toColorInt(); outlinePaint.strokeWidth = 8f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.alpha = trailAlpha; map1.overlays.add(1, this) } }
        c1.setPoints(points); l1.setPoints(points)

        map1.invalidate()
    }

    private fun setupRealTimeSync() {
        signalR.start()
        lifecycleScope.launch {
            signalR.dataChangeEvents
                .collectLatest { event ->
                    // Central coordinator already coalesces realtime bursts.
                    // Do not add another visible delay here.
                    _binding?.let {
                        when (event) {
                            is SignalRManager.SyncEvent.SessionEnded -> {
                                val myId = sessionStore.employeeId()
                                val mySessionId = sessionStore.gpsSessionId()
                                if (myId > 0 && event.employeeId == myId && event.sessionId.equals(mySessionId, true)) {
                                    Log.w("MainActivity", "Current admin-linked session terminated (Automatic logout disabled) ⚠️")
                                    // logout() // Requirement: App will never logged out automatically
                                }
                            }
                            is SignalRManager.SyncEvent.LocationChanged -> {
                                updateAdminMarkers(signalR.liveLocations.value.values.toList())
                            }
                            is SignalRManager.SyncEvent.SessionStarted -> {
                                Log.d("MainActivity", "New GPS session detected: ${event.employeeId}. Updating live map. 🛰️")
                                signalR.reconcileLiveLocationsNow()
                                // The liveLocations StateFlow updates the map directly.
                                // Avoid a whole-dashboard refresh for GPS session changes.
                            }
                            is SignalRManager.SyncEvent.GlobalRefresh -> {
                                // Employee Android writes invalidation events to
                                // client_events. This event must reach the visible
                                // Admin Dashboard immediately. Reconcile the
                                // authoritative owner-scoped live node first, then
                                // refresh the existing dashboard data loaders.
                                signalR.reconcileLiveLocationsNow()
                                refreshFromCentralRealtime()
                            }
                            // LocationChanged is rendered directly by the
                            // signalR.liveLocations StateFlow collector above.
                            else -> Unit
                        }
                    }
                }
        }
    }

    private fun triggerExclusiveRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            _binding?.let {
                viewModel.triggerRefresh()
                sharedViewModel.warmUpDashboard()
            }
        }
    }

    /**
     * Called by the application-wide realtime dispatcher after a Firebase event.
     * This is UI invalidation only and does not perform a database pull.
     */
    private fun refreshFromCentralRealtime() {
        if (isFinishing || isDestroyed) return
        lifecycleScope.launch {
            _binding?.let {
                viewModel.triggerRefresh()
                sharedViewModel.warmUpDashboard()
            }
        }
    }

    private fun getLocStatus(loc: SignalRManager.LiveLocation): String {
        val timestamp = loc.timestamp ?: return "Offline"
        val epochMs = signalR.parseTrackingTimestamp(timestamp)
        if (epochMs <= 0L) return "Offline"
        val ageMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
        return when {
            ageMs <= 300_000L -> "Live"
            ageMs <= 900_000L -> "Stale"
            else -> "Offline"
        }
    }

    private fun formatSpeed(mps: Double): String {
        if (mps <= 0.15) return "Stopped"
        val kmh = mps * 3.6
        return if (kmh < 1) "Slow" else "${kmh.toInt()} km/h"
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()}m" else String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

    private fun getInitials(name: String): String {
        val parts = name.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0][0]}${parts.last()[0]}".uppercase() else name.take(1).uppercase()
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

    private fun distanceBetween(p1: GeoPoint, p2: GeoPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0]
    }


    private fun applyCurrentThemeToMap(mapView: MapView) {
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
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
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
    }

    override fun onResume() {
        super.onResume()
        lastRenderedLiveSignature = null
        firebaseRoomHydrator.forceRebind("MainActivity onResume")
        binding.tvLiveDate.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        _binding?.adminMapView?.onResume()
        _binding?.adminMapView?.post { _binding?.adminMapView?.invalidate() }
        checkBatteryOptimizations()

        standbyRefreshJob?.cancel()
        standbyRefreshJob = lifecycleScope.launch {
            // Anti-Inactivity: Stagger to avoid UI jank on resume
            delay(300)
            _binding?.let {
                runCatching {
                    FirebaseAuth.getInstance().currentUser?.getIdToken(false)
                }

                triggerExclusiveRefresh()
                viewModel.startRealtimeSync()
                viewModel.triggerRefresh()
                signalR.start()
                // Force a canonical Firebase/SSOT live-location read whenever
                // Admin returns to the foreground. start() also reconciles when
                // the realtime manager is already running.
                signalR.reconcileLiveLocationsNow()

                val currentLocs = signalR.liveLocations.value.values.toList()
                if (currentLocs.isNotEmpty()) {
                    updateAdminMarkers(currentLocs)
                }
            }

            // Standby live updater: Re-evaluates marker status (Live/Stale/Offline)
            // and reconciles live locations every 15s while the Admin screen is active.
            while (isActive) {
                delay(15_000L)
                if (isFinishing || isDestroyed) break
                _binding?.let {
                    val currentLocs = signalR.liveLocations.value.values.toList()
                    if (currentLocs.isNotEmpty()) {
                        updateAdminMarkers(currentLocs)
                    }
                    signalR.reconcileLiveLocationsNow()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        standbyRefreshJob?.cancel()
        standbyRefreshJob = null
        _binding?.adminMapView?.onPause()
    }

    override fun onDestroy() {
        standbyRefreshJob?.cancel()
        standbyRefreshJob = null
        adminRoadRouteJobs.values.forEach { it.cancel() }
        adminRoadRouteJobs.clear()
        markerAnimations.values.forEach { it.cancel() }
        markerAnimations.clear()
        collisionConnectors.clear()
        mapControlsHandler.removeCallbacksAndMessages(null)
        refreshJob?.cancel()
        iconCache.clear()

        _binding?.let { b ->
            officeMarker?.let { b.adminMapView.overlays.remove(it) }
            geofenceCircle?.let { b.adminMapView.overlays.remove(it) }
        }
        officeMarker = null
        geofenceCircle = null

        _binding?.adminMapView?.onDetach()

        super.onDestroy()
        _binding = null
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { viewModel.triggerRefresh() }
        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.colorPrimary), ContextCompat.getColor(this, R.color.green_700))
    }

    private fun setupListeners() {
        // KPI Cards Navigation (1:1 Web Admin Home Contract)
        binding.cardKpiWorkforce.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, StaffActivity::class.java))
        }
        binding.cardKpiPresent.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminAttendanceActivity::class.java))
        }
        binding.cardKpiPayroll.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminPayrollActivity::class.java))
        }
        binding.cardKpiAdvances.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminFinanceActivity::class.java))
        }

        // Daily Operations Cards Navigation
        binding.cardShiftsToday.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, ShiftManagerActivity::class.java))
        }
        binding.cardScheduledHours.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, ReportCenterActivity::class.java))
        }

        // Live Map Card Navigation
        binding.cvLiveMapCard.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, TrackingMapActivity::class.java))
        }

        // Quick Actions
        binding.btnApproveRegs.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, RegularizationActivity::class.java))
        }
        binding.btnRunPayroll.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminPayrollActivity::class.java))
        }
        binding.btnReportCenter.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, ReportCenterActivity::class.java))
        }
        binding.btnLeaveManagement.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, LeaveManagementActivity::class.java))
        }
        binding.btnAddEmployee.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, StaffActivity::class.java))
        }
        binding.btnAuditTrail.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AuditTrailActivity::class.java))
        }
        binding.btnRecycleBin.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, RecycleBinActivity::class.java))
        }
        binding.btnUserManagement.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, UserManagementActivity::class.java))
        }
        binding.btnTroubleshoot.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, TroubleshootActivity::class.java))
        }

        // Recent Advances View All
        binding.btnViewAllAdvances.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminFinanceActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sharedViewModel.userProfile.collectLatest { profile ->
                        _binding?.let { b ->
                            profile?.let { p ->
                                brandingManager.updateBranding(
                                    BrandingManager.BrandingConfig(
                                        appName = p.brandingName ?: "Biometric Payroll",
                                        logoUrl = p.brandingLogoUrl
                                    )
                                )
                            }
                        }
                    }
                }
                launch {
                    sharedViewModel.allShops.collectLatest { shops ->
                        if (shops.isNotEmpty() && sharedViewModel.selectedShop.value == null) {
                            sharedViewModel.setSelectedShop(shops.first())
                        }
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { isLoading ->
                        _binding?.let { b ->
                            if (isLoading) {
                                PremiumLoader.show(b.loadingLayout, PremiumLoader.ScreenType.GENERIC, lifecycleScope)
                            } else {
                                PremiumLoader.hide(b.loadingLayout)
                            }
                        }
                    }
                }
                launch {
                    sharedViewModel.isWarmingUp.collect { isWarming ->
                        _binding?.let { b ->
                            b.swipeRefresh.isRefreshing = isWarming
                        }
                    }
                }
                launch {
                    viewModel.globalStats.collectLatest { stats ->
                        _binding?.let { b ->
                            b.tvKpiWorkforce.text = stats.totalWorkforce.toString()
                            b.tvKpiPresent.text = getString(R.string.present_format, stats.presentToday, stats.activeEmployees)
                            b.tvKpiAbsent.text = getString(R.string.absent_format, stats.absentToday)

                            val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
                            b.tvKpiAdvances.text = currency.format(stats.unpaidAdvances)

                            // Payroll KPI: Done/Pending matching web
                            b.tvKpiPayroll.text = if (stats.pendingPayrolls > 0) "Pending" else "Done"

                            val month = SimpleDateFormat("MMM", Locale.US).format(Date())
                            b.tvPayrollLabel.text = "PAYROLL ($month)".uppercase()

                            b.tvPayrollVariance.text = String.format(Locale.US, "📈 %.1f%% vs Last Month", stats.payrollVariancePercent)
                            b.tvShiftsToday.text = stats.shiftsScheduledToday.toString()

                            // Web Parity: Xh Ym formatting
                            val totalMinutes = stats.totalMonthScheduledMs / (1000 * 60)
                            val h = totalMinutes / 60
                            val m = totalMinutes % 60
                            b.tvScheduledHours.text = "${h}h ${m}m"

                            updateRecentAdvances(stats.recentAdvances)
                        }
                    }
                }

            }
        }
    }

    private fun applyRolePermissions() {
        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE).getString("user_role", UserRole.Employee.name)
        val isSuperAdmin = role == UserRole.SuperAdmin.name

        lifecycleScope.launch {
            viewModel.featureSettings.collectLatest { settings ->
                _binding?.let { b ->
                    val s = settings ?: AdminFeatureSettingsDto()

                    // Admin Hub Quick Actions
                    b.btnRunPayroll.isVisible = isSuperAdmin || (s.enablePayroll && s.adminCanRunPayroll)
                    b.btnApproveRegs.isVisible = isSuperAdmin || s.enableRegularizationRequest
                    b.btnLeaveManagement.isVisible = isSuperAdmin || s.enableLeaveManagement
                    b.btnReportCenter.isVisible = isSuperAdmin || (s.enableCompanyReports && s.adminCanViewReports)
                    b.btnAddEmployee.isVisible = isSuperAdmin || (s.enableEmployeeManagement && s.adminCanManageEmployees)
                    b.btnUserManagement.isVisible = isSuperAdmin
                    b.btnTroubleshoot.isVisible = isSuperAdmin || s.adminCanViewAttendance
                    b.btnRecycleBin.isVisible = isSuperAdmin && s.enableRecycleBin
                    b.btnAuditTrail.isVisible = isSuperAdmin || s.enableAuditLog

                    // The profile/role can arrive after the toolbar is first created.
                    // Rebuild the toolbar so Admin Modules becomes visible immediately.
                    invalidateOptionsMenu()

                    // Map Visibility
                    b.cvLiveMapCard.isVisible = isSuperAdmin || (s.enableGeoFencing && s.adminCanViewAttendance)
                }
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val dealsWithAlready = prefs.getBoolean("battery_opt_dealt_with", false)
            if (dealsWithAlready) return

            if (System.currentTimeMillis() - prefs.getLong("battery_prompt_time", 0L) > 24 * 60 * 60 * 1000) {
                MaterialAlertDialogBuilder(this).setTitle("Continuous Sync ⚡").setMessage("To ensure location tracking never stops, please disable battery optimization and enable 'Auto-start' if available on your device.").setPositiveButton("Configure") { _, _ ->
                    prefs.edit { putBoolean("battery_opt_dealt_with", true) }
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                    OemBackgroundHelper.showAutoStartSettings(this)
                }.setNegativeButton("Later") { _, _ ->
                    prefs.edit {
                        putLong("battery_prompt_time", System.currentTimeMillis())
                        putBoolean("battery_opt_dealt_with", true)
                    }
                }.show()
            }
        }
    }

    private fun showAddShopDialog() {
        val dialogBinding = DialogAddShopBinding.inflate(LayoutInflater.from(this))
        MaterialAlertDialogBuilder(this).setTitle("🏬 Add New Shop").setView(dialogBinding.root).setPositiveButton("Add") { _, _ -> val name = dialogBinding.etShopName.text.toString(); if (name.isNotBlank()) viewModel.addShop(Shop(name = name)) }.setNegativeButton("Cancel", null).show()
    }

    private fun scheduleDailyBackup() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_backup", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, showShopSwitcher = false, activity = this)

        val profile = sharedViewModel.userProfile.value
        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .getString("user_role", UserRole.Employee.name)
        val isAdmin = profile?.isAdmin() == true || profile?.isSuperAdmin() == true ||
            role == UserRole.Admin.name || role == UserRole.SuperAdmin.name
        if (isAdmin) {
            menu?.add(Menu.NONE, ACTION_ADMIN_MODULES, Menu.NONE, "📱 Admin Modules")?.apply {
                setIcon(R.drawable.ic_people)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val profile = sharedViewModel.userProfile.value
        val isAdmin = profile?.isAdmin() == true || profile?.isSuperAdmin() == true
        val isSuperAdmin = profile?.isSuperAdmin() == true

        if (isAdmin && item.itemId == ACTION_ADMIN_MODULES) {
            startActivity(Intent(this, WebParityHubActivity::class.java))
            return true
        }

        val extraActions = mutableListOf(
            GlobalSwitcherDelegate.ActionItem("🔄", "Sync Data") {
                triggerExclusiveRefresh()
                Toast.makeText(this, "Real-time sync triggered... 🛰️", Toast.LENGTH_SHORT).show()
            }
        )

        if (isAdmin) {
            extraActions.add(GlobalSwitcherDelegate.ActionItem("⚙️", "Settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
        }

        if (isSuperAdmin) {
            extraActions.add(GlobalSwitcherDelegate.ActionItem("👥", "Users") {
                startActivity(Intent(this, UserManagementActivity::class.java))
            })
        }

        return if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel, extraActions)) true else super.onOptionsItemSelected(item)
    }

    override fun onPaymentSuccess(p0: String?) { Toast.makeText(this, "Subscription Successful! 💎 ✅", Toast.LENGTH_LONG).show() }
    override fun onPaymentError(p0: Int, p1: String?) { Toast.makeText(this, "Subscription Failed: $p1 ⚠️", Toast.LENGTH_LONG).show() }

    private fun updateRecentAdvances(advances: List<AdvancePayment>) {
        _binding?.let { b ->
            b.tvRecentAdvancesCount.text = advances.size.toString()
            b.tvNoAdvances.isVisible = advances.isEmpty()
            b.llRecentAdvancesContainer.removeAllViews()

            val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
            val sdf = SimpleDateFormat("dd-MMM", Locale.US)
            val employees = sharedViewModel.allEmployees.value

            advances.take(4).forEach { adv ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_history_row, b.llRecentAdvancesContainer, false)
                val tvTitle = row.findViewById<TextView>(R.id.tvHistoryTitle)
                val tvDate = row.findViewById<TextView>(R.id.tvHistoryDate)
                val tvReason = row.findViewById<TextView>(R.id.tvHistoryReason)
                val tvIcon = row.findViewById<TextView>(R.id.tvHistoryIcon)

                val emp = employees.find { it.employeeId == adv.employeeId }

                // Web Parity: Name, Date • Type, Amount
                tvTitle.text = "👤 ${emp?.name ?: "Staff ID ${adv.employeeId}"}"
                tvDate.text = "🗓️ ${sdf.format(Date(adv.date))} • Advance"
                tvReason.text = "💰 ${currency.format(adv.amount)}"
                tvReason.setTextColor(Color.parseColor("#EF4444")) // Danger red for unpaid
                tvIcon.text = "💸"

                b.llRecentAdvancesContainer.addView(row)
            }
        }
    }

}
