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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
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
import javax.inject.Inject

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
    private val markerAnimations = mutableMapOf<Int, ValueAnimator>()
    private val lastRouteUpdate = mutableMapOf<Int, Long>()
    private val roadRouteJobs = mutableMapOf<Int, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private var statusFilter = "All"
    private var searchFilter = ""
    private var isAutoFocusEnabled = true
    private var followingEmployeeId: Int? = null
    
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

    private fun setupPremiumMapControls() {
        binding.btnMapFit.setOnClickListener {
            isAutoFocusEnabled = false
            followingEmployeeId = null
            binding.btnAdminMapFollow.alpha = 0.4f
            
            val points = signalR.liveLocations.value.values
                .map { GeoPoint(it.latitude, it.longitude) }.toMutableList()
            if (officeLat != 0.0 && officeLon != 0.0) points.add(GeoPoint(officeLat, officeLon))
            
            if (points.isNotEmpty()) {
                val bounds = BoundingBox.fromGeoPoints(points)
                binding.mapview.zoomToBoundingBox(bounds, true, 150)
            }
            Toast.makeText(this, "Fitting all staff", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapOffice.setOnClickListener {
            isAutoFocusEnabled = false
            followingEmployeeId = null
            binding.btnAdminMapFollow.alpha = 0.4f
            
            if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.animateTo(GeoPoint(officeLat, officeLon))
                binding.mapview.controller.setZoom(16.0)
            }
        }

        binding.btnAdminMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
        binding.btnAdminMapFollow.setOnClickListener {
            isAutoFocusEnabled = !isAutoFocusEnabled
            followingEmployeeId = null
            binding.btnAdminMapFollow.alpha = if (isAutoFocusEnabled) 1.0f else 0.4f
            Toast.makeText(this, if (isAutoFocusEnabled) "Auto-follow enabled ⦿" else "Auto-follow disabled ◌", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapZone.setOnClickListener {
            adminZoneVisible = !adminZoneVisible
            val alpha = if (adminZoneVisible) 0x40 else 0
            officeCircle?.fillPaint?.alpha = alpha
            officeCircle?.outlinePaint?.alpha = if (adminZoneVisible) 0xA0 else 0
            binding.mapview.invalidate()
            Toast.makeText(this, if (adminZoneVisible) "Office Zone Visible 🏢" else "Office Zone Hidden 🛡️", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdminMapTrail.setOnClickListener {
            adminTrailsVisible = !adminTrailsVisible
            val alpha = if (adminTrailsVisible) 255 else 0
            val casingAlpha = if (adminTrailsVisible) 150 else 0
            
            roadLines.values.forEach { it.outlinePaint.alpha = alpha }
            roadCasings.values.forEach { it.outlinePaint.alpha = casingAlpha }
            
            binding.mapview.invalidate()
            Toast.makeText(this, if (adminTrailsVisible) "Trails Enabled ↝" else "Trails Disabled 📍", Toast.LENGTH_SHORT).show()
        }

        binding.btnMapLayer.setOnClickListener {
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
        binding.btnMapFullscreen.setOnClickListener { toggleMapFullscreen() }
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

            // Extend map to cover the entire screen
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

            // Restore map to its bounded position
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
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            applyCurrentThemeToMap(this)
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

        markers.keys.filter { it !in currentIds }.toList().forEach { id ->
            mapView.overlays.remove(markers[id])
            mapView.overlays.remove(roadLines[id])
            mapView.overlays.remove(roadCasings[id])
            markers.remove(id)
            roadLines.remove(id)
            roadCasings.remove(id)
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
                roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                return@forEach
            }

            // Apply spiral offset for overlapping markers
            val coordKey = "%.5f:%.5f".format(Locale.US, loc.latitude, loc.longitude)
            val group = locationsByCoord[coordKey] ?: emptyList()
            val point = if (group.size > 1) {
                val index = group.indexOf(loc)
                val angle = 2.0 * Math.PI * index / group.size
                // REQUIREMENT: Increase radius so markers are clearly visible 
                // even when at the exact same coordinate.
                val radius = 0.00015 // ~15-18 meters offset
                GeoPoint(
                    loc.latitude + radius * Math.cos(angle),
                    loc.longitude + radius * Math.sin(angle)
                )
            } else {
                GeoPoint(loc.latitude, loc.longitude)
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

            val marker = markers.getOrPut(loc.employeeId) {
                Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = emp?.name ?: "Staff #${loc.employeeId}"
                    mapView.overlays.add(this)

                    setOnMarkerClickListener { clicked, map ->
                        followingEmployeeId = loc.employeeId
                        isAutoFocusEnabled = true
                        map.controller.animateTo(clicked.position)
                        clicked.showInfoWindow()
                        
                        // Immediately trigger route for selected employee
                        updateActivityRoadRoute(loc.employeeId, point)
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
                // Update road lines synchronously with marker movement
                runCatching {
                    val isSelected = followingEmployeeId == loc.employeeId
                    val trailAlpha = if (isSelected) 255 else 0
                    val casingAlpha = if (isSelected) 150 else 0

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
                
                // Smoothly follow the selected employee
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

            val speedText = formatSpeed(loc.speedMps)
            marker.snippet =
                "Status: $status | Speed: $speedText\n" +
                "Dist: ${formatDistance(distanceFromOffice)} | " +
                "Radius: ${officeRadiusMeters}m | " +
                "${if (withinCurrentRadius) "Within range" else "Outside range"} | " +
                "Accuracy: ±${loc.accuracyMeters.toInt()}m"

            // REQUIREMENT: Only show route for selected employee
            if (followingEmployeeId == loc.employeeId) {
                updateActivityRoadRoute(loc.employeeId, point)
            } else {
                roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
            }
            geoPoints.add(point)
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

    private fun updateActivityRoadRoute(empId: Int, userPoint: GeoPoint) {
        val last = lastRouteUpdate[empId] ?: 0L
        if (System.currentTimeMillis() - last < 30000L) return
        
        roadRouteJobs[empId]?.cancel()
        roadRouteJobs[empId] = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = sharedViewModel.selectedShop.value
                val officeLat = settings?.latitude ?: 11.9416
                val officeLon = settings?.longitude ?: 79.8083
                
                val coords = "${userPoint.longitude},${userPoint.latitude};$officeLon,$officeLat"
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
                outlinePaint.alpha = 150
                mapView.overlays.add(0, this)
            }
        }
        val line = roadLines.getOrPut(empId) {
            Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#4F46E5")
                outlinePaint.strokeWidth = 8f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                mapView.overlays.add(1, this)
            }
        }
        casing.setPoints(points)
        line.setPoints(points)
        mapView.invalidate()
    }

    private fun getLocStatus(loc: SignalRManager.LiveLocation): String {
        val timestamp = loc.timestamp ?: return "Offline"
        return try {
            val parsed = java.time.Instant.parse(timestamp)
            val ageMs = (System.currentTimeMillis() - parsed.toEpochMilli()).coerceAtLeast(0L)
            when {
                ageMs <= 120_000L -> "Live"
                ageMs <= 300_000L -> "Stale"
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
        markerAnimations.values.forEach { it.cancel() }
        markerAnimations.clear()
        iconCache.clear()
        
        _binding?.mapview?.onDetach()
        super.onDestroy()
        _binding = null
    }
}