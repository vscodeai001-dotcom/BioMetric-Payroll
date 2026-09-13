package com.biometric.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.location.Location
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
import com.biometric.app.util.BatteryOptimizationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.TimeUnit

import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.util.BoundingBox
import android.graphics.ColorMatrixColorFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.toColorInt
import com.biometric.app.api.AdminFeatureSettingsDto
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.OemBackgroundHelper
import com.biometric.app.util.PolylineDecoder
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.text.NumberFormat

@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class MainActivity : MotionBaseActivity(), PaymentResultListener {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var brandingManager: BrandingManager
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var osrmApi: OsrmApiService
    @Inject lateinit var sessionStore: MobileSessionStore

    private lateinit var adapter: ShopAdapter

    private val markers = mutableMapOf<Int, Marker>()
    private val markers2 = mutableMapOf<Int, Marker>()
    private val roadLines = mutableMapOf<Int, Polyline>()
    private val roadCasings = mutableMapOf<Int, Polyline>()
    private val roadLines2 = mutableMapOf<Int, Polyline>()
    private val roadCasings2 = mutableMapOf<Int, Polyline>()
    private val lastRouteUpdate = mutableMapOf<Int, Long>()
    private val markerAnimations = mutableMapOf<Int, ValueAnimator>()
    private val adminRoadRouteJobs = mutableMapOf<Int, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private var officeMarker: Marker? = null
    private var geofenceCircle: Polygon? = null
    private var statusFilter = "All"
    private val approvalFilter = MutableStateFlow("All")
    private val workforceSearchQuery = MutableStateFlow("")

    private val driveManager by lazy { GoogleDriveManager(this) }
    private var tvLastSynced: TextView? = null
    private var refreshJob: Job? = null

    private lateinit var workforceAdapter: StaffSummaryAdapter
    private lateinit var approvalsAdapter: ApprovalsAdapter

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
        val headers = setupDualHeader(binding.toolbar, "Workforce Hub 🏢 ✨", "Organization Dashboard 🛡️")
        headers.btnShop?.visibility = View.GONE
        tvLastSynced = headers.status
        tvLastSynced?.visibility = View.VISIBLE

        setupBottomNavigation()
        setupListeners()
        setupSwipeRefresh()
        setupRecyclerViews()
        observeViewModel()
        
        lifecycleScope.launch {
            // High-priority UI components first
            delay(300)
            viewModel.startRealtimeSync()
            applyRolePermissions()

            // Map and Search (Heavier) next
            delay(800)
            setupRealTimeSync()
            setupAdminMap()
            setupAdminFilters()
            setupWorkforceSearch()
            setupApprovalFilters()

            // SSOT: Initial full sync
            delay(1200)
            viewModel.startNeonSync()
            viewModel.triggerRefresh() 
            
            // Low-priority animations last
            findViewById<LottieAnimationView>(R.id.backgroundParticles)?.let {
                it.visibility = View.VISIBLE
                it.playAnimation()
            }
        }

        if (driveManager.isUserSignedIn()) {
            scheduleDailyBackup()
        }
        checkBatteryOptimizations()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> showHub("DASHBOARD")
                R.id.nav_workforce -> showHub("WORKFORCE")
                R.id.nav_approvals -> showHub("APPROVALS")
                R.id.nav_tracking -> showHub("TRACKING")
                R.id.nav_reports -> showHub("REPORTS")
                else -> false
            }
        }
        showHub("DASHBOARD")
    }

    private fun showHub(hub: String): Boolean {
        binding.hubDashboard.visibility = if (hub == "DASHBOARD") View.VISIBLE else View.GONE
        binding.hubWorkforce.visibility = if (hub == "WORKFORCE") View.VISIBLE else View.GONE
        binding.hubApprovals.visibility = if (hub == "APPROVALS") View.VISIBLE else View.GONE
        binding.hubTracking.visibility = if (hub == "TRACKING") View.VISIBLE else View.GONE
        binding.hubReports.visibility = if (hub == "REPORTS") View.VISIBLE else View.GONE
        
        val activeHub = when (hub) {
            "DASHBOARD" -> binding.hubDashboard
            "WORKFORCE" -> binding.hubWorkforce
            "APPROVALS" -> binding.hubApprovals
            "TRACKING" -> binding.hubTracking
            "REPORTS" -> binding.hubReports
            else -> null
        }
        activeHub?.let { animateContentEntry(it) }
        setupDualHeader(binding.toolbar, when (hub) {
            "DASHBOARD" -> "Workforce Hub 🏢 ✨"
            "WORKFORCE" -> "Staff Management 👥 🛡️"
            "APPROVALS" -> "Admin Approvals ✅ ⚡"
            "TRACKING" -> "Live Operations 🛰️ 📍"
            "REPORTS" -> "Business Intelligence 📈 💎"
            else -> "Organization Dashboard 🛡️ ✨"
        }, "Organization Dashboard 🛡️")
        return true
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

    private fun setupAdminMap() {
        val maps = listOf(binding.adminMapView, binding.commandCenterMapView)
        maps.forEach { map ->
            map.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(16.0)
                applyCurrentThemeToMap(this)
            }
        }
        lifecycleScope.launch {
            delay(800)
            _binding?.let { b ->
                b.llAdminMapLoading.visibility = View.GONE
                observeLiveLocations()
            }
        }
    }

    private fun setupAdminFilters() {
        val listener = { id: Int ->
            statusFilter = when(id) {
                R.id.chipAdminLive, R.id.chipCommandLive -> "Live"
                R.id.chipAdminStale -> "Stale"
                R.id.chipAdminOffline -> "Offline"
                else -> "All"
            }
            updateAdminMarkers(signalR.liveLocations.value.values.toList())
        }
        binding.chipGroupAdminStatus.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { listener(it) } }
        binding.chipGroupCommandStatus.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { listener(it) } }
    }

    private fun setupWorkforceSearch() {
        binding.etWorkforceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                workforceSearchQuery.value = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupApprovalFilters() {
        binding.chipGroupApprovals.setOnCheckedStateChangeListener { _, ids ->
            val filter = when (ids.firstOrNull()) {
                R.id.chipApprovalRegs -> "Regularizations"
                R.id.chipApprovalLeaves -> "Leaves"
                else -> "All"
            }
            approvalFilter.value = filter
        }
    }

    private fun observeLiveLocations() {
        lifecycleScope.launch {
            signalR.liveLocations
                .collectLatest { liveMap ->
                    // Stable Flow API: wait briefly for a burst to settle, then render the latest snapshot.
                    delay(500L)
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
    }

    private fun updateOfficeOnMap(lat: Double, lon: Double, radius: Int) {
        _binding?.let { b ->
            val maps = listOf(b.adminMapView, b.commandCenterMapView)
            if (lat == 0.0) return
            val point = GeoPoint(lat, lon)
            if (officeMarker == null) {
                officeMarker = Marker(b.adminMapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createPremiumOfficeIcon()
                    title = "Office Hub 🏢"
                }
                b.adminMapView.overlays.add(officeMarker)
                geofenceCircle = Polygon(b.adminMapView).apply {
                    points = Polygon.pointsAsCircle(point, radius.toDouble())
                    fillPaint.color = 0x153B82F6
                    outlinePaint.color = 0x403B82F6
                    outlinePaint.strokeWidth = 2f
                }
                b.adminMapView.overlays.add(0, geofenceCircle)
            }
            officeMarker?.position = point
            geofenceCircle?.points = Polygon.pointsAsCircle(point, radius.toDouble())
            maps.forEach { it.invalidate() }
        }
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
            val commandMap = b.commandCenterMapView
            val employeeData = sharedViewModel.allEmployees.value
            val geoPoints = mutableListOf<GeoPoint>()
            val currentIds = locations.map { it.employeeId }
            
            markers.keys.filter { !currentIds.contains(it) }.forEach { id ->
                dashboardMap.overlays.remove(markers[id]); markers.remove(id)
                dashboardMap.overlays.remove(roadLines[id]); roadLines.remove(id)
                dashboardMap.overlays.remove(roadCasings[id]); roadCasings.remove(id)
            }
            markers2.keys.filter { !currentIds.contains(it) }.forEach { id ->
                commandMap.overlays.remove(markers2[id]); markers2.remove(id)
            }

            locations.forEach { loc ->
                val emp = employeeData.find { it.employeeId == loc.employeeId.toString() }
                val status = getLocStatus(loc)
                val isFilteredOut = statusFilter != "All" && statusFilter != status
                if (isFilteredOut) {
                    markers[loc.employeeId]?.alpha = 0f; markers2[loc.employeeId]?.alpha = 0f
                    roadLines[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    roadCasings[loc.employeeId]?.let { it.outlinePaint.alpha = 0 }
                    return@forEach
                }

                // PORTED: Update Global Geofence Circle if any employee reports a different radius
                // This ensures the Admin Map circle updates to 20m instantly when changed in Settings.
                if (loc.allowedRadiusMeters > 0 && loc.allowedRadiusMeters != geofenceCircle?.points?.let { radiusFromPoints(it, officeMarker?.position) }) {
                    officeMarker?.position?.let { updateOfficeOnMap(it.latitude, it.longitude, loc.allowedRadiusMeters) }
                }

                val point = GeoPoint(loc.latitude, loc.longitude)
                val m1 = markers.getOrPut(loc.employeeId) { Marker(dashboardMap).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); dashboardMap.overlays.add(this) } }
                val m2 = markers2.getOrPut(loc.employeeId) { Marker(commandMap).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); commandMap.overlays.add(this) } }
                
                m1.alpha = 1f; m2.alpha = 1f; m1.title = emp?.name; m2.title = emp?.name
                
                // Optimization: Only animate if the position changed significantly (> 0.5m)
                val prevPos = m1.position
                val dist = distanceBetween(prevPos, point)
                if (dist > 0.5) {
                    animateMarker(m1, m2, point, loc.employeeId)
                } else {
                    m1.position = point
                    m2.position = point
                }
                
                val initials = getInitials(emp?.name ?: "E")
                val cacheKey = "${initials}_${loc.isWithinAllowedRadius}_$status"
                val icon = iconCache.getOrPut(cacheKey) { 
                    createPremiumMarkerIcon(initials, loc.isWithinAllowedRadius, status) 
                }
                m1.icon = icon; m2.icon = icon
                val snippet = "Status: $status | Speed: ${formatSpeed(loc.speedMps)}\nDist: ${formatDistance(loc.distanceMeters)}"
                m1.snippet = snippet; m2.snippet = snippet
                updateAdminRoadRoute(loc.employeeId, point)
                geoPoints.add(point)
            }
            b.tvCommandLiveCount.text = getString(R.string.label_live_operators_format, locations.count { getLocStatus(it) == "Live" })
            b.btnRefreshMap.setOnClickListener {
                createBoundingBox(geoPoints)?.let { box ->
                    dashboardMap.zoomToBoundingBox(box, true, 120)
                }
            }
            dashboardMap.invalidate(); commandMap.invalidate()
        }
    }

    private fun updateAdminRoadRoute(empId: Int, userPoint: GeoPoint) {
        val last = lastRouteUpdate[empId] ?: 0L
        if (System.currentTimeMillis() - last < 30000L) return
        
        adminRoadRouteJobs[empId]?.cancel()
        adminRoadRouteJobs[empId] = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = viewModel.companySettings.value ?: return@launch
                val coords = "${userPoint.longitude},${userPoint.latitude};${settings.officeLongitude},${settings.officeLatitude}"
                val response = osrmApi.getRoute(coords)
                if (response.isSuccessful) {
                    response.body()?.routes?.firstOrNull()?.geometry?.let { encoded ->
                        val decoded = PolylineDecoder.decode(encoded)
                        withContext(Dispatchers.Main) { 
                            _binding?.let {
                                drawAdminRoute(empId, decoded)
                                lastRouteUpdate[empId] = System.currentTimeMillis() 
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun drawAdminRoute(empId: Int, points: List<GeoPoint>) {
        val map1 = binding.adminMapView
        val map2 = binding.commandCenterMapView
        
        val c1 = roadCasings.getOrPut(empId) { Polyline(map1).apply { outlinePaint.color = Color.WHITE; outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.alpha = 150; map1.overlays.add(0, this) } }
        val l1 = roadLines.getOrPut(empId) { Polyline(map1).apply { outlinePaint.color = "#4F46E5".toColorInt(); outlinePaint.strokeWidth = 8f; outlinePaint.strokeCap = Paint.Cap.ROUND; map1.overlays.add(1, this) } }
        c1.setPoints(points); l1.setPoints(points)
        
        val c2 = roadCasings2.getOrPut(empId) { Polyline(map2).apply { outlinePaint.color = Color.WHITE; outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.alpha = 150; map2.overlays.add(0, this) } }
        val l2 = roadLines2.getOrPut(empId) { Polyline(map2).apply { outlinePaint.color = "#4F46E5".toColorInt(); outlinePaint.strokeWidth = 8f; outlinePaint.strokeCap = Paint.Cap.ROUND; map2.overlays.add(1, this) } }
        c2.setPoints(points); l2.setPoints(points)
        
        map1.invalidate(); map2.invalidate()
    }

    private fun setupRealTimeSync() {
        signalR.start()
        lifecycleScope.launch {
            signalR.dataChangeEvents
                .collectLatest { event ->
                    // Stable Flow API: coalesce a burst of realtime events without preview debounce API.
                    delay(500L)
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
                            is SignalRManager.SyncEvent.LocationChanged -> { }
                            is SignalRManager.SyncEvent.SessionStarted -> {
                                Log.d("MainActivity", "New session detected: ${event.employeeId}. Pulling fresh data. 🛰️")
                                triggerExclusiveRefresh()
                            }
                            is SignalRManager.SyncEvent.ApplicationDataChanged,
                            is SignalRManager.SyncEvent.RegularizationChanged,
                            is SignalRManager.SyncEvent.LeaveChanged,
                            is SignalRManager.SyncEvent.AdvanceChanged,
                            is SignalRManager.SyncEvent.BonusChanged,
                            is SignalRManager.SyncEvent.TaxDeclarationChanged,
                            is SignalRManager.SyncEvent.EmployeeChanged,
                            is SignalRManager.SyncEvent.ExitChanged,
                            is SignalRManager.SyncEvent.AttendanceChanged,
                            is SignalRManager.SyncEvent.PunchChanged,
                            is SignalRManager.SyncEvent.DataChanged -> {
                                Log.d("MainActivity", "Real-time refresh triggered by: $event 🔄")
                                triggerExclusiveRefresh()
                            }
                            else -> triggerExclusiveRefresh()
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
                viewModel.startNeonSync()
            }
        }
    }

    private fun getLocStatus(loc: SignalRManager.LiveLocation): String {
        // Ported from Web: LiveLocationStore.cs
        // Requirement: Status remains "Live" for 10 years to prevent dashboard flicker on backgrounding
        val timestamp = loc.timestamp ?: return "Live" 
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val serverTime = sdf.parse(timestamp)?.time ?: 0L
            val ageMs = Math.abs(System.currentTimeMillis() - serverTime)
            
            // Web: LiveTimeoutSeconds = 315360000 (10 years)
            val liveTimeoutMs = 10L * 365 * 24 * 60 * 60 * 1000
            when { 
                ageMs <= liveTimeoutMs -> "Live" 
                else -> "Offline" 
            }
        } catch (_: Exception) { "Live" }
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

    private fun animateMarker(m1: Marker, m2: Marker, toPosition: GeoPoint, empId: Int) {
        // Cancel existing animation for this employee to prevent main thread pinning
        markerAnimations[empId]?.cancel()
        
        val startPosition = m1.position
        if (startPosition.latitude == 0.0) { 
            m1.position = toPosition
            m2.position = toPosition
            return 
        }
        
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1200L // 1.2s ultra-smooth glide
        animator.interpolator = AccelerateDecelerateInterpolator()
        
        animator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float
            
            val lat = t * toPosition.latitude + (1 - t) * startPosition.latitude
            val lng = t * toPosition.longitude + (1 - t) * startPosition.longitude
            
            val point = GeoPoint(lat, lng)
            m1.position = point
            m2.position = point
            
            syncRoadLineWithMarker(empId, point)
            
            binding.adminMapView.invalidate()
            binding.commandCenterMapView.invalidate()
        }
        
        markerAnimations[empId] = animator
        animator.start()
    }

    private fun syncRoadLineWithMarker(empId: Int, point: GeoPoint) {
        roadLines[empId]?.let { line ->
            val pts = line.actualPoints.toMutableList()
            if (pts.size >= 2) { pts[0] = point; line.setPoints(pts) }
        }
        roadCasings[empId]?.let { casing ->
            val pts = casing.actualPoints.toMutableList()
            if (pts.size >= 2) { pts[0] = point; casing.setPoints(pts) }
        }
        roadLines2[empId]?.let { line ->
            val pts = line.actualPoints.toMutableList()
            if (pts.size >= 2) { pts[0] = point; line.setPoints(pts) }
        }
        roadCasings2[empId]?.let { casing ->
            val pts = casing.actualPoints.toMutableList()
            if (pts.size >= 2) { pts[0] = point; casing.setPoints(pts) }
        }
    }

    private fun applyCurrentThemeToMap(mapView: MapView) {
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(floatArrayOf(0.25f, 0f, 0f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0.25f, 0f, 30f, 0f, 0f, 0f, 1f, 0f)))
        }
    }

    override fun onResume() { 
        super.onResume()
        _binding?.adminMapView?.onResume()
        _binding?.commandCenterMapView?.onResume()
        checkBatteryOptimizations() 
        
        lifecycleScope.launch {
            // Anti-Inactivity: Stagger to avoid UI jank on resume
            delay(400)
            _binding?.let {
                triggerExclusiveRefresh()
                signalR.start()
            }
        }
    }
    override fun onPause() { 
        super.onPause() 
        _binding?.adminMapView?.onPause()
        _binding?.commandCenterMapView?.onPause() 
    }
    override fun onDestroy() { 
        adminRoadRouteJobs.values.forEach { it.cancel() }
        adminRoadRouteJobs.clear()
        markerAnimations.values.forEach { it.cancel() }
        markerAnimations.clear()
        refreshJob?.cancel()
        iconCache.clear()
        
        _binding?.adminMapView?.onDetach()
        _binding?.commandCenterMapView?.onDetach()
        
        super.onDestroy()
        _binding = null 
    }

    private fun setupRecyclerViews() {
        adapter = ShopAdapter(
            onShopClick = { shop -> 
                sharedViewModel.setSelectedShop(shop)
                startActivity(Intent(this, StaffActivity::class.java)) 
            }, 
            onDeleteClick = { shop -> 
                MaterialAlertDialogBuilder(this)
                    .setTitle("🗑️ Delete Shop")
                    .setMessage("Are you sure you want to delete ${shop.name}?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteShop(shop) }
                    .setNegativeButton("Cancel", null)
                    .show() 
            }
        )
        binding.rvShops.layoutManager = LinearLayoutManager(this)
        binding.rvShops.adapter = adapter

        workforceAdapter = StaffSummaryAdapter(emptyList())
        binding.rvWorkforceList.layoutManager = LinearLayoutManager(this)
        binding.rvWorkforceList.adapter = workforceAdapter

        approvalsAdapter = ApprovalsAdapter(emptyList())
        binding.rvPendingApprovals.layoutManager = LinearLayoutManager(this)
        binding.rvPendingApprovals.adapter = approvalsAdapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { viewModel.triggerRefresh() }
        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.colorPrimary), ContextCompat.getColor(this, R.color.green_700))
    }

    private fun setupListeners() {
        binding.btnAddShop.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            showAddShopDialog() 
        }
        binding.cvLiveMapCard.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            showHub("TRACKING"); binding.bottomNavigation.selectedItemId = R.id.nav_tracking 
        }
        binding.cvManualPunchCorrection.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminManualPunchCorrectionActivity::class.java))
        }
        binding.btnApproveRegs.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, RegularizationActivity::class.java)) 
        }
        binding.btnRunPayroll.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AdminPayrollActivity::class.java)) 
        }
        binding.btnLeaveManagement.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, LeaveManagementActivity::class.java)) 
        }
        binding.btnAuditTrail.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, AuditTrailActivity::class.java)) 
        }
        binding.btnRecycleBin.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, RecycleBinActivity::class.java)) 
        }
        binding.btnReportCenter.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, ReportCenterActivity::class.java)) 
        }
        binding.btnAddEmployee.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, StaffActivity::class.java)) 
        }
        binding.btnViewAllAdvances.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            showHub("REPORTS"); binding.bottomNavigation.selectedItemId = R.id.nav_reports 
        }
        binding.btnUserManagement.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, UserManagementActivity::class.java)) 
        }
        binding.btnOpenFullReportCenter.setOnClickListener { 
            HapticUtil.vibrateClick(it)
            startActivity(Intent(this, ReportCenterActivity::class.java)) 
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
                    viewModel.shopsWorkforceState.collectLatest { 
                        _binding?.let { b ->
                            adapter.submitList(it) 
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
                
                // Ported: Workforce Hub Data 1:1 with Web
                launch {
                    combine(sharedViewModel.allEmployees, workforceSearchQuery) { employees, query ->
                        if (query.isBlank()) employees
                        else employees.filter { it.name.contains(query, true) || it.role.contains(query, true) }
                    }.flowOn(Dispatchers.Default).collectLatest { employees ->
                        _binding?.let { b ->
                            val active = employees.count { it.isActive }
                            b.tvWorkforceSummary.text = "Currently managing $active staff members."
                            workforceAdapter.updateData(employees)
                        }
                    }
                }

                // Ported: Approvals Hub Data
                launch {
                    combine(
                        sharedViewModel.allRegularizations,
                        sharedViewModel.allLeaveRequests,
                        approvalFilter
                    ) { regs: List<RegularizationRequest>, leaves: List<LeaveRequest>, filter: String ->
                        val pendingRegs = if (filter == "All" || filter == "Regularizations") regs.filter { it.status == "Pending" } else emptyList()
                        val pendingLeaves = if (filter == "All" || filter == "Leaves") leaves.filter { it.status == "Pending" } else emptyList()
                        pendingRegs + pendingLeaves
                    }.flowOn(Dispatchers.Default).collectLatest { pendingItems ->
                        _binding?.let { b ->
                            b.tvNoPendingApprovals.isVisible = pendingItems.isEmpty()
                            approvalsAdapter.updateData(pendingItems)
                        }
                    }
                }

                // Ported: Analytics Hub Data (Reports)
                launch {
                    viewModel.globalStats.collectLatest { stats ->
                        _binding?.let { b ->
                            val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
                            b.tvPayrollProjection.text = currency.format(stats.currentPayrollCost)
                        }
                    }
                }
            }
        }
    }

    inner class StaffSummaryAdapter(private var list: List<Employee>) : RecyclerView.Adapter<StaffSummaryAdapter.ViewHolder>() {
        fun updateData(newList: List<Employee>) {
            list = newList
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false))
        @Suppress("UNCHECKED_CAST")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val emp = list[position]
            holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle).text = "👤 ${emp.name}"
            holder.itemView.findViewById<TextView>(R.id.tvHistoryDate).text = "👔 Role: ${emp.role}"
            holder.itemView.findViewById<TextView>(R.id.tvHistoryReason).text = if (emp.isActive) "🟢 Active" else "🔴 Terminated"
            holder.itemView.findViewById<TextView>(R.id.tvHistoryReason).setTextColor(if (emp.isActive) Color.parseColor("#198754") else Color.GRAY)
            holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon).text = "👥"
            holder.itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, StaffActivity::class.java)
                intent.putExtra("employee_id", emp.employeeId)
                startActivity(intent)
            }
        }
        override fun getItemCount() = list.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    inner class ApprovalsAdapter(private var list: List<Any>) : RecyclerView.Adapter<ApprovalsAdapter.ViewHolder>() {
        fun updateData(newList: List<Any>) {
            list = newList
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false))
        @Suppress("UNCHECKED_CAST")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)

            when (item) {
                is RegularizationRequest -> {
                    tvTitle.text = "👤 ${item.staffName} - Correction"
                    tvDate.text = "🗓️ ${item.date}"
                    tvReason.text = "📝 ${item.reason}"
                    tvIcon.text = "🛠️"
                    holder.itemView.setOnClickListener { startActivity(Intent(this@MainActivity, RegularizationActivity::class.java)) }
                }
                is LeaveRequest -> {
                    tvTitle.text = "👤 ${item.staffName} - ${item.leaveType}"
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    tvDate.text = "🗓️ ${sdf.format(Date(item.startDate))}"
                    tvReason.text = "📝 ${item.reason}"
                    tvIcon.text = "🌴"
                    holder.itemView.setOnClickListener { 
                         Toast.makeText(this@MainActivity, "✨ Redirecting to Leave Management 🌴", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        override fun getItemCount() = list.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    private fun applyRolePermissions() {
        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE).getString("user_role", UserRole.STAFF.name)
        val isSuperAdmin = role == UserRole.SUPER_ADMIN.name

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
                    b.btnRecycleBin.isVisible = isSuperAdmin && s.enableRecycleBin
                    b.btnAuditTrail.isVisible = isSuperAdmin || s.enableAuditLog

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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) true else super.onOptionsItemSelected(item)
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

    private fun radiusFromPoints(points: List<GeoPoint>, center: GeoPoint?): Int {
        if (center == null || points.isEmpty()) return 100
        val results = FloatArray(1)
        Location.distanceBetween(center.latitude, center.longitude, points[0].latitude, points[0].longitude, results)
        return results[0].toInt()
    }

    private fun logout() {
        FirebaseAuth.getInstance().signOut()
        applicationContext.getSharedPreferences("auth_prefs", MODE_PRIVATE).edit { clear() }
        startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }
}
