package com.biometric.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.LocalLocation
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalAttendancePunch
import com.biometric.app.data.entity.OfflineTrackingEvent
import com.biometric.app.domain.location.GeofenceAutoPunchCoordinator
import com.biometric.app.domain.location.OfflineSyncWorker
import com.biometric.app.domain.location.OfflineTrackingMonitor
import com.biometric.app.sync.SignalRManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class OfflineTrackingActivity : MotionBaseActivity() {
    @Inject lateinit var repo: MainRepository
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var eventDao: OfflineTrackingEventDao
    @Inject lateinit var punchDao: LocalAttendancePunchDao
    @Inject lateinit var monitor: OfflineTrackingMonitor
    @Inject lateinit var signalR: SignalRManager

    // Filter Controls
    private lateinit var spnFilterEmployee: Spinner
    private lateinit var chipGroupDateFilter: ChipGroup
    private lateinit var btnRefreshOffline: MaterialButton
    private lateinit var btnSyncNow: MaterialButton

    // 4 KPI Summary Cards
    private lateinit var tvOfflineSessionsCount: TextView
    private lateinit var tvOfflineSessionsSub: TextView
    private lateinit var tvTotalOfflineDuration: TextView
    private lateinit var tvOfflineDurationSub: TextView
    private lateinit var tvGeofenceSurvey: TextView
    private lateinit var tvGeofenceSurveySub: TextView
    private lateinit var tvLiveImpactCount: TextView
    private lateinit var tvLiveImpactSub: TextView

    // Map Section
    private lateinit var mapView: MapView
    private lateinit var tvMapRouteBanner: TextView
    private lateinit var btnResetMapFilter: MaterialButton
    private lateinit var tvMapEmptyNotice: TextView

    // Offline Disconnection Sessions List
    private lateinit var tvPeriodCountBadge: TextView
    private lateinit var rvOfflinePeriods: RecyclerView
    private lateinit var tvNoOfflinePeriods: TextView

    private lateinit var periodAdapter: OfflinePeriodAdapter

    private var activeEmployees: List<Employee> = emptyList()
    private var allAttendanceList: List<Attendance> = emptyList()
    private var allPunchesList: List<LocalAttendancePunch> = emptyList()
    private var officeLat: Double = 0.0
    private var officeLng: Double = 0.0
    private var officeRadiusMeters: Int = 100

    private var selectedEmployeeId: Int = 0 // 0 = All
    private var selectedPeriod: OfflinePeriodItem? = null
    private var dateFilter: String = "TODAY" // TODAY, YESTERDAY, 7DAYS, MONTH

    private var refreshJob: Job? = null
    private var cloudLoadJob: Job? = null
    private val cloudHistoryCache = mutableMapOf<Int, Pair<Long, List<SignalRManager.LiveLocation>>>()

    private suspend fun getCachedOrFetchHistory(empId: Int, limit: Int = 300): List<SignalRManager.LiveLocation> {
        val now = System.currentTimeMillis()
        val cached = cloudHistoryCache[empId]
        if (cached != null && (now - cached.first) < 60_000L) {
            return cached.second
        }
        val fetched = signalR.loadTrackingHistory(empId, limit)
        if (fetched.isNotEmpty()) {
            cloudHistoryCache[empId] = now to fetched
        }
        return fetched
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_tracking)
        applyWindowInsets(findViewById(R.id.clOfflineTrackingRoot), findViewById(R.id.appBar))
        monitor.start()

        initViews()
        setupMap()
        setupAdapters()
        setupListeners()
        observeData()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Filter Controls
        spnFilterEmployee = findViewById(R.id.spnFilterEmployee)
        chipGroupDateFilter = findViewById(R.id.chipGroupDateFilter)
        btnRefreshOffline = findViewById(R.id.btnRefreshOffline)
        btnSyncNow = findViewById(R.id.btnSyncNow)

        // 4 KPI Summary Cards
        tvOfflineSessionsCount = findViewById(R.id.tvOfflineSessionsCount)
        tvOfflineSessionsSub = findViewById(R.id.tvOfflineSessionsSub)
        tvTotalOfflineDuration = findViewById(R.id.tvTotalOfflineDuration)
        tvOfflineDurationSub = findViewById(R.id.tvOfflineDurationSub)
        tvGeofenceSurvey = findViewById(R.id.tvGeofenceSurvey)
        tvGeofenceSurveySub = findViewById(R.id.tvGeofenceSurveySub)
        tvLiveImpactCount = findViewById(R.id.tvLiveImpactCount)
        tvLiveImpactSub = findViewById(R.id.tvLiveImpactSub)

        // Map Section
        mapView = findViewById(R.id.offlineMapView)
        tvMapRouteBanner = findViewById(R.id.tvMapRouteBanner)
        btnResetMapFilter = findViewById(R.id.btnResetMapFilter)
        tvMapEmptyNotice = findViewById(R.id.tvMapEmptyNotice)

        // Sessions List
        tvPeriodCountBadge = findViewById(R.id.tvPeriodCountBadge)
        rvOfflinePeriods = findViewById(R.id.rvOfflinePeriods)
        tvNoOfflinePeriods = findViewById(R.id.tvNoOfflinePeriods)
    }

    private fun setupMap() {
        OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200 * 1024 * 1024L
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)

        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == Configuration.UI_MODE_NIGHT_YES) {
            mapView.overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(
                    floatArrayOf(
                        0.25f, 0f, 0f, 0f, 0f,
                        0f, 0.25f, 0f, 0f, 0f,
                        0f, 0f, 0.25f, 0f, 30f,
                        0f, 0f, 0.2f, 1f, 0f
                    )
                )
            )
        }
    }

    private fun setupAdapters() {
        periodAdapter = OfflinePeriodAdapter { period ->
            selectedPeriod = period
            btnResetMapFilter.visibility = View.VISIBLE
            val periodEmp = if (period.employeeName.isNotBlank()) "${period.employeeName} • " else ""
            tvMapRouteBanner.text = "Showing offline route: $periodEmp${formatTimeOnly(period.startTime)} → ${formatTimeOnly(period.endTime)} (${period.pointsCount} points)"
            drawOfflinePeriodRoute(period)
            mapView.parent?.requestChildFocus(mapView, mapView)
        }

        rvOfflinePeriods.apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = periodAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        // Date Filter Chips
        chipGroupDateFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            dateFilter = when (checkedIds.firstOrNull()) {
                R.id.chipDateYesterday -> "YESTERDAY"
                R.id.chipDate7Days -> "7DAYS"
                R.id.chipDateMonth -> "MONTH"
                else -> "TODAY"
            }
            selectedPeriod = null
            btnResetMapFilter.visibility = View.GONE
            refreshCloudAndLocal()
        }

        // Employee Dropdown Spinner
        spnFilterEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newId = if (position == 0) {
                    0
                } else {
                    activeEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull() ?: 0
                }
                if (newId != selectedEmployeeId) {
                    selectedEmployeeId = newId
                    selectedPeriod = null
                    btnResetMapFilter.visibility = View.GONE
                    refreshCloudAndLocal()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Reset Map View Button
        btnResetMapFilter.setOnClickListener {
            selectedPeriod = null
            btnResetMapFilter.visibility = View.GONE
            refreshCloudAndLocal()
        }

        // Refresh Button
        btnRefreshOffline.setOnClickListener {
            cloudHistoryCache.clear()
            signalR.reconcileLiveLocationsNow()
            refreshCloudAndLocal()
            Toast.makeText(this, "Refreshed offline tracking data", Toast.LENGTH_SHORT).show()
        }

        // Sync Pending GPS Buffer Button
        btnSyncNow.setOnClickListener {
            OfflineSyncWorker.schedule(this)
            Toast.makeText(this, "Offline GPS synchronization scheduled", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                delay(1200L)
                refreshCloudAndLocal()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(15000L)
                refreshCloudAndLocal()
            }
        }
        refreshCloudAndLocal()
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repo.allEmployeesFlow.collect { list ->
                activeEmployees = list.filter { it.isActive }
                updateEmployeeSpinner()
                refreshCloudAndLocal()
            }
        }

        lifecycleScope.launch {
            repo.allAttendanceFlow.collect { attList ->
                allAttendanceList = attList
                refreshCloudAndLocal()
            }
        }

        lifecycleScope.launch {
            punchDao.getAllFlow().collect { punches ->
                allPunchesList = punches
                refreshCloudAndLocal()
            }
        }
    }

    private fun updateEmployeeSpinner() {
        val items = mutableListOf("All Tracked Employees (${activeEmployees.size})")
        items.addAll(activeEmployees.map { "${it.name} (#${it.employeeId})" })
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spnFilterEmployee.adapter = adapter

        if (selectedEmployeeId > 0) {
            val idx = activeEmployees.indexOfFirst { it.employeeId.toIntOrNull() == selectedEmployeeId }
            if (idx >= 0) {
                spnFilterEmployee.setSelection(idx + 1)
            }
        }
    }

    private fun getDateRangeEpoch(): Pair<Long, Long> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        return when (dateFilter) {
            "YESTERDAY" -> {
                val yStart = todayStart - 24 * 3600 * 1000L
                val yEnd = todayStart - 1L
                yStart to yEnd
            }
            "7DAYS" -> {
                val weekStart = todayStart - 6 * 24 * 3600 * 1000L
                val now = System.currentTimeMillis()
                weekStart to now
            }
            "MONTH" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val monthStart = cal.timeInMillis
                val now = System.currentTimeMillis()
                monthStart to now
            }
            else -> { // TODAY
                val now = System.currentTimeMillis()
                todayStart to now
            }
        }
    }

    private fun refreshCloudAndLocal() {
        cloudLoadJob?.cancel()
        cloudLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val (fromEpoch, toEpoch) = getDateRangeEpoch()

            // Office Coordinates from Room/Settings
            val officeEmp = activeEmployees.firstOrNull()
            officeLat = 11.9308
            officeLng = 79.7849
            officeRadiusMeters = 800

            val localRecent = locationDao.getRecent(500)
                .filter { it.timestamp in fromEpoch..toEpoch }
            val localEvents = eventDao.recent(150)
                .filter { it.eventTime in fromEpoch..toEpoch }

            val empNameMap = activeEmployees.associate { (it.employeeId.toIntOrNull() ?: 0) to it.name }
            val allLocalPunches = try {
                punchDao.getAll().filter { it.timestamp in fromEpoch..toEpoch }
            } catch (_: Exception) {
                emptyList<LocalAttendancePunch>()
            }

            val allPeriods = mutableListOf<OfflinePeriodItem>()

            if (selectedEmployeeId > 0) {
                val empId = selectedEmployeeId
                val empName = empNameMap[empId] ?: "Employee #$empId"
                val hist = getCachedOrFetchHistory(empId, 300)
                    .filter {
                        val epoch = parseTrackingTimestamp(it.timestamp)
                        epoch in fromEpoch..toEpoch
                    }

                val convertedHist = hist.map { h ->
                    val epoch = parseTrackingTimestamp(h.timestamp)
                    LocalLocation(
                        id = 0,
                        sessionId = h.sessionId,
                        clientEventId = UUID.randomUUID().toString(),
                        sequence = 0L,
                        latitude = h.latitude,
                        longitude = h.longitude,
                        accuracy = h.accuracyMeters.toFloat(),
                        speed = h.speedMps.toFloat(),
                        bearing = h.bearing.toFloat(),
                        batteryLevel = 100,
                        timestamp = epoch,
                        capturedElapsedRealtime = 0L,
                        syncState = LocalLocation.SYNCED,
                        attemptCount = 1,
                        lastAttemptAt = epoch,
                        syncedAt = epoch,
                        lastError = null,
                        isOfflineCapture = h.isOffline || h.movementState.contains("offline", ignoreCase = true)
                    )
                }

                val combinedPoints = if (empId == sessionStore.employeeId()) {
                    (convertedHist + localRecent).distinctBy { "${it.latitude}_${it.longitude}_${it.timestamp}" }
                } else {
                    convertedHist
                }

                val empProfile = activeEmployees.firstOrNull { it.employeeId == empId.toString() }
                val empPeriods = buildOfflinePeriods(combinedPoints, localEvents, empId, empName, allLocalPunches, empProfile)
                allPeriods.addAll(empPeriods)
            } else {
                // All Employees
                for (emp in activeEmployees) {
                    val eid = emp.employeeId.toIntOrNull() ?: continue
                    val hist = getCachedOrFetchHistory(eid, 200).filter {
                        val epoch = parseTrackingTimestamp(it.timestamp)
                        epoch in fromEpoch..toEpoch
                    }
                    val converted = hist.map { h ->
                        val epoch = parseTrackingTimestamp(h.timestamp)
                        LocalLocation(
                            id = 0,
                            sessionId = h.sessionId,
                            clientEventId = UUID.randomUUID().toString(),
                            sequence = 0L,
                            latitude = h.latitude,
                            longitude = h.longitude,
                            accuracy = h.accuracyMeters.toFloat(),
                            speed = h.speedMps.toFloat(),
                            bearing = h.bearing.toFloat(),
                            batteryLevel = 100,
                            timestamp = epoch,
                            capturedElapsedRealtime = 0L,
                            syncState = LocalLocation.SYNCED,
                            attemptCount = 1,
                            lastAttemptAt = epoch,
                            syncedAt = epoch,
                            lastError = null,
                            isOfflineCapture = h.isOffline || h.movementState.contains("offline", ignoreCase = true)
                        )
                    }

                    val pointsForEmp = if (eid == sessionStore.employeeId()) {
                        (converted + localRecent).distinctBy { "${it.latitude}_${it.longitude}_${it.timestamp}" }
                    } else {
                        converted
                    }

                    if (pointsForEmp.isNotEmpty()) {
                        val pList = buildOfflinePeriods(pointsForEmp, localEvents, eid, emp.name, allLocalPunches, emp)
                        allPeriods.addAll(pList)
                    }
                }

                if (allPeriods.isEmpty() && localRecent.isNotEmpty()) {
                    val myProfile = activeEmployees.firstOrNull { it.employeeId == sessionStore.employeeId().toString() }
                    val localPeriods = buildOfflinePeriods(localRecent, localEvents, sessionStore.employeeId(), empNameMap[sessionStore.employeeId()] ?: "This Device", allLocalPunches, myProfile)
                    allPeriods.addAll(localPeriods)
                }
            }

            val sortedPeriods = allPeriods.distinctBy { it.id }.sortedByDescending { it.startTime }

            // KPI Calculations
            val totalOfflineMs = sortedPeriods.sumOf { it.durationMs }
            var totalInsideMs = 0L
            for (p in sortedPeriods) {
                if (p.pointsCount > 0) {
                    val ratio = p.inRadiusCount.toDouble() / p.pointsCount
                    totalInsideMs += (p.durationMs * ratio).toLong()
                } else {
                    totalInsideMs += p.durationMs
                }
            }
            val totalOutsideMs = (totalOfflineMs - totalInsideMs).coerceAtLeast(0L)
            val insidePercent = if (totalOfflineMs > 0) ((totalInsideMs * 100) / totalOfflineMs).toInt() else 100
            val outsidePercent = (100 - insidePercent).coerceAtLeast(0)

            val autoOutCount = sortedPeriods.count { it.punches.any { punch -> punch.type.equals("OUT", true) } || it.liveImpactSummary.contains("Auto OUT", true) }
            val impactCount = sortedPeriods.count { it.punches.isNotEmpty() || it.liveImpactSummary.contains("Auto OUT", true) || it.liveImpactSummary.contains("Transition", true) }

            withContext(Dispatchers.Main) {
                // Update 4 KPI Cards
                tvOfflineSessionsCount.text = sortedPeriods.size.toString()
                tvOfflineSessionsSub.text = if (selectedEmployeeId > 0) "For selected employee" else "${activeEmployees.size} employees audited"

                tvTotalOfflineDuration.text = formatDuration(totalOfflineMs)
                tvOfflineDurationSub.text = "Cumulative disconnected"

                tvGeofenceSurvey.text = "${formatDuration(totalInsideMs)} / ${formatDuration(totalOutsideMs)}"
                tvGeofenceSurveySub.text = "🟢 In: $insidePercent% • 🔴 Out: $outsidePercent%"

                tvLiveImpactCount.text = impactCount.toString()
                tvLiveImpactSub.text = if (autoOutCount > 0) "$autoOutCount shift(s) closed automatically" else "Reconciled with live attendance"

                // Update List & Badge
                tvPeriodCountBadge.text = "${sortedPeriods.size} period(s)"
                periodAdapter.submit(sortedPeriods)
                tvNoOfflinePeriods.visibility = if (sortedPeriods.isEmpty()) View.VISIBLE else View.GONE

                // Update Map if not inspecting a single period
                if (selectedPeriod == null) {
                    drawOverviewMap(sortedPeriods)
                }
            }
        }
    }

    private fun drawOverviewMap(periods: List<OfflinePeriodItem>) {
        mapView.overlays.clear()
        val allGeoPoints = mutableListOf<GeoPoint>()

        // 1. Office Location Marker
        if (officeLat != 0.0 && officeLng != 0.0) {
            val officeGeo = GeoPoint(officeLat, officeLng)
            Marker(mapView).apply {
                position = officeGeo
                title = "🏢 Office Location"
                snippet = "Geofence Radius: ${officeRadiusMeters}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
            allGeoPoints.add(officeGeo)
        }

        // 2. Offline Routes
        for (period in periods) {
            val valid = period.points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            if (valid.isNotEmpty()) {
                val geoPoints = valid.map { GeoPoint(it.latitude, it.longitude) }
                val offlinePoly = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = Color.parseColor("#E65100")
                    outlinePaint.strokeWidth = 9f
                }
                mapView.overlays.add(offlinePoly)
                allGeoPoints.addAll(geoPoints)

                // Stays
                for (s in period.stays) {
                    val sGeo = GeoPoint(s.latitude, s.longitude)
                    Marker(mapView).apply {
                        position = sGeo
                        title = "📍 Stayed ${(s.durationMs / 60000).coerceAtLeast(1)}m"
                        snippet = s.locationDescription
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(this)
                    }
                }
            }
        }

        if (allGeoPoints.isNotEmpty()) {
            tvMapEmptyNotice.visibility = View.GONE
            val empTitle = if (selectedEmployeeId > 0) {
                activeEmployees.firstOrNull { it.employeeId.toIntOrNull() == selectedEmployeeId }?.name ?: "Employee"
            } else "All Employees"
            tvMapRouteBanner.text = "Showing offline routes for $empTitle (${periods.size} sessions plotted)"
            zoomToFit(allGeoPoints)
        } else {
            tvMapEmptyNotice.visibility = View.VISIBLE
            tvMapEmptyNotice.text = "No offline tracking points recorded for the selected filter."
            tvMapRouteBanner.text = "Tap an offline period below to inspect journey route"
            if (officeLat != 0.0) {
                mapView.controller.setCenter(GeoPoint(officeLat, officeLng))
                mapView.controller.setZoom(15.0)
            }
            mapView.invalidate()
        }
    }

    private fun createPinBadgeDrawable(text: String, badgeColor: Int): Drawable {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textWidth = paint.measureText(text)
        val padH = 22f
        val w = (textWidth + padH * 2f).coerceAtLeast(80f)
        val h = 46f
        val totalH = h + 12f

        val bitmap = Bitmap.createBitmap(w.toInt(), totalH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Drop shadow
        p.color = Color.parseColor("#35000000")
        canvas.drawRoundRect(RectF(2f, 4f, w - 2f, h + 2f), h / 2f, h / 2f, p)

        // Badge body
        p.color = badgeColor
        p.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), h / 2f, h / 2f, p)

        // White border
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        canvas.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), h / 2f, h / 2f, p)

        // Pointer triangle at bottom center
        p.style = Paint.Style.FILL
        p.color = badgeColor
        val path = Path().apply {
            moveTo(w / 2f - 9f, h - 3f)
            lineTo(w / 2f + 9f, h - 3f)
            lineTo(w / 2f, totalH - 1f)
            close()
        }
        canvas.drawPath(path, p)

        // Text
        val fm = paint.fontMetrics
        val textY = (h / 2f) - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, w / 2f, textY, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun createBreadcrumbDotDrawable(): Drawable {
        val size = 26
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Shadow
        p.color = Color.parseColor("#35000000")
        canvas.drawCircle(size / 2f, size / 2f + 1f, 11f, p)
        // White border
        p.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, 10f, p)
        // Orange center
        p.color = Color.parseColor("#EA580C")
        canvas.drawCircle(size / 2f, size / 2f, 6.5f, p)
        return BitmapDrawable(resources, bitmap)
    }

    private fun drawOfflinePeriodRoute(period: OfflinePeriodItem) {
        mapView.overlays.clear()
        val routeGeo = mutableListOf<GeoPoint>()

        // 1. Office Location Marker & Circle (for geographical reference, not added to route zoom)
        if (officeLat != 0.0 && officeLng != 0.0) {
            val officeGeo = GeoPoint(officeLat, officeLng)
            Marker(mapView).apply {
                position = officeGeo
                title = "🏢 Office Location"
                snippet = "Geofence Radius: ${officeRadiusMeters}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
            try {
                val circlePoints = Polygon.pointsAsCircle(officeGeo, officeRadiusMeters.toDouble())
                val circle = Polygon().apply {
                    points = circlePoints
                    fillPaint.color = Color.parseColor("#202563EB")
                    outlinePaint.color = Color.parseColor("#602563EB")
                    outlinePaint.strokeWidth = 3f
                }
                mapView.overlays.add(circle)
            } catch (_: Exception) {}
        }

        val valid = period.points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (valid.isNotEmpty()) {
            val geoPoints = valid.map { GeoPoint(it.latitude, it.longitude) }
            val polyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#EA580C")
                outlinePaint.strokeWidth = 12f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            mapView.overlays.add(polyline)
            routeGeo.addAll(geoPoints)

            // Intermediate breadcrumb dots along the route
            if (valid.size > 2) {
                for (i in 1 until valid.size - 1) {
                    val pt = valid[i]
                    val ptGeo = GeoPoint(pt.latitude, pt.longitude)
                    Marker(mapView).apply {
                        position = ptGeo
                        title = "📍 Breadcrumb #${i + 1} (${formatTimeOnly(pt.timestamp)})"
                        snippet = "Speed: ${String.format(Locale.US, "%.1f", pt.speed * 3.6f)} km/h"
                        icon = createBreadcrumbDotDrawable()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        mapView.overlays.add(this)
                    }
                }
            }

            // Start Disconnect Marker (Distinct Red Badge)
            val startLoc = valid.first()
            val startGeo = GeoPoint(startLoc.latitude, startLoc.longitude)
            Marker(mapView).apply {
                position = startGeo
                title = "📴 Disconnected: ${formatTimeOnly(period.startTime)}"
                snippet = "Offline Route Start • Cause: ${period.reason}"
                icon = createPinBadgeDrawable("📴 Disconnected: ${formatTimeOnly(period.startTime)}", Color.parseColor("#DC2626"))
                setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                mapView.overlays.add(this)
            }

            // Dwell Stay Markers (Distinct Blue Badge)
            for (stay in period.stays) {
                val stayGeo = GeoPoint(stay.latitude, stay.longitude)
                routeGeo.add(stayGeo)
                Marker(mapView).apply {
                    position = stayGeo
                    val durMins = (stay.durationMs / 60000).coerceAtLeast(1)
                    title = "📍 Stayed ${durMins}m"
                    snippet = "${stay.locationDescription} (${formatTimeOnly(stay.startTime)} - ${formatTimeOnly(stay.endTime)})"
                    icon = createPinBadgeDrawable("📍 Stayed ${durMins}m", Color.parseColor("#0284C7"))
                    setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                    mapView.overlays.add(this)
                }
            }

            // Reconnected End Marker (Distinct Green Badge)
            val endLoc = valid.last()
            val endGeo = GeoPoint(endLoc.latitude, endLoc.longitude)
            Marker(mapView).apply {
                position = endGeo
                title = "📶 Reconnected: ${formatTimeOnly(period.endTime)}"
                snippet = "Offline Route End • Synced to Cloud SSOT (${period.pointsCount} points)"
                icon = createPinBadgeDrawable("📶 Reconnected: ${formatTimeOnly(period.endTime)}", Color.parseColor("#16A34A"))
                setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                mapView.overlays.add(this)
            }
        }

        if (routeGeo.isNotEmpty()) {
            tvMapEmptyNotice.visibility = View.GONE
            val periodEmp = if (period.employeeName.isNotBlank()) "${period.employeeName} • " else ""
            val distStr = if (period.distanceMeters >= 1000) String.format(Locale.US, "%.1f km", period.distanceMeters / 1000.0) else "${period.distanceMeters.toInt()}m"
            tvMapRouteBanner.text = "Offline Route: $periodEmp${formatTimeOnly(period.startTime)} → ${formatTimeOnly(period.endTime)} ($distStr • ${period.pointsCount} points)"
            zoomToFit(routeGeo)
        } else {
            tvMapEmptyNotice.visibility = View.VISIBLE
            tvMapEmptyNotice.text = "No GPS coordinates recorded for this offline session."
        }
    }

    private fun zoomToFit(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            mapView.controller.setCenter(points.first())
            mapView.controller.setZoom(16.5)
            mapView.invalidate()
            return
        }

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE

        for (p in points) {
            minLat = min(minLat, p.latitude)
            maxLat = max(maxLat, p.latitude)
            minLng = min(minLng, p.longitude)
            maxLng = max(maxLng, p.longitude)
        }

        val latSpan = maxLat - minLat
        val lngSpan = maxLng - minLng
        val minSpan = 0.003 // ~300 meters minimum span
        val extraLatPad = if (latSpan < minSpan) (minSpan - latSpan) / 2.0 else 0.0012
        val extraLngPad = if (lngSpan < minSpan) (minSpan - lngSpan) / 2.0 else 0.0012

        val box = BoundingBox(
            maxLat + extraLatPad,
            maxLng + extraLngPad,
            minLat - extraLatPad,
            minLng - extraLngPad
        )
        mapView.post {
            try {
                mapView.zoomToBoundingBox(box, true, 80)
            } catch (_: Exception) {
                mapView.controller.setCenter(GeoPoint((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0))
                mapView.controller.setZoom(16.0)
            }
            mapView.invalidate()
        }
    }

    private fun buildOfflinePeriods(
        locations: List<LocalLocation>,
        events: List<OfflineTrackingEvent>,
        employeeId: Int,
        employeeName: String,
        localPunches: List<LocalAttendancePunch> = emptyList(),
        employeeProfile: Employee? = null
    ): List<OfflinePeriodItem> {
        val sorted = locations.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()

        val result = mutableListOf<OfflinePeriodItem>()
        val rawClusters = mutableListOf<MutableList<LocalLocation>>()

        // 1. Contiguous runs of explicit offline points (with gap <= 20 mins between consecutive points)
        var currentOfflineRun = mutableListOf<LocalLocation>()
        for (i in sorted.indices) {
            val pt = sorted[i]
            if (pt.isOfflineCapture) {
                if (currentOfflineRun.isEmpty()) {
                    if (i > 0 && (pt.timestamp - sorted[i - 1].timestamp) <= 30 * 60 * 1000L) {
                        currentOfflineRun.add(sorted[i - 1])
                    }
                    currentOfflineRun.add(pt)
                } else {
                    val prevPt = currentOfflineRun.last()
                    if (pt.timestamp - prevPt.timestamp <= 20 * 60 * 1000L) {
                        currentOfflineRun.add(pt)
                    } else {
                        if (currentOfflineRun.size >= 2) {
                            rawClusters.add(currentOfflineRun)
                        }
                        currentOfflineRun = mutableListOf()
                        if (i > 0 && (pt.timestamp - sorted[i - 1].timestamp) <= 30 * 60 * 1000L) {
                            currentOfflineRun.add(sorted[i - 1])
                        }
                        currentOfflineRun.add(pt)
                    }
                }
            } else {
                if (currentOfflineRun.isNotEmpty()) {
                    currentOfflineRun.add(pt)
                    if (currentOfflineRun.size >= 2) {
                        rawClusters.add(currentOfflineRun)
                    }
                    currentOfflineRun = mutableListOf()
                }
            }
        }
        if (currentOfflineRun.size >= 2) {
            rawClusters.add(currentOfflineRun)
        }

        // 2. Detect gaps between consecutive points (>= 10 mins and <= 48 hours)
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val gap = curr.timestamp - prev.timestamp
            if (gap in (10 * 60 * 1000L)..(48 * 3600 * 1000L)) {
                val gapPoints = sorted.filter { it.timestamp in prev.timestamp..curr.timestamp }
                if (gapPoints.size >= 2) {
                    rawClusters.add(gapPoints.toMutableList())
                } else {
                    rawClusters.add(mutableListOf(prev, curr))
                }
            }
        }

        // 3. Sort & Merge overlapping or adjacent clusters (within 5 minutes)
        val sortedClusters = rawClusters
            .map { it.distinctBy { pt -> "${pt.latitude}_${pt.longitude}_${pt.timestamp}" }.sortedBy { pt -> pt.timestamp }.toMutableList() }
            .filter { it.size >= 2 }
            .sortedBy { it.first().timestamp }

        val clusters = mutableListOf<MutableList<LocalLocation>>()
        for (cand in sortedClusters) {
            if (clusters.isEmpty()) {
                clusters.add(cand)
            } else {
                val lastCluster = clusters.last()
                val lastEnd = lastCluster.last().timestamp
                val candStart = cand.first().timestamp
                if (candStart <= lastEnd + 5 * 60 * 1000L) {
                    val combined = (lastCluster + cand).distinctBy { "${it.latitude}_${it.longitude}_${it.timestamp}" }.sortedBy { it.timestamp }.toMutableList()
                    clusters[clusters.size - 1] = combined
                } else {
                    clusters.add(cand)
                }
            }
        }

        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        val punchFmt = SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = tz }

        val trackingMode = employeeProfile?.trackingMode?.trim()?.uppercase() ?: "24/7"
        val isShiftMode = trackingMode == "SHIFT" || trackingMode == "SHIFT_TIME" || trackingMode == "SHIFT_ONLY"
        val shiftStartStr = employeeProfile?.shiftStart?.trim().orEmpty()
        val shiftEndStr = employeeProfile?.shiftEnd?.trim().orEmpty()

        for (c in clusters) {
            if (c.isEmpty()) continue
            val first = c.first()
            val last = c.last()
            var dist = 0.0
            for (i in 1 until c.size) {
                dist += calculateDistance(
                    c[i - 1].latitude,
                    c[i - 1].longitude,
                    c[i].latitude,
                    c[i].longitude
                )
            }
            val duration = (last.timestamp - first.timestamp).coerceAtLeast(60_000L)

            // Shift evaluation
            var isOffShift = false
            var shiftTag = if (isShiftMode) "Active Shift Disconnection" else "24/7 Active Tracking"
            if (isShiftMode && shiftStartStr.isNotBlank() && shiftEndStr.isNotBlank()) {
                val startHour = timeFmt.format(Date(first.timestamp))
                val inShift = isTimeBetween(startHour, shiftStartStr, shiftEndStr)
                if (!inShift) {
                    isOffShift = true
                    shiftTag = "Off-Duty / Non-Shift Gap"
                }
            }

            val priorPunches = localPunches.filter { p ->
                val pStaffId = p.staffId.toIntOrNull() ?: 0
                (pStaffId == employeeId || employeeId == 0 || p.staffId == employeeId.toString()) &&
                    p.timestamp < first.timestamp
            }.sortedBy { it.timestamp }
            val lastPrior = priorPunches.lastOrNull()
            val priorState = if (lastPrior != null) {
                if (GeofenceAutoPunchCoordinator.isCheckInType(lastPrior.type)) "IN" else "OUT"
            } else "OUT"

            val relatedEvent = events.firstOrNull {
                abs(it.eventTime - first.timestamp) < 30 * 60 * 1000L &&
                        (it.message.contains("reason", ignoreCase = true) || it.eventType == "OFFLINE_PERIOD")
            }

            val reason = when {
                relatedEvent?.message?.contains("airplane", ignoreCase = true) == true -> "Airplane Mode"
                relatedEvent?.message?.contains("manual", ignoreCase = true) == true -> "Mobile Data / Wi-Fi Off"
                else -> "Network Disconnected / Signal Loss"
            }

            val punchesInGap = localPunches.filter { p ->
                val pStaffId = p.staffId.toIntOrNull() ?: 0
                (pStaffId == employeeId || employeeId == 0 || p.staffId == employeeId.toString()) &&
                        p.timestamp >= (first.timestamp - 120_000L) &&
                        p.timestamp <= (last.timestamp + 120_000L)
            }.sortedBy { it.timestamp }.map { p ->
                val isOutside = if (officeLat != 0.0 && officeLng != 0.0 && p.latitude != 0.0) {
                    calculateDistance(p.latitude, p.longitude, officeLat, officeLng) > officeRadiusMeters
                } else false

                val changeDetail = when {
                    p.type.equals("OUT", ignoreCase = true) -> "Exited ${officeRadiusMeters}m radius → Shift Closed"
                    p.type.equals("IN", ignoreCase = true) -> "Entered ${officeRadiusMeters}m radius → Shift Opened"
                    else -> "Attendance Punch Recorded"
                }

                OfflinePunchInfo(
                    type = p.type,
                    timestamp = p.timestamp,
                    isSynced = p.syncState == 1,
                    isOutside = isOutside,
                    changeDetail = changeDetail
                )
            }

            val inRadiusCount = if (officeLat != 0.0 && officeLng != 0.0) {
                c.count { calculateDistance(it.latitude, it.longitude, officeLat, officeLng) <= officeRadiusMeters }
            } else {
                c.count { it.accuracy > 0 }
            }

            val isSynced = c.all { it.syncState == LocalLocation.SYNCED || it.syncState == LocalLocation.FIREBASE_SYNCED }

            val liveImpact = when {
                punchesInGap.isNotEmpty() -> {
                    val punchDetails = punchesInGap.joinToString(", ") { p ->
                        val timeStr = punchFmt.format(Date(p.timestamp))
                        "${p.type.uppercase()} at $timeStr (${if (p.type.equals("OUT", true)) "Exited radius → Closed shift" else "Entered radius → Opened shift"})"
                    }
                    if (isSynced) {
                        "Live Attendance Updated: $punchDetails • Merged into cloud attendance ledger"
                    } else {
                        "Pending Local Reconcile: $punchDetails • Queued on device"
                    }
                }
                isOffShift -> {
                    "Off-Duty Window: Outside scheduled shift ($shiftStartStr - $shiftEndStr) • Tracking idle, no attendance punch created"
                }
                priorState == "IN" && inRadiusCount > 0 -> {
                    "Stationary / Active Inside: Remained inside ${officeRadiusMeters}m radius • Active shift remained IN (No punch created)"
                }
                priorState == "OUT" -> {
                    "Stationary / Moving Outside: Stayed outside ${officeRadiusMeters}m radius • Attendance remained OUT (No punch created)"
                }
                else -> {
                    "No Attendance State Changes: ${c.size} breadcrumbs captured • Reconciled to live route"
                }
            }

            val periodStays = detectPeriodStays(c, officeLat, officeLng, officeRadiusMeters)

            result.add(
                OfflinePeriodItem(
                    id = "${employeeId}_${first.sessionId}_${first.timestamp}",
                    employeeId = employeeId,
                    employeeName = employeeName,
                    startTime = first.timestamp,
                    endTime = last.timestamp,
                    durationMs = duration,
                    reason = reason,
                    pointsCount = c.size,
                    distanceMeters = dist,
                    inRadiusCount = inRadiusCount,
                    isSynced = isSynced,
                    points = c,
                    punches = punchesInGap,
                    stays = periodStays,
                    isOffShift = isOffShift,
                    shiftTag = shiftTag,
                    liveImpactSummary = liveImpact,
                    priorState = priorState
                )
            )
        }

        return result
    }

    private fun detectPeriodStays(
        points: List<LocalLocation>,
        officeLat: Double,
        officeLng: Double,
        radiusMeters: Int
    ): List<OfflineStayPoint> {
        val stays = mutableListOf<OfflineStayPoint>()
        if (points.isEmpty()) return stays

        val ordered = points.sortedBy { it.timestamp }
        var curCluster = mutableListOf(ordered[0])

        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]
            val curr = ordered[i]
            val dist = calculateDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            val gap = curr.timestamp - prev.timestamp

            if (dist <= 40.0 && gap <= 15 * 60 * 1000L) {
                curCluster.add(curr)
            } else {
                addStayIfValid(curCluster, stays, officeLat, officeLng, radiusMeters)
                curCluster = mutableListOf(curr)
            }
        }
        addStayIfValid(curCluster, stays, officeLat, officeLng, radiusMeters)
        return stays
    }

    private fun addStayIfValid(
        points: List<LocalLocation>,
        stays: MutableList<OfflineStayPoint>,
        officeLat: Double,
        officeLng: Double,
        radiusMeters: Int
    ) {
        if (points.isEmpty()) return
        val first = points.first()
        val last = points.last()
        val duration = last.timestamp - first.timestamp

        if (duration >= 2 * 60 * 1000L || (points.size >= 3 && points.all { it.speed < 0.5f })) {
            val avgLat = points.map { it.latitude }.average()
            val avgLng = points.map { it.longitude }.average()
            val distToOffice = if (officeLat != 0.0 && officeLng != 0.0) {
                calculateDistance(avgLat, avgLng, officeLat, officeLng).toInt()
            } else 0
            val isInside = if (officeLat != 0.0 && officeLng != 0.0 && radiusMeters > 0) {
                distToOffice <= radiusMeters
            } else {
                true
            }

            val desc = if (officeLat != 0.0 && officeLng != 0.0) {
                if (isInside) "Inside Office Geofence (${distToOffice}m from center)"
                else "Outside Geofence (${distToOffice}m from office)"
            } else {
                if (isInside) "Within Radius" else "Outside Radius"
            }

            stays.add(
                OfflineStayPoint(
                    startTime = first.timestamp,
                    endTime = last.timestamp,
                    durationMs = duration.coerceAtLeast(60_000L),
                    latitude = avgLat,
                    longitude = avgLng,
                    pointCount = points.size,
                    isInsideGeofence = isInside,
                    distanceFromOffice = distToOffice,
                    locationDescription = desc
                )
            )
        }
    }

    private fun isTimeBetween(currentTime: String, startTime: String, endTime: String): Boolean {
        val cur = parseTimeMinutes(currentTime) ?: return false
        val start = parseTimeMinutes(startTime) ?: return false
        val end = parseTimeMinutes(endTime) ?: return false
        return if (end >= start) {
            cur in start..end
        } else {
            cur >= start || cur <= end
        }
    }

    private fun parseTimeMinutes(timeStr: String): Int? {
        val parts = timeStr.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }

    private fun formatTimeOnly(epoch: Long): String {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        return SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = tz }.format(Date(epoch))
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        val m = s / 60
        val h = m / 60
        return when {
            h > 0 -> "${h}h ${m % 60}m"
            m > 0 -> "${m}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    private fun parseTrackingTimestamp(ts: String?): Long {
        if (ts.isNullOrBlank()) return 0L
        return try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            )
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val d = sdf.parse(ts)
                    if (d != null) return d.time
                } catch (_: Exception) {}
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }
}

data class OfflinePeriodItem(
    val id: String,
    val employeeId: Int,
    val employeeName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val reason: String,
    val pointsCount: Int,
    val distanceMeters: Double,
    val inRadiusCount: Int,
    val isSynced: Boolean,
    val points: List<LocalLocation>,
    val punches: List<OfflinePunchInfo>,
    val stays: List<OfflineStayPoint>,
    val isOffShift: Boolean,
    val shiftTag: String,
    val liveImpactSummary: String,
    val priorState: String
)

data class OfflineStayPoint(
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val latitude: Double,
    val longitude: Double,
    val pointCount: Int,
    val isInsideGeofence: Boolean,
    val distanceFromOffice: Int,
    val locationDescription: String
)

data class OfflinePunchInfo(
    val type: String,
    val timestamp: Long,
    val isSynced: Boolean,
    val isOutside: Boolean,
    val changeDetail: String
)

private class OfflinePeriodAdapter(
    private val onInspect: (OfflinePeriodItem) -> Unit
) : RecyclerView.Adapter<OfflinePeriodAdapter.Holder>() {

    private var items = listOf<OfflinePeriodItem>()

    fun submit(newItems: List<OfflinePeriodItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_period, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onInspect)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvShiftTag = view.findViewById<TextView>(R.id.tvPeriodShiftTag)
        private val tvTitle = view.findViewById<TextView>(R.id.tvPeriodTitle)
        private val tvWindow = view.findViewById<TextView>(R.id.tvPeriodWindow)
        private val tvDuration = view.findViewById<TextView>(R.id.tvPeriodDuration)
        private val tvReason = view.findViewById<TextView>(R.id.tvPeriodReason)
        private val tvSyncStatus = view.findViewById<TextView>(R.id.tvPeriodSyncStatus)
        private val tvLiveImpactBadge = view.findViewById<TextView>(R.id.tvPeriodLiveImpactBadge)
        private val tvLiveImpact = view.findViewById<TextView>(R.id.tvPeriodLiveImpact)
        private val llOfflinePunchesContainer = view.findViewById<View>(R.id.llOfflinePunchesContainer)
        private val tvPunches = view.findViewById<TextView>(R.id.tvPeriodPunches)
        private val tvStep1Detail = view.findViewById<TextView>(R.id.tvStep1Detail)
        private val tvStep2Detail = view.findViewById<TextView>(R.id.tvStep2Detail)
        private val tvStep3Detail = view.findViewById<TextView>(R.id.tvStep3Detail)
        private val tvStep4Detail = view.findViewById<TextView>(R.id.tvStep4Detail)
        private val tvStats = view.findViewById<TextView>(R.id.tvPeriodStats)
        private val btnInspect = view.findViewById<MaterialButton>(R.id.btnViewPeriodRoute)

        fun bind(item: OfflinePeriodItem, onInspect: (OfflinePeriodItem) -> Unit) {
            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd-MMM hh:mm a", Locale.US).apply { timeZone = tz }
            val timeFmt = SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = tz }
            val punchFmt = SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = tz }

            tvTitle.text = if (item.employeeName.isNotBlank()) item.employeeName else "Employee #${item.employeeId}"
            tvWindow.text = "Disconnected: ${fmt.format(Date(item.startTime))} → Reconnected: ${timeFmt.format(Date(item.endTime))} IST"

            tvShiftTag.text = item.shiftTag
            if (item.isOffShift) {
                tvShiftTag.setTextColor(Color.parseColor("#616161"))
                tvShiftTag.setBackgroundColor(Color.parseColor("#EEEEEE"))
            } else {
                tvShiftTag.setTextColor(Color.parseColor("#E65100"))
                tvShiftTag.setBackgroundColor(Color.parseColor("#FFF3E0"))
            }

            val mins = item.durationMs / 60000
            val secs = (item.durationMs % 60000) / 1000
            tvDuration.text = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

            tvReason.text = when {
                item.reason.contains("airplane", ignoreCase = true) -> "✈️ ${item.reason}"
                item.reason.contains("wi-fi", ignoreCase = true) || item.reason.contains("data", ignoreCase = true) -> "📶 ${item.reason}"
                else -> "📴 ${item.reason}"
            }

            if (item.isSynced) {
                tvSyncStatus.text = "✓ Reconciled into Live Data"
                tvSyncStatus.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                tvSyncStatus.text = "⏳ Local Queue (Pending Sync)"
                tvSyncStatus.setTextColor(Color.parseColor("#F57C00"))
            }

            // Live Attendance Impact Badge & Detailed Summary
            val hasAutoOut = item.punches.any { it.type.equals("OUT", ignoreCase = true) && it.changeDetail.contains("radius", ignoreCase = true) } ||
                    item.liveImpactSummary.contains("Auto OUT", ignoreCase = true)
            val hasPunches = item.punches.isNotEmpty()
            val outOfRadiusCount = (item.pointsCount - item.inRadiusCount).coerceAtLeast(0)

            when {
                hasAutoOut -> {
                    tvLiveImpactBadge.text = "⚡ Auto OUT Punch Triggered"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#C62828"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#FFEBEE"))
                }
                hasPunches -> {
                    tvLiveImpactBadge.text = "🟢 Reconciled Attendance Punch"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#2E7D32"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                item.isOffShift -> {
                    tvLiveImpactBadge.text = "🌙 Off-Duty Window (No Impact)"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#616161"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                item.priorState == "IN" && outOfRadiusCount == 0 -> {
                    tvLiveImpactBadge.text = "🛡️ No Live Attendance Impact"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#2E7D32"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                item.priorState == "OUT" -> {
                    tvLiveImpactBadge.text = "🛡️ No Attendance State Change"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#455A64"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#ECEFF1"))
                }
                else -> {
                    tvLiveImpactBadge.text = "🛡️ No Live Attendance Impact"
                    tvLiveImpactBadge.setTextColor(Color.parseColor("#1565C0"))
                    tvLiveImpactBadge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                }
            }

            tvLiveImpact.text = item.liveImpactSummary.ifBlank {
                when {
                    hasAutoOut -> "Shift automatically closed upon exiting office geofence. Reconciled to cloud attendance ledger."
                    hasPunches -> "${item.punches.size} punch(es) captured during offline gap reconciled to cloud upon reconnection."
                    item.isOffShift -> "Outside assigned shift hours. Tracking was idle; no attendance punches were generated."
                    item.priorState == "IN" && outOfRadiusCount == 0 -> "Employee remained safely inside office radius throughout offline window. Active IN shift preserved."
                    item.priorState == "OUT" -> "Employee was already clocked OUT. Remained outside office perimeter; no change to attendance status."
                    else -> "${item.pointsCount} offline breadcrumb points merged into cloud route upon reconnection."
                }
            }

            // Sub-container for punch changes
            if (item.punches.isNotEmpty()) {
                llOfflinePunchesContainer.visibility = View.VISIBLE
                val punchText = item.punches.joinToString("\n") { p ->
                    val icon = if (p.type.equals("OUT", ignoreCase = true)) "🔴" else "🟢"
                    val syncLabel = if (p.isSynced) "Synced to Live" else "Pending Local"
                    val detail = if (p.changeDetail.isNotBlank()) " • ${p.changeDetail}" else ""
                    "$icon ${p.type.uppercase()} Punch at ${punchFmt.format(Date(p.timestamp))} ($syncLabel)$detail"
                }
                tvPunches.text = punchText
                tvPunches.setTextColor(if (hasAutoOut) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
            } else {
                llOfflinePunchesContainer.visibility = View.GONE
            }

            // Step 1: Disconnection Event
            val firstPt = item.points.firstOrNull()
            val step1Loc = if (item.inRadiusCount > 0 && firstPt != null) "Inside Office Zone" else "Outside Office"
            tvStep1Detail.text = "Disconnected at ${timeFmt.format(Date(item.startTime))} IST • Cause: ${item.reason} • Position: $step1Loc"

            // Step 2: Journey & Stay Stops
            val distText = if (item.distanceMeters >= 1000) {
                String.format(Locale.US, "%.2f km", item.distanceMeters / 1000.0)
            } else {
                String.format(Locale.US, "%.0f m", item.distanceMeters)
            }
            val step2Text = StringBuilder()
            step2Text.append("Traversed $distText across ${item.pointsCount} points captured locally")
            if (item.stays.isNotEmpty()) {
                step2Text.append("\n")
                val staySummary = item.stays.joinToString("\n") { s ->
                    val durM = (s.durationMs / 60000).coerceAtLeast(1)
                    val sTime = "${timeFmt.format(Date(s.startTime))} – ${timeFmt.format(Date(s.endTime))}"
                    "📍 Stayed ${durM}m ($sTime): ${s.locationDescription}"
                }
                step2Text.append(staySummary)
            } else if (item.distanceMeters < 30.0) {
                val locDesc = if (item.inRadiusCount > 0) "Inside Office Zone" else "Outside Office Perimeter"
                step2Text.append(" • Stationary at $locDesc (${item.pointsCount} points captured) throughout entire disconnect window")
            } else {
                step2Text.append(" • Continuous movement in transit with no stationary stops")
            }
            tvStep2Detail.text = step2Text.toString()

            // Step 3: Geofence Perimeter Assessment
            val step3Desc = StringBuilder()
            step3Desc.append("📴 During Offline Disconnection Window:\n")
            if (outOfRadiusCount == 0) {
                step3Desc.append("• Employee remained 100% Inside Office Radius (${item.pointsCount} points within boundary) • Safe on-premises.\n")
            } else if (item.inRadiusCount == 0) {
                step3Desc.append("• Employee remained Outside Office Perimeter ($outOfRadiusCount points outside boundary) throughout entire disconnect window.\n")
            } else {
                step3Desc.append("• Perimeter Transition: ${item.inRadiusCount} points inside, $outOfRadiusCount points outside • Exited office boundary during disconnect.\n")
            }
            step3Desc.append("📶 Upon Network Reconnection:\n")
            step3Desc.append("• ${item.liveImpactSummary}")
            tvStep3Detail.text = step3Desc.toString()

            // Step 4: Reconnection & Cloud Sync
            val syncNote = if (item.isSynced) {
                "✓ All ${item.pointsCount} offline breadcrumbs & punch events reconciled into cloud database"
            } else {
                "⏳ ${item.pointsCount} points stored in local offline queue • Awaiting cloud transmission"
            }
            tvStep4Detail.text = "Reconnected at ${timeFmt.format(Date(item.endTime))} IST\n$syncNote"

            // Summary Statistics line
            tvStats.text = "${item.pointsCount} points captured • Distance: $distText • Within geofence: ${item.inRadiusCount} / Outside: $outOfRadiusCount"

            btnInspect.setOnClickListener {
                onInspect(item)
            }
        }
    }
}
