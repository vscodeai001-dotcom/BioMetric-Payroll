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
            // Trigger marker update with current SignalR data
            updateMapMarkers(signalR.liveLocations.value.values.toList())
        }
    }

    private var isMapFullscreen = false
    private var mapLayerIndex = 0

    private fun setupPremiumMapControls() {
        binding.btnMapFit.setOnClickListener {
            fitAllVisibleStaff()
        }
        binding.btnMapLayer.setOnClickListener {
            mapLayerIndex = (mapLayerIndex + 1) % 3
            when (mapLayerIndex) {
                0 -> {
                    binding.mapview.setTileSource(TileSourceFactory.MAPNIK)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                1 -> {
                    binding.mapview.setTileSource(TileSourceFactory.USGS_SAT)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
                else -> {
                    binding.mapview.setTileSource(TileSourceFactory.MAPNIK)
                    binding.mapview.overlayManager.tilesOverlay.setColorFilter(null)
                }
            }
            binding.mapview.invalidate()
        }
        binding.btnMapFullscreen.setOnClickListener { toggleMapFullscreen() }
    }

    private fun fitAllVisibleStaff() {
        val points = signalR.liveLocations.value.values.map { GeoPoint(it.latitude, it.longitude) }.toMutableList()
        if (officeLat != 0.0 && officeLon != 0.0) points.add(GeoPoint(officeLat, officeLon))
        if (points.isEmpty()) return
        val bounds = BoundingBox.fromGeoPoints(points)
        binding.mapview.zoomToBoundingBox(bounds, true, 150)
    }

    private fun toggleMapFullscreen() {
        isMapFullscreen = !isMapFullscreen
        val controller = WindowInsetsControllerCompat(window, binding.main)
        val mapParams = binding.mapview.layoutParams as ConstraintLayout.LayoutParams
        if (isMapFullscreen) {
            binding.appBar.visibility = View.GONE
            binding.filterScroll.visibility = View.GONE
            binding.cardLegend.visibility = View.GONE
            binding.cardLiveStats.visibility = View.GONE

            mapParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
            mapParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.mapview.layoutParams = mapParams

            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            binding.appBar.visibility = View.VISIBLE
            binding.filterScroll.visibility = View.VISIBLE
            binding.cardLegend.visibility = View.VISIBLE
            binding.cardLiveStats.visibility = View.VISIBLE

            mapParams.topToTop = ConstraintLayout.LayoutParams.UNSET
            mapParams.topToBottom = binding.filterScroll.id
            mapParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.mapview.layoutParams = mapParams

            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        binding.mapview.postDelayed({
            binding.mapview.invalidate()
            if (officeLat != 0.0 && officeLon != 0.0) {
                binding.mapview.controller.setCenter(GeoPoint(officeLat, officeLon))
            }
        }, 220)
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
            overlayManager.tilesOverlay.setColorFilter(null)
            controller.setZoom(16.0)
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
            val status = getLocStatus(loc)

            val isFilteredOut = statusFilter != "All" && statusFilter != status

            if (isFilteredOut) {
                markers[loc.employeeId]?.alpha = 0f
                roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                return@forEach
            }

            val point = GeoPoint(loc.latitude, loc.longitude)

            // Current company office/radius are the source of truth.
            // Do not use a stale radius/status from the live payload.
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
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = emp?.name ?: "Staff #${loc.employeeId}"
                    mapView.overlays.add(this)

                    setOnMarkerClickListener { clicked, map ->
                        map.controller.animateTo(clicked.position)
                        clicked.showInfoWindow()
                        true
                    }
                }
            }

            marker.alpha = 1f
            animateMarker(marker, point, loc.employeeId)

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

            roadLines[loc.employeeId]?.outlinePaint?.alpha = 255
            roadCasings[loc.employeeId]?.outlinePaint?.alpha = 255

            updateActivityRoadRoute(loc.employeeId, point)
            geoPoints.add(point)
        }

        binding.fabRefresh.setOnClickListener {
            if (geoPoints.isNotEmpty()) {
                val bounds = BoundingBox.fromGeoPoints(geoPoints)
                mapView.zoomToBoundingBox(bounds, true, 150)
            } else if (officeLat != 0.0 && officeLon != 0.0) {
                mapView.controller.setCenter(GeoPoint(officeLat, officeLon))
                mapView.controller.setZoom(16.0)
            }
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
        if (empId == null) {
            marker.position = toPosition
            return
        }

        // Cancel existing animation for this staff to prevent concurrent map invalidations
        markerAnimations[empId]?.cancel()

        val startPosition = marker.position
        if (startPosition.latitude == 0.0) {
            marker.position = toPosition
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                if (_binding == null) {
                    animation.cancel()
                    return@addUpdateListener
                }

                val t = animation.animatedValue as Float
                val lat = t * toPosition.latitude + (1 - t) * startPosition.latitude
                val lng = t * toPosition.longitude + (1 - t) * startPosition.longitude
                val point = GeoPoint(lat, lng)

                marker.position = point

                // Safely update road lines during animation
                runCatching {
                    roadLines[empId]?.let { l ->
                        val pts = l.actualPoints.toMutableList()
                        if (pts.size >= 2) {
                            pts[0] = point
                            l.setPoints(pts)
                        }
                    }
                    roadCasings[empId]?.let { c ->
                        val pts = c.actualPoints.toMutableList()
                        if (pts.size >= 2) {
                            pts[0] = point
                            c.setPoints(pts)
                        }
                    }
                }
                
                binding.mapview.invalidate()
            }
        }

        markerAnimations[empId] = animator
        animator.start()
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