package com.biometric.app.ui

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.OsrmApiService
import com.biometric.app.databinding.ActivityTrackingMapBinding
import com.biometric.app.sync.SignalRManager
import com.biometric.app.sync.FirebaseAuthSecurityGate
import com.biometric.app.domain.attendance.AttendancePolicyRepository
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.util.PolylineDecoder
import com.biometric.app.util.MarkerAnimationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@AndroidEntryPoint
class TrackingMapActivity : MotionBaseActivity() {

    private var _binding: ActivityTrackingMapBinding? = null
    private val binding get() = _binding!!
    
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var osrmApi: OsrmApiService
    @Inject lateinit var securityGate: FirebaseAuthSecurityGate
    @Inject lateinit var attendancePolicy: AttendancePolicyRepository
    private val viewModel: MainViewModel by viewModels()

    private val markers = mutableMapOf<Int, Marker>()
    private val roadLines = mutableMapOf<Int, Polyline>()
    private val roadCasings = mutableMapOf<Int, Polyline>()
    private val travelledRoadLines = mutableMapOf<Int, Polyline>()
    private val travelledRoadCasings = mutableMapOf<Int, Polyline>()
    private val collisionConnectors = mutableMapOf<Int, Polyline>()
    private val markerAnimations = mutableMapOf<Int, ValueAnimator>()
    private val lastRouteUpdate = mutableMapOf<Int, Long>()
    private val lastTravelledRouteUpdate = mutableMapOf<Int, Long>()
    private val roadRouteJobs = mutableMapOf<Int, Job>()
    private val travelledRouteJobs = mutableMapOf<Int, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private var statusFilter = "All"
    private var searchFilter = ""
    private var isAutoFocusEnabled = true
    private var followingEmployeeId: Int? = null
    private var selectedAddressEmployeeId: Int? = null
    private var selectedAddressLat: Double? = null
    private var selectedAddressLon: Double? = null
    private var selectedAddressJob: Job? = null
    
    private var policyJob: Job? = null
    private var officeMarker: Marker? = null
    private var officeCircle: Polygon? = null
    private var officeLat: Double = 0.0
    private var officeLon: Double = 0.0
    private var officeRadiusMeters: Int = 0
    private var officeSettingsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityTrackingMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val result = securityGate.validateCurrentSession()
            val allowed = result.allowed && (result.role.equals("ADMIN", true) || result.role.equals("SUPER_ADMIN", true))
            if (!allowed) {
                Toast.makeText(this@TrackingMapActivity, "Only Admin/SuperAdmin can view live staff tracking.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            setupProtectedTrackingScreen()
        }
    }

    private fun setupProtectedTrackingScreen() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyWindowInsets(binding.main, binding.appBar)
        setupMap()
        setupFilters()
        setupSelectedLocationRail()
        setupPremiumMapControls()
        observeLiveLocations()
        observeTrackingPolicy()
        observeOfficeSettings()
    }

    private fun setupFilters() {
        binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            statusFilter = when(checkedId) {
                R.id.chipLive -> "Live"
                R.id.chipStale -> "Stale"
                R.id.chipOffline -> "Offline"
                else -> "All"
            }
            updateMapMarkers(signalR.liveLocations.value.values.toList())
        }

        binding.etMapSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchFilter = s?.toString()?.lowercase() ?: ""
                updateMapMarkers(signalR.liveLocations.value.values.toList())
            }
        })
    }

    private var isMapFullscreen = false
    private var mapLayerIndex = 0
    private var adminZoneVisible = true
    private var adminTrailsVisible = true
    private var mapControlsVisible = false
    private val mapControlsHandler = Handler(Looper.getMainLooper())
    private var hideMapControlsRunnable: Runnable? = null
    private var mapTouchDownX = 0f
    private var mapTouchDownY = 0f
    private var mapTouchDownAt = 0L
    private val selectedRailHandler = Handler(Looper.getMainLooper())
    private var selectedRailPaused = false
    private var selectedRailRunnable: Runnable? = null

    private fun setupSelectedLocationRail() {
        binding.cardSelectedLocationRail.visibility = View.GONE
    }

    private fun updateSelectedLocationRail(
        loc: SignalRManager.LiveLocation,
        employeeName: String,
        distanceFromOffice: Double,
        withinCurrentRadius: Boolean,
        status: String
    ) {
        binding.cardSelectedLocationRail.visibility = View.VISIBLE
        startSelectedRailAutoScroll()
        binding.tvSelectedEmployeeName.text = employeeName
        binding.tvSelectedEmployeeInitials.text = getInitials(employeeName)
        binding.tvSelectedEmployeeStatus.text = "● ${status.uppercase(Locale.getDefault())}"
        binding.tvSelectedEmployeeStatus.setTextColor(
            when (status) {
                "Live" -> "#16A34A".toColorInt()
                "Stale" -> "#D97706".toColorInt()
                else -> "#64748B".toColorInt()
            }
        )
        val employee = sharedViewModel.allEmployees.value.firstOrNull { it.employeeId == loc.employeeId.toString() }
        binding.tvSelectedEmployeeMeta.text = "#${loc.employeeId} · ${employee?.role?.takeIf { it.isNotBlank() } ?: "Staff"}"

        binding.tvSelectedLocationCoords.text =
            "Lat ${String.format(Locale.US, "%.6f", loc.latitude)}  •  Long ${String.format(Locale.US, "%.6f", loc.longitude)}"
        binding.tvSelectedAccuracy.text = if (loc.accuracyMeters > 0) "±${loc.accuracyMeters.toInt()} m" else "Unknown"
        binding.tvSelectedDistance.text = formatDistance(distanceFromOffice)
        binding.tvSelectedRadiusStatus.text = if (withinCurrentRadius) {
            "Within ${officeRadiusMeters} m radius"
        } else {
            "Outside ${officeRadiusMeters} m radius"
        }
        binding.tvSelectedRadiusStatus.setTextColor(
            if (withinCurrentRadius) "#16A34A".toColorInt() else "#DC2626".toColorInt()
        )
        binding.tvSelectedLastUpdated.text = formatLocationTime(loc.timestamp)
        binding.tvSelectedSpeed.text = formatSpeed(loc.speedMps)
        binding.tvSelectedMovementState.text = loc.movementState.ifBlank { "Stopped" }

        // The live DTO does not contain a session-start timestamp, so never
        // fabricate a stay duration. Show the authoritative movement state in
        // the same premium rail instead.
        binding.tvSelectedStayDuration.text =
            loc.movementState.ifBlank { formatSpeed(loc.speedMps) }

        val needsNewAddress = selectedAddressEmployeeId != loc.employeeId ||
            selectedAddressLat == null || selectedAddressLon == null ||
            distanceMeters(selectedAddressLat!!, selectedAddressLon!!, loc.latitude, loc.longitude) >= 80.0

        if (needsNewAddress) {
            selectedAddressEmployeeId = loc.employeeId
            selectedAddressLat = loc.latitude
            selectedAddressLon = loc.longitude
            binding.tvSelectedLocationAddress.text = "Address unavailable"
            selectedAddressJob?.cancel()
            selectedAddressJob = lifecycleScope.launch(Dispatchers.IO) {
                val result = reverseGeocode(loc.latitude, loc.longitude)
                withContext(Dispatchers.Main) {
                    if (followingEmployeeId == loc.employeeId &&
                        selectedAddressLat == loc.latitude && selectedAddressLon == loc.longitude) {
                        binding.tvSelectedLocationAddress.text = result
                    }
                }
            }
        }
    }

    private fun formatLocationTime(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "No timestamp"
        return try {
            val instant = java.time.Instant.parse(timestamp)
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
            formatter.format(java.util.Date.from(instant))
        } catch (_: Exception) {
            timestamp
        }
    }

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): String {
        return try {
            if (!Geocoder.isPresent()) {
                return "Address unavailable"
            }

            val geocoder = Geocoder(this@TrackingMapActivity, Locale.getDefault())

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
                                    ?: address.locality
                                        ?.takeIf { it.isNotBlank() }

                                val city = address.locality
                                    ?.takeIf { it.isNotBlank() }
                                    ?: address.subAdminArea
                                        ?.takeIf { it.isNotBlank() }

                                val state = address.adminArea
                                    ?.takeIf { it.isNotBlank() }

                                val pin = address.postalCode
                                    ?.takeIf { it.isNotBlank() }

                                val parts = listOfNotNull(
                                    road,
                                    area,
                                    city,
                                    state,
                                    pin
                                ).distinct()

                                val result =
                                    if (parts.isNotEmpty()) {
                                        parts.joinToString(", ")
                                    } else {
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

    private fun startSelectedRailAutoScroll() {
        if (selectedRailRunnable != null) return
        selectedRailRunnable = object : Runnable {
            override fun run() {
                val scroll = _binding?.selectedLocationRailScroll ?: return
                if (!selectedRailPaused && scroll.visibility == View.VISIBLE) {
                    val child = scroll.getChildAt(0)
                    val max = (child?.width ?: 0) - scroll.width
                    if (max > 4) {
                        val next = scroll.scrollX + 2
                        scroll.scrollTo(if (next >= max) 0 else next, 0)
                    }
                }
                selectedRailHandler.postDelayed(this, 32L)
            }
        }
        binding.selectedLocationRailScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    selectedRailPaused = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selectedRailPaused = true
                    selectedRailHandler.postDelayed({ selectedRailPaused = false }, 700L)
                }
            }
            false
        }
        selectedRailHandler.post(selectedRailRunnable!!)
    }

    private fun stopSelectedRailAutoScroll() {
        selectedRailRunnable?.let { selectedRailHandler.removeCallbacks(it) }
        selectedRailRunnable = null
        selectedRailPaused = false
    }

    private var hasTrackingInitialFocused = false
    private val trackingMapIdleHandler = Handler(Looper.getMainLooper())
    private val trackingMapIdleRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            Log.i("TrackingMapActivity", "Admin auto-focusing Company & Staff after idle period 🏢👥")
            fitCompanyAndStaff(animated = true)
        }
    }

    private fun resetTrackingMapIdleTimer() {
        trackingMapIdleHandler.removeCallbacks(trackingMapIdleRunnable)
        trackingMapIdleHandler.postDelayed(trackingMapIdleRunnable, 120_000L) // 2 minutes idle auto-focus
    }

    private fun fitCompanyAndStaff(animated: Boolean = true) {
        val points = mutableListOf<GeoPoint>()
        if (officeLat != 0.0 && officeLon != 0.0) {
            points.add(GeoPoint(officeLat, officeLon))
        }
        val followingId = followingEmployeeId
        if (followingId != null) {
            signalR.liveLocations.value[followingId]?.let {
                points.add(GeoPoint(it.latitude, it.longitude))
            }
        } else {
            signalR.liveLocations.value.values.forEach {
                points.add(GeoPoint(it.latitude, it.longitude))
            }
        }
        if (points.isNotEmpty()) {
            if (points.size == 1) {
                binding.mapview.controller.animateTo(points[0])
                binding.mapview.controller.setZoom(16.0)
            } else {
                val bounds = BoundingBox.fromGeoPoints(points)
                binding.mapview.zoomToBoundingBox(bounds, animated, 140)
            }
        }
    }

    private fun setupPremiumMapControls() {
        binding.btnMapFit.setOnClickListener {
            resetTrackingMapIdleTimer()
            isAutoFocusEnabled = false
            followingEmployeeId?.let { prevId ->
                roadLines[prevId]?.outlinePaint?.alpha = 0
                roadCasings[prevId]?.outlinePaint?.alpha = 0
                travelledRoadLines[prevId]?.outlinePaint?.alpha = 0
                travelledRoadCasings[prevId]?.outlinePaint?.alpha = 0
            }
            followingEmployeeId = null
            binding.btnAdminMapFollow.alpha = 0.4f
            fitCompanyAndStaff(animated = true)
            Toast.makeText(this, "Fitting Company & All Staff 🏢👥", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapOffice.setOnClickListener {
            resetTrackingMapIdleTimer()
            isAutoFocusEnabled = false
            followingEmployeeId?.let { prevId ->
                roadLines[prevId]?.outlinePaint?.alpha = 0
                roadCasings[prevId]?.outlinePaint?.alpha = 0
                travelledRoadLines[prevId]?.outlinePaint?.alpha = 0
                travelledRoadCasings[prevId]?.outlinePaint?.alpha = 0
            }
            followingEmployeeId = null
            binding.btnAdminMapFollow.alpha = 0.4f
            
            if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(officeLat, officeLon))
                binding.mapview.controller.setZoom(16.0)
                Toast.makeText(this, "Focusing Office Location 🏢", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAdminMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
        binding.btnAdminMapFollow.setOnClickListener {
            resetTrackingMapIdleTimer()
            isAutoFocusEnabled = !isAutoFocusEnabled
            binding.btnAdminMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
            if (isAutoFocusEnabled && followingEmployeeId == null) {
                val firstLive = signalR.liveLocations.value.values.firstOrNull { getLocStatus(it) == "Live" }
                    ?: signalR.liveLocations.value.values.firstOrNull()
                if (firstLive != null) {
                    followingEmployeeId = firstLive.employeeId
                    updateMapMarkers(signalR.liveLocations.value.values.toList())
                }
            }
            Toast.makeText(this, if (isAutoFocusEnabled) "Auto-follow enabled ⦿" else "Auto-follow disabled ◌", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapZone.setOnClickListener {
            resetTrackingMapIdleTimer()
            adminZoneVisible = !adminZoneVisible
            val alpha = if (adminZoneVisible) 0x40 else 0
            officeCircle?.fillPaint?.alpha = alpha
            officeCircle?.outlinePaint?.alpha = if (adminZoneVisible) 0xA0 else 0
            binding.mapview.invalidate()
            Toast.makeText(this, if (adminZoneVisible) "Office Zone Visible 🏢" else "Office Zone Hidden 🛡️", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapTrail.setOnClickListener {
            resetTrackingMapIdleTimer()
            adminTrailsVisible = !adminTrailsVisible
            val alpha = if (adminTrailsVisible) 255 else 0
            val casingAlpha = if (adminTrailsVisible) 180 else 0
            
            val empId = followingEmployeeId
            if (empId != null) {
                roadLines[empId]?.let { it.outlinePaint.alpha = alpha }
                roadCasings[empId]?.let { it.outlinePaint.alpha = casingAlpha }
                travelledRoadLines[empId]?.let { it.outlinePaint.alpha = alpha }
                travelledRoadCasings[empId]?.let { it.outlinePaint.alpha = casingAlpha }
            } else {
                roadLines.values.forEach { it.outlinePaint.alpha = alpha }
                roadCasings.values.forEach { it.outlinePaint.alpha = casingAlpha }
                travelledRoadLines.values.forEach { it.outlinePaint.alpha = alpha }
                travelledRoadCasings.values.forEach { it.outlinePaint.alpha = casingAlpha }
            }
            
            binding.mapview.invalidate()
            Toast.makeText(this, if (adminTrailsVisible) "Trails Enabled ↝" else "Trails Disabled 📍", Toast.LENGTH_SHORT).show()
        }

        binding.btnMapLayer.setOnClickListener {
            resetTrackingMapIdleTimer()
            mapLayerIndex = (mapLayerIndex + 1) % 4
            val mapView = binding.mapview
            when (mapLayerIndex) {
                0 -> {
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                }
                1 -> {
                    mapView.setTileSource(TileSourceFactory.USGS_SAT)
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                }
                2 -> {
                    mapView.setTileSource(TileSourceFactory.OpenTopo)
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                }
                else -> {
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    applyDarkThemeFilter(mapView)
                }
            }
            mapView.invalidate()
        }
        binding.btnMapFullscreen.setOnClickListener {
            resetTrackingMapIdleTimer()
            toggleMapFullscreen()
        }
        binding.btnMapToolsToggle.setOnClickListener {
            resetTrackingMapIdleTimer()
            setMapControlsVisible(!mapControlsVisible)
        }

        // Google Maps-style Vertical Floating Navigation Widget Actions
        binding.btnZoomIn.setOnClickListener {
            resetTrackingMapIdleTimer()
            binding.mapview.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            resetTrackingMapIdleTimer()
            binding.mapview.controller.zoomOut()
        }

        binding.btnCompass.setOnClickListener {
            resetTrackingMapIdleTimer()
            binding.mapview.mapOrientation = 0.0f
            binding.mapview.invalidate()
            Toast.makeText(this, "Orientation Reset to North 🧭", Toast.LENGTH_SHORT).show()
        }

        binding.btnRecenterStaff.setOnClickListener {
            resetTrackingMapIdleTimer()
            val empId = followingEmployeeId
            val empLoc = if (empId != null) signalR.liveLocations.value[empId] else signalR.liveLocations.value.values.firstOrNull()
            if (empLoc != null) {
                isAutoFocusEnabled = true
                binding.btnAdminMapFollow.alpha = 1.0f
                if (followingEmployeeId != empLoc.employeeId) {
                    followingEmployeeId = empLoc.employeeId
                    updateMapMarkers(signalR.liveLocations.value.values.toList())
                }
                binding.mapview.controller.animateTo(GeoPoint(empLoc.latitude, empLoc.longitude))
                binding.mapview.controller.setZoom(17.0)
                Toast.makeText(this, "Centered on ${sharedViewModel.allEmployees.value.firstOrNull { it.employeeId == empLoc.employeeId.toString() }?.name ?: "Staff"} ⦿", Toast.LENGTH_SHORT).show()
            } else if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(officeLat, officeLon))
                binding.mapview.controller.setZoom(16.0)
                Toast.makeText(this, "Centered on Office 🏢", Toast.LENGTH_SHORT).show()
            }
        }

        // Controls are explicitly expanded/minimized by the persistent Tools
        // button. They never appear because of hover or disappear by timeout.
        setMapControlsVisible(false)
    }

    private fun setMapControlsVisible(visible: Boolean) {
        mapControlsVisible = visible
        binding.mapCommandRailScroll.visibility =
            if (visible) View.VISIBLE else View.GONE
        binding.btnMapToolsToggle.text = if (visible) "− Hide" else "⚙ Tools"
        binding.btnMapToolsToggle.isSelected = visible

        hideMapControlsRunnable?.let {
            mapControlsHandler.removeCallbacks(it)
        }
        hideMapControlsRunnable = null
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

    private fun toggleMapFullscreen() {
        isMapFullscreen = !isMapFullscreen
        val controller = WindowInsetsControllerCompat(window, binding.main)
        
        if (isMapFullscreen) {
            binding.appBar.visibility = View.GONE
            binding.filterScroll.visibility = View.GONE
            binding.cardLegend.visibility = View.GONE
            binding.cardLiveStats.visibility = View.GONE
            if (followingEmployeeId != null) {
                binding.cardSelectedLocationRail.visibility = View.VISIBLE
                binding.cardSelectedLocationRail.bringToFront()
            }

            // Extend the existing map to cover the entire screen. The selected
            // employee rail remains attached to the map itself.

            binding.mapview.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = -1
            }

            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            binding.appBar.visibility = View.VISIBLE
            binding.filterScroll.visibility = View.VISIBLE
            binding.cardLegend.visibility = View.VISIBLE
            binding.cardLiveStats.visibility = View.VISIBLE
            if (followingEmployeeId == null) {
                binding.cardSelectedLocationRail.visibility = View.GONE
            } else {
                binding.cardSelectedLocationRail.visibility = View.VISIBLE
                binding.cardSelectedLocationRail.bringToFront()
            }

            // Restore the existing map below the existing filter row. The
            // selected-location rail remains over the map, never above it.
            binding.mapview.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topToTop = -1
                topToBottom = binding.filterScroll.id
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }

            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        
        binding.mapview.postDelayed({
            binding.mapview.invalidate()
        }, 300)
    }

    private fun setupMap() {
        runCatching {
            OsmConfig.getInstance().userAgentValue = "BioMetricPayroll_Android_" + packageName
            OsmConfig.getInstance().tileDownloadThreads = 4
            OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200L * 1024L * 1024L
        }
        binding.mapview.apply {
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val rotationGestureOverlay = RotationGestureOverlay(this).apply {
                isEnabled = true
            }
            overlays.add(rotationGestureOverlay)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            applyCurrentThemeToMap(this)

            setOnTouchListener { v, event ->
                resetTrackingMapIdleTimer()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mapTouchDownX = event.x
                        mapTouchDownY = event.y
                        mapTouchDownAt = SystemClock.elapsedRealtime()
                    }
                    MotionEvent.ACTION_UP -> {
                        // A tap on the map itself must never hide/show controls.
                        // Controls are changed only through the explicit Tools button.
                    }
                }

                v.parent?.requestDisallowInterceptTouchEvent(
                    event.action != MotionEvent.ACTION_UP
                )
                false
            }
            resetTrackingMapIdleTimer()
        }
    }

    private fun applyCurrentThemeToMap(mapView: MapView) {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
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

    private fun observeTrackingPolicy() {
        policyJob?.cancel()
        policyJob = lifecycleScope.launch {
            attendancePolicy.observe().collect { policy ->
                val mode = when {
                    !policy.geoFencingEnabled -> "Geo-Fencing OFF"
                    policy.dualAttendanceEnabled -> "Biometric Attendance"
                    policy.automaticGeofencePunchingEnabled -> "Automatic Geofence Punching"
                    else -> "Manual Punch"
                }
                binding.tvTrackingPolicy.text = "${mode} • Radius ${policy.geoRadiusMeters.coerceAtLeast(0)} m"
            }
        }
    }

    private fun observeOfficeSettings() {
        officeSettingsJob?.cancel()
        officeSettingsJob = lifecycleScope.launch {
            viewModel.companySettings.collectLatest { settings ->
                if (settings == null) return@collectLatest
                if (settings.officeLatitude == 0.0 || settings.officeLongitude == 0.0) return@collectLatest

                officeLat = settings.officeLatitude
                officeLon = settings.officeLongitude
                officeRadiusMeters = settings.geoRadiusMeters.coerceAtLeast(0)

                renderOfficeOverlay()
                updateMapMarkers(signalR.liveLocations.value.values.toList())
            }
        }
    }

    private fun renderOfficeOverlay() {
        if (officeLat == 0.0 || officeLon == 0.0) return
        val point = GeoPoint(officeLat, officeLon)

        if (officeMarker == null) {
            officeMarker = Marker(binding.mapview).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createPremiumOfficeIcon()
                title = "Office Hub 🏢"
                snippet = "Office • Radius ${officeRadiusMeters}m"
            }
            binding.mapview.overlays.add(officeMarker)
        }

        if (officeCircle == null) {
            officeCircle = Polygon(binding.mapview).apply {
                fillPaint.color = 0x153B82F6
                outlinePaint.color = 0x803B82F6.toInt()
                outlinePaint.strokeWidth = 3f
            }
            binding.mapview.overlays.add(0, officeCircle)
        }

        officeMarker?.position = point
        officeMarker?.isEnabled = true
        officeMarker?.alpha = 1f
        officeMarker?.snippet = "Office • Radius ${officeRadiusMeters}m"

        officeCircle?.apply {
            points = Polygon.pointsAsCircle(point, officeRadiusMeters.toDouble())
            isEnabled = officeRadiusMeters > 0
        }

        binding.mapview.post {
            binding.mapview.invalidate()
            binding.mapview.requestLayout()
        }
    }

    private fun createPremiumOfficeIcon(): Drawable {
        val bitmap = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x55000000
        canvas.drawCircle(44f, 47f, 34f, paint)
        paint.color = "#4F46E5".toColorInt()
        canvas.drawCircle(44f, 40f, 31f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(44f, 40f, 31f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(31f, 28f, 57f, 54f, paint)
        paint.color = "#4F46E5".toColorInt()
        for (row in 0..2) for (col in 0..1) {
            canvas.drawRect(35f + col * 9f, 32f + row * 7f, 40f + col * 9f, 36f + row * 7f, paint)
        }
        return BitmapDrawable(resources, bitmap)
    }

    @OptIn(FlowPreview::class)
    private fun observeLiveLocations() {
        signalR.start()
        
        // Initial history load to fill gaps
        lifecycleScope.launch {
            val employeeId = sessionStore.employeeId()
            if (employeeId > 0) {
                val history = signalR.loadTrackingHistory(employeeId, limit = 100)
                if (history.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        updateMapMarkers(history)
                    }
                }
            }
        }

        lifecycleScope.launch {
            signalR.liveLocations
                .debounce(100L) // Throttled updates to prevent UI saturation
                .collect { liveMap ->
                    _binding?.let { updateMapMarkers(liveMap.values.toList()) }
                }
        }
    }

    private fun updateMapMarkers(locations: List<SignalRManager.LiveLocation>) {
        val mapView = binding.mapview
        val employeeData = sharedViewModel.allEmployees.value
        val firebaseEmployeeData = signalR.ownerEmployees.value
        val geoPoints = mutableListOf<GeoPoint>()

        val liveCount = locations.count { getLocStatus(it) == "Live" }
        val staleCount = locations.count { getLocStatus(it) == "Stale" }
        val offlineCount = locations.count { getLocStatus(it) == "Offline" }

        val outsideCount = locations.count { loc ->
            if (officeLat != 0.0 && officeLon != 0.0 && officeRadiusMeters > 0) {
                distanceMeters(
                    officeLat,
                    officeLon,
                    loc.latitude,
                    loc.longitude
                ) > officeRadiusMeters.toDouble()
            } else {
                !loc.isWithinAllowedRadius
            }
        }

        binding.tvLiveCount.text = "$liveCount Live"
        binding.tvOutsideCount.text = "$outsideCount Outside"
        binding.tvMapSync.text =
            "Realtime • ${locations.size} sessions • ${staleCount} stale • ${offlineCount} offline"

        // Collision handling: group by approximate location to apply offset
        val locationsByCoord = locations.groupBy { 
            "%.5f:%.5f".format(Locale.US, it.latitude, it.longitude)
        }

        val currentIds = locations.map { it.employeeId }.toSet()

        if (followingEmployeeId != null && followingEmployeeId !in currentIds) {
            binding.cardSelectedLocationRail.visibility = View.GONE
            stopSelectedRailAutoScroll()
            selectedAddressJob?.cancel()
            selectedAddressEmployeeId = null
        }

        markers.keys.filter { it !in currentIds }.toList().forEach { id ->
            mapView.overlays.remove(markers[id])
            mapView.overlays.remove(roadLines[id])
            mapView.overlays.remove(roadCasings[id])
            mapView.overlays.remove(travelledRoadLines[id])
            mapView.overlays.remove(travelledRoadCasings[id])
            mapView.overlays.remove(collisionConnectors[id])
            markers.remove(id)
            roadLines.remove(id)
            roadCasings.remove(id)
            travelledRoadLines.remove(id)
            travelledRoadCasings.remove(id)
            collisionConnectors.remove(id)
        }

        locations.forEach { loc ->
            val emp = employeeData.find { it.employeeId == loc.employeeId.toString() }
            val firebaseEmp = firebaseEmployeeData[loc.employeeId]
            
            val employeeName = emp?.name ?: firebaseEmp?.name ?: "Employee #${loc.employeeId}"
            val status = getLocStatus(loc)

            val matchesStatus = statusFilter == "All" || statusFilter == status
            val matchesSearch = searchFilter.isEmpty() || 
                    employeeName.lowercase().contains(searchFilter) || 
                    loc.employeeId.toString().contains(searchFilter)
            
            val isVisible = matchesStatus && matchesSearch

            if (!isVisible) {
                markers[loc.employeeId]?.alpha = 0f
                collisionConnectors[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                travelledRoadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                travelledRoadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                return@forEach
            }

            // Preserve the exact GPS coordinate. Only the visual pin fans out
            // when multiple employees share the same coordinate.
            val actualPoint = GeoPoint(loc.latitude, loc.longitude)
            val coordKey = "%.5f:%.5f".format(Locale.US, loc.latitude, loc.longitude)
            val group = locationsByCoord[coordKey] ?: emptyList()
            val point = if (group.size > 1) {
                val index = group.indexOf(loc)
                val angle = 2.0 * Math.PI * index / group.size
                val radius = 0.00008 // visual fan only
                GeoPoint(
                    loc.latitude + radius * Math.cos(angle),
                    loc.longitude + radius * Math.sin(angle)
                )
            } else {
                actualPoint
            }

            if (group.size > 1) {
                val connector = collisionConnectors.getOrPut(loc.employeeId) {
                    Polyline(mapView).apply {
                        outlinePaint.color = "#94A3B8".toColorInt()
                        outlinePaint.strokeWidth = 2f
                        outlinePaint.alpha = 190
                        mapView.overlays.add(0, this)
                    }
                }
                connector.setPoints(listOf(actualPoint, point))
            } else {
                collisionConnectors.remove(loc.employeeId)?.let {
                    mapView.overlays.remove(it)
                }
            }

            // Current company office/radius are the source of truth.
            val hasCurrentOffice = officeLat != 0.0 && officeLon != 0.0

            val distanceFromOffice = if (hasCurrentOffice) {
                distanceMeters(
                    officeLat,
                    officeLon,
                    loc.latitude,
                    loc.longitude
                )
            } else {
                loc.distanceMeters.coerceAtLeast(0.0)
            }

            val withinCurrentRadius =
                hasCurrentOffice &&
                officeRadiusMeters > 0 &&
                distanceFromOffice <= officeRadiusMeters.toDouble()

            if (followingEmployeeId == loc.employeeId) {
                updateSelectedLocationRail(
                    loc = loc,
                    employeeName = employeeName,
                    distanceFromOffice = distanceFromOffice,
                    withinCurrentRadius = withinCurrentRadius,
                    status = status
                )
            }

            val marker = markers.getOrPut(loc.employeeId) {
                Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = emp?.name ?: "Staff #${loc.employeeId}"
                    mapView.overlays.add(this)

                    setOnMarkerClickListener { clicked, map ->
                        // Marker selection is handled by the selected-details rail.
                        // Never open an information popup/snippet above the marker.
                        clicked.closeInfoWindow()
                        followingEmployeeId?.let { prevId ->
                            if (prevId != loc.employeeId) {
                                roadLines[prevId]?.outlinePaint?.alpha = 0
                                roadCasings[prevId]?.outlinePaint?.alpha = 0
                                travelledRoadLines[prevId]?.outlinePaint?.alpha = 0
                                travelledRoadCasings[prevId]?.outlinePaint?.alpha = 0
                            }
                        }
                        followingEmployeeId = loc.employeeId
                        isAutoFocusEnabled = true
                        map.controller.animateTo(clicked.position)
                        val selectedDistance = if (officeLat != 0.0 && officeLon != 0.0) {
                            distanceMeters(officeLat, officeLon, loc.latitude, loc.longitude)
                        } else {
                            loc.distanceMeters.coerceAtLeast(0.0)
                        }
                        val selectedWithin = officeLat != 0.0 && officeLon != 0.0 &&
                            officeRadiusMeters > 0 && selectedDistance <= officeRadiusMeters.toDouble()
                        updateSelectedLocationRail(
                            loc = loc,
                            employeeName = employeeName,
                            distanceFromOffice = selectedDistance,
                            withinCurrentRadius = selectedWithin,
                            status = status
                        )

                        // Immediately trigger travelled route (green) and office route (blue) for selected employee
                        if (adminTrailsVisible) {
                            updateSelectedEmployeeJourneyRoute(loc.employeeId, actualPoint)
                            updateActivityRoadRoute(loc.employeeId, actualPoint)
                        }
                        true
                    }
                }
            }

            marker.alpha = 1f
            
            MarkerAnimationHelper.animateMarker(
                marker, 
                point, 
                loc.bearing.toFloat(), 
                loc.employeeId
            ) { animatedPoint ->
                collisionConnectors[loc.employeeId]?.setPoints(
                    listOf(actualPoint, animatedPoint)
                )

                // Update road lines synchronously with marker movement
                runCatching {
                    val isSelected = followingEmployeeId == loc.employeeId && adminTrailsVisible
                    val trailAlpha = if (isSelected) 255 else 0
                    val casingAlpha = if (isSelected) 180 else 0

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

                    // Also synchronize travelled road route endpoint with marker animation
                    travelledRoadLines[loc.employeeId]?.let { tl ->
                        val pts = tl.actualPoints.toMutableList()
                        if (pts.isNotEmpty()) {
                            pts[pts.size - 1] = animatedPoint
                            tl.setPoints(pts)
                        }
                        tl.outlinePaint.alpha = trailAlpha
                    }
                    travelledRoadCasings[loc.employeeId]?.let { tc ->
                        val pts = tc.actualPoints.toMutableList()
                        if (pts.isNotEmpty()) {
                            pts[pts.size - 1] = animatedPoint
                            tc.setPoints(pts)
                        }
                        tc.outlinePaint.alpha = casingAlpha
                    }
                }
                
                // Smoothly follow the selected employee throughout journey
                if (isAutoFocusEnabled && followingEmployeeId == loc.employeeId) {
                    mapView.controller.animateTo(animatedPoint)
                }
                
                mapView.invalidate()
            }

            val initials = getInitials(emp?.name ?: "E")
            val cacheKey = "${initials}_${withinCurrentRadius}_$status"
            marker.icon = iconCache.getOrPut(cacheKey) {
                createPremiumMarkerIcon(
                    initials,
                    withinCurrentRadius,
                    status
                )
            }

            // Keep employee markers clean. Detailed information belongs only
            // in the selected-employee rail, including fullscreen mode.
            marker.snippet = ""
            marker.title = ""

            // REQUIREMENT: Only show route for selected employee
            if (followingEmployeeId == loc.employeeId) {
                if (adminTrailsVisible) {
                    updateSelectedEmployeeJourneyRoute(loc.employeeId, point)
                    updateActivityRoadRoute(loc.employeeId, point)
                }
            } else {
                roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                travelledRoadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                travelledRoadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
            }
            geoPoints.add(point)
        }

        if (!hasTrackingInitialFocused && (locations.isNotEmpty() || (officeLat != 0.0 && officeLon != 0.0))) {
            hasTrackingInitialFocused = true
            fitCompanyAndStaff(animated = false)
        }

        mapView.invalidate()
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Double {
        val result = FloatArray(1)

        android.location.Location.distanceBetween(
            fromLat,
            fromLon,
            toLat,
            toLon,
            result
        )

        return result[0].toDouble().coerceAtLeast(0.0)
    }

    private fun updateSelectedEmployeeJourneyRoute(empId: Int, currentPoint: GeoPoint) {
        val last = lastTravelledRouteUpdate[empId] ?: 0L
        if (System.currentTimeMillis() - last < 15000L) return

        travelledRouteJobs[empId]?.cancel()
        travelledRouteJobs[empId] = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Fetch recent tracking history for this employee
                val history = signalR.loadTrackingHistory(empId, limit = 50)
                val rawPoints = history.map { GeoPoint(it.latitude, it.longitude) }.toMutableList()
                if (rawPoints.isEmpty() || distanceMeters(rawPoints.last().latitude, rawPoints.last().longitude, currentPoint.latitude, currentPoint.longitude) > 5.0) {
                    rawPoints.add(currentPoint)
                }

                // Filter outliers and duplicates (suppress teleport spikes > 1500m & duplicates < 4m)
                val filtered = mutableListOf<GeoPoint>()
                for (pt in rawPoints) {
                    if (filtered.isEmpty()) {
                        filtered.add(pt)
                    } else {
                        val d = distanceMeters(filtered.last().latitude, filtered.last().longitude, pt.latitude, pt.longitude)
                        if (d in 4.0..1500.0) {
                            filtered.add(pt)
                        }
                    }
                }
                if (filtered.size < 2 && rawPoints.size >= 2) {
                    filtered.add(rawPoints.last())
                }

                var snapped: List<GeoPoint> = emptyList()
                if (filtered.size >= 2) {
                    // Downsample if too many points for OSRM URL
                    val sampled = if (filtered.size > 25) {
                        val step = (filtered.size / 24).coerceAtLeast(1)
                        val s = mutableListOf<GeoPoint>()
                        s.add(filtered.first())
                        for (i in 1 until filtered.size - 1 step step) {
                            s.add(filtered[i])
                        }
                        s.add(filtered.last())
                        s
                    } else filtered

                    try {
                        val coords = sampled.joinToString(";") { "${it.longitude},${it.latitude}" }
                        val response = osrmApi.getRoute(coords)
                        if (response.isSuccessful) {
                            val encoded = response.body()?.routes?.firstOrNull()?.geometry
                            if (!encoded.isNullOrBlank()) {
                                snapped = PolylineDecoder.decode(encoded)
                            }
                        }
                    } catch (_: Exception) {}

                    // Fallback to Catmull-Rom smooth spline interpolation if OSRM is unavailable
                    if (snapped.size < 2) {
                        snapped = generateSmoothSpline(filtered)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (_binding != null && followingEmployeeId == empId) {
                        drawTravelledRoadRoute(empId, snapped)
                        lastTravelledRouteUpdate[empId] = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun drawTravelledRoadRoute(empId: Int, points: List<GeoPoint>) {
        val mapView = binding.mapview
        if (points.size < 2) {
            travelledRoadLines[empId]?.outlinePaint?.alpha = 0
            travelledRoadCasings[empId]?.outlinePaint?.alpha = 0
            mapView.invalidate()
            return
        }

        val casing = travelledRoadCasings.getOrPut(empId) {
            Polyline(mapView).apply {
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 14f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.alpha = 180
                mapView.overlays.add(0, this)
            }
        }
        val line = travelledRoadLines.getOrPut(empId) {
            Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#10B981") // Vibrant Emerald Green (Way Arrived)
                outlinePaint.strokeWidth = 8f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.alpha = 255
                mapView.overlays.add(1, this)
            }
        }

        casing.setPoints(points)
        casing.outlinePaint.alpha = 180
        line.setPoints(points)
        line.outlinePaint.alpha = 255
        mapView.invalidate()
    }

    private fun generateSmoothSpline(points: List<GeoPoint>, pointsPerSegment: Int = 5): List<GeoPoint> {
        if (points.size <= 2) return points
        val result = mutableListOf<GeoPoint>()
        for (i in 0 until points.size - 1) {
            val p0 = if (i > 0) points[i - 1] else points[i]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = if (i < points.size - 2) points[i + 2] else p2
            for (step in 0 until pointsPerSegment) {
                val t = step.toDouble() / pointsPerSegment
                val t2 = t * t
                val t3 = t2 * t
                val lat = 0.5 * ((2 * p1.latitude) + (-p0.latitude + p2.latitude) * t + (2 * p0.latitude - 5 * p1.latitude + 4 * p2.latitude - p3.latitude) * t2 + (-p0.latitude + 3 * p1.latitude - 3 * p2.latitude + p3.latitude) * t3)
                val lng = 0.5 * ((2 * p1.longitude) + (-p0.longitude + p2.longitude) * t + (2 * p0.longitude - 5 * p1.longitude + 4 * p2.longitude - p3.longitude) * t2 + (-p0.longitude + 3 * p1.longitude - 3 * p2.longitude + p3.longitude) * t3)
                result.add(GeoPoint(lat, lng))
            }
        }
        result.add(points.last())
        return result
    }

    private fun updateActivityRoadRoute(empId: Int, userPoint: GeoPoint) {
        val last = lastRouteUpdate[empId] ?: 0L
        if (System.currentTimeMillis() - last < 30000L) return
        
        roadRouteJobs[empId]?.cancel()
        roadRouteJobs[empId] = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destLat = if (officeLat != 0.0) officeLat else (sharedViewModel.selectedShop.value?.latitude ?: 11.9416)
                val destLon = if (officeLon != 0.0) officeLon else (sharedViewModel.selectedShop.value?.longitude ?: 79.8083)
                
                val coords = "${userPoint.longitude},${userPoint.latitude};$destLon,$destLat"
                val response = osrmApi.getRoute(coords)
                if (response.isSuccessful) {
                    val encoded = response.body()?.routes?.firstOrNull()?.geometry
                    if (encoded != null) {
                        val decoded = PolylineDecoder.decode(encoded)
                        withContext(Dispatchers.Main) {
                            _binding?.let {
                                drawActivityRoute(empId, decoded)
                                lastRouteUpdate[empId] = System.currentTimeMillis()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun drawActivityRoute(empId: Int, points: List<GeoPoint>) {
        val mapView = binding.mapview
        val casing = roadCasings.getOrPut(empId) {
            Polyline(mapView).apply {
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 14f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.alpha = 180
                mapView.overlays.add(0, this)
            }
        }
        val line = roadLines.getOrPut(empId) {
            Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#3B82F6") // Blue for office path as per legend
                outlinePaint.strokeWidth = 8f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                mapView.overlays.add(1, this)
            }
        }
        casing.setPoints(points)
        casing.outlinePaint.alpha = 180
        line.setPoints(points)
        line.outlinePaint.alpha = 255
        mapView.invalidate()
    }

    private fun getLocStatus(loc: SignalRManager.LiveLocation): String {
        val timestamp = loc.timestamp ?: return "Offline"
        return try {
            val parsed = java.time.Instant.parse(timestamp)
            val ageMs = (System.currentTimeMillis() - parsed.toEpochMilli()).coerceAtLeast(0L)
            when {
                ageMs <= 300_000L -> "Live"
                ageMs <= 900_000L -> "Stale"
                else -> "Offline"
            }
        } catch (_: Exception) {
            "Offline"
        }
    }

    private fun formatSpeed(mps: Double): String {
        if (mps <= 0.15) return "Stationary"
        val kmh = mps * 3.6
        return if (kmh < 1) "Slow" else "${kmh.toInt()} km/h"
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()}m" else String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

    private fun animateMarker(marker: Marker, toPosition: GeoPoint, empId: Int? = null) {
        // Deprecated: replaced by util/MarkerAnimationHelper
    }

    private fun getInitials(name: String): String {
        val parts = name.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0][0]}${parts.last()[0]}".uppercase()
        else name.take(1).uppercase()
    }

    private fun createPremiumMarkerIcon(initials: String, within: Boolean, status: String): Drawable {
        val color = if (within) "#3B82F6".toColorInt() else "#EF4444".toColorInt()
        val bitmap = Bitmap.createBitmap(100, 130, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        val path = Path()
        path.moveTo(50f, 130f)
        path.cubicTo(100f, 80f, 100f, 10f, 50f, 10f)
        path.cubicTo(0f, 10f, 0f, 80f, 50f, 130f)
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(50f, 55f, 35f, paint)
        paint.color = color
        paint.textSize = 32f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText(initials, 50f, 65f, paint)
        
        // Status Dot
        val statusColor = when(status) {
            "Live" -> "#22C55E".toColorInt()
            "Stale" -> "#F59E0B".toColorInt()
            else -> "#94A3B8".toColorInt()
        }
        paint.color = Color.WHITE
        canvas.drawCircle(85f, 25f, 12f, paint)
        paint.color = statusColor
        canvas.drawCircle(85f, 25f, 8f, paint)
        
        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() {
        super.onResume()
        binding.mapview.onResume()
        // Re-read Firebase/SSOT immediately when the map returns to foreground.
        // Do not require Admin logout/login to recover a stale live snapshot.
        signalR.reconcileLiveLocationsNow()
    }

    override fun onPause() {
        super.onPause()
        binding.mapview.onPause()
    }

    override fun onDestroy() {
        policyJob?.cancel()
        officeSettingsJob?.cancel()
        roadRouteJobs.values.forEach { it.cancel() }
        roadRouteJobs.clear()
        travelledRouteJobs.values.forEach { it.cancel() }
        travelledRouteJobs.clear()
        markerAnimations.values.forEach { it.cancel() }
        markerAnimations.clear()
        collisionConnectors.clear()
        roadLines.clear()
        roadCasings.clear()
        travelledRoadLines.clear()
        travelledRoadCasings.clear()
        hideMapControlsRunnable?.let { mapControlsHandler.removeCallbacks(it) }
        trackingMapIdleHandler.removeCallbacks(trackingMapIdleRunnable)
        stopSelectedRailAutoScroll()
        iconCache.clear()
        
        _binding?.mapview?.onDetach()
        super.onDestroy()
        _binding = null
    }
}