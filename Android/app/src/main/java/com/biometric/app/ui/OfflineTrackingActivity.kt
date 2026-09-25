package com.biometric.app.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
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
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.OfflineTrackingEvent
import com.biometric.app.domain.location.OfflineSyncWorker
import com.biometric.app.domain.location.OfflineTrackingMonitor
import com.biometric.app.sync.SignalRManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class OfflineTrackingActivity : AppCompatActivity() {
    @Inject lateinit var repo: MainRepository
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var eventDao: OfflineTrackingEventDao
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var monitor: OfflineTrackingMonitor
    @Inject lateinit var signalR: SignalRManager

    private lateinit var mapView: MapView
    private lateinit var tvConnectivity: TextView
    private lateinit var tvLiveCount: TextView
    private lateinit var tvStaleCount: TextView
    private lateinit var tvOfflineCount: TextView
    private lateinit var tvLastRefreshTime: TextView
    private lateinit var tvEmployeesCountBadge: TextView
    private lateinit var rvEmployeeConnectionStatus: RecyclerView
    private lateinit var tvNoEmployeesRegistered: TextView

    private lateinit var spnFilterEmployee: Spinner
    private lateinit var tvPeriodCountBadge: TextView
    private lateinit var rvOfflinePeriods: RecyclerView
    private lateinit var tvNoOfflinePeriods: TextView

    private lateinit var tvMapRouteBanner: TextView
    private lateinit var btnResetMapFilter: MaterialButton

    private lateinit var tvQueue: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvLastCapture: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvRemoteHistory: TextView
    private lateinit var cardActiveOfflineNotice: MaterialCardView
    private lateinit var tvActiveOfflineElapsed: TextView
    private lateinit var tvActiveOfflineDetails: TextView
    private lateinit var rvOfflineEvents: RecyclerView

    private lateinit var employeeStatusAdapter: OfflineEmployeeStatusAdapter
    private lateinit var eventAdapter: OfflineEventAdapter
    private lateinit var periodAdapter: OfflinePeriodAdapter

    private var activeEmployees: List<Employee> = emptyList()
    private var selectedEmployeeId: Int = 0 // 0 = All
    private var selectedPeriod: OfflinePeriodItem? = null
    private var refreshJob: Job? = null
    private var cloudLoadJob: Job? = null
    private val cloudHistoryCache = mutableMapOf<Int, Pair<Long, List<SignalRManager.LiveLocation>>>()

    private suspend fun getCachedOrFetchHistory(empId: Int, limit: Int = 200): List<SignalRManager.LiveLocation> {
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
        monitor.start()

        initViews()
        setupMap()
        setupAdapters()
        setupListeners()
        observeData()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tvConnectivity = findViewById(R.id.tvConnectivity)
        tvLiveCount = findViewById(R.id.tvLiveCount)
        tvStaleCount = findViewById(R.id.tvStaleCount)
        tvOfflineCount = findViewById(R.id.tvOfflineCount)
        tvLastRefreshTime = findViewById(R.id.tvLastRefreshTime)
        tvEmployeesCountBadge = findViewById(R.id.tvEmployeesCountBadge)
        rvEmployeeConnectionStatus = findViewById(R.id.rvEmployeeConnectionStatus)
        tvNoEmployeesRegistered = findViewById(R.id.tvNoEmployeesRegistered)

        spnFilterEmployee = findViewById(R.id.spnFilterEmployee)
        tvPeriodCountBadge = findViewById(R.id.tvPeriodCountBadge)
        rvOfflinePeriods = findViewById(R.id.rvOfflinePeriods)
        tvNoOfflinePeriods = findViewById(R.id.tvNoOfflinePeriods)

        tvMapRouteBanner = findViewById(R.id.tvMapRouteBanner)
        btnResetMapFilter = findViewById(R.id.btnResetMapFilter)
        mapView = findViewById(R.id.offlineMapView)

        tvQueue = findViewById(R.id.tvQueue)
        tvTotal = findViewById(R.id.tvTotal)
        tvSession = findViewById(R.id.tvSession)
        tvLastCapture = findViewById(R.id.tvLastCapture)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvRemoteHistory = findViewById(R.id.tvRemoteHistory)
        cardActiveOfflineNotice = findViewById(R.id.cardActiveOfflineNotice)
        tvActiveOfflineElapsed = findViewById(R.id.tvActiveOfflineElapsed)
        tvActiveOfflineDetails = findViewById(R.id.tvActiveOfflineDetails)
        rvOfflineEvents = findViewById(R.id.rvOfflineEvents)
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
        employeeStatusAdapter = OfflineEmployeeStatusAdapter { empId ->
            // On employee card click, switch spinner to this employee
            val pos = activeEmployees.indexOfFirst { it.employeeId.toIntOrNull() == empId }
            if (pos >= 0) {
                spnFilterEmployee.setSelection(pos + 1)
            }
        }
        rvEmployeeConnectionStatus.apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = employeeStatusAdapter
            isNestedScrollingEnabled = false
        }

        periodAdapter = OfflinePeriodAdapter { period ->
            selectedPeriod = period
            btnResetMapFilter.visibility = View.VISIBLE
            val periodEmp = if (period.employeeName.isNotBlank()) "${period.employeeName} • " else ""
            tvMapRouteBanner.text = "Showing offline route: $periodEmp${formatTimeOnly(period.startTime)} → ${formatTimeOnly(period.endTime)} (${period.pointsCount} points)"
            drawLocalRoute(period.points, isOfflinePeriod = true, periodLabel = "${formatTimeOnly(period.startTime)} - ${formatTimeOnly(period.endTime)}")
        }
        rvOfflinePeriods.apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = periodAdapter
            isNestedScrollingEnabled = false
        }

        eventAdapter = OfflineEventAdapter()
        rvOfflineEvents.apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = eventAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        btnResetMapFilter.setOnClickListener {
            selectedPeriod = null
            btnResetMapFilter.visibility = View.GONE
            tvMapRouteBanner.text = "Cached map tiles + locally retained GPS ledger"
            refreshCloudAndLocal()
        }

        findViewById<MaterialButton>(R.id.btnSyncNow).setOnClickListener {
            OfflineSyncWorker.schedule(this)
            Toast.makeText(this, "Offline GPS sync queued", Toast.LENGTH_SHORT).show()
            refreshOnce()
        }

        findViewById<MaterialButton>(R.id.btnRefreshOffline).setOnClickListener {
            cloudHistoryCache.clear()
            signalR.reconcileLiveLocationsNow()
            refreshCloudAndLocal()
        }

        findViewById<MaterialButton>(R.id.btnLoadCloudHistory).setOnClickListener {
            loadCloudHistory()
        }

        spnFilterEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedEmployeeId = if (position == 0) {
                    0
                } else {
                    activeEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull() ?: 0
                }
                selectedPeriod = null
                btnResetMapFilter.visibility = View.GONE
                refreshCloudAndLocal()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repo.allEmployeesFlow.collect { list ->
                activeEmployees = list.filter { it.isActive }
                updateEmployeeSpinner()
                refreshStatusTable()
            }
        }

        lifecycleScope.launch {
            signalR.liveLocations.collect {
                refreshStatusTable()
            }
        }
    }

    private fun updateEmployeeSpinner() {
        val options = mutableListOf("All Tracked Employees")
        options.addAll(activeEmployees.map { "${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spnFilterEmployee.adapter = adapter
    }

    private fun refreshStatusTable() {
        val liveMap = signalR.liveLocations.value
        val now = System.currentTimeMillis()

        var liveCount = 0
        var staleCount = 0
        var offlineCount = 0

        val rows = activeEmployees.map { emp ->
            val empId = emp.employeeId.toIntOrNull() ?: 0
            val live = liveMap[empId]

            val (status, ageSec, updatedTime) = if (live != null && live.timestamp != null) {
                val epoch = parseTrackingTimestamp(live.timestamp)
                val age = ((now - epoch) / 1000L).coerceAtLeast(0L)
                val st = when {
                    age <= 300L -> {
                        liveCount++
                        "Live"
                    }
                    age <= 900L -> {
                        staleCount++
                        "Stale"
                    }
                    else -> {
                        offlineCount++
                        "Offline"
                    }
                }
                Triple(st, age, epoch)
            } else {
                offlineCount++
                Triple("Offline", Long.MAX_VALUE, null)
            }

            EmployeeStatusRow(
                employeeId = empId,
                employeeName = emp.name,
                status = status,
                lastUpdatedUtc = updatedTime,
                ageSeconds = ageSec,
                movementState = live?.movementState ?: "No active session",
                speedKmh = (live?.speedMps ?: 0.0) * 3.6,
                latitude = live?.latitude ?: 0.0,
                longitude = live?.longitude ?: 0.0,
                isWithinRadius = live?.isWithinAllowedRadius ?: false
            )
        }.sortedWith(compareBy({
            when (it.status) {
                "Live" -> 0
                "Stale" -> 1
                else -> 2
            }
        }, { it.employeeName }))

        tvLiveCount.text = liveCount.toString()
        tvStaleCount.text = staleCount.toString()
        tvOfflineCount.text = offlineCount.toString()
        tvLastRefreshTime.text = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(now))
        tvEmployeesCountBadge.text = "${rows.size} employees"

        employeeStatusAdapter.submit(rows)
        tvNoEmployeesRegistered.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshOnce()
                delay(5000L)
            }
        }
        refreshCloudAndLocal()
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun refreshOnce() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isOnline = monitor.isOnline()
            val pending = locationDao.getPendingCount()
            val total = locationDao.getTotalCount()
            val recent = locationDao.getRecent(50)
            val lastSynced = locationDao.getLastSynced()
            val events = eventDao.recent(50)
            val currentSession = sessionStore.gpsSessionId()

            withContext(Dispatchers.Main) {
                val disconnectReason = monitor.getDisconnectReason()
                tvConnectivity.text =
                    if (isOnline) {
                        "ONLINE • Server reachable & synchronized"
                    } else {
                        "OFFLINE • Local capture active ($disconnectReason)"
                    }

                if (!isOnline) {
                    val offlineStart = monitor.getActiveOfflineStartTime()
                    val activeReason = monitor.getActiveOfflineReason()
                    val elapsedMs = if (offlineStart > 0L) (System.currentTimeMillis() - offlineStart).coerceAtLeast(0L) else 0L
                    val mins = elapsedMs / 60000
                    val secs = (elapsedMs % 60000) / 1000

                    cardActiveOfflineNotice.visibility = View.VISIBLE
                    tvActiveOfflineElapsed.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                    tvActiveOfflineDetails.text = "Started at ${if (offlineStart > 0L) formatIst(offlineStart) else "now"} • Reason: $activeReason\nAll tracking points and punches are recorded locally and will sync when internet returns."
                } else {
                    cardActiveOfflineNotice.visibility = View.GONE
                }

                tvQueue.text = pending.toString()
                tvTotal.text = total.toString()
                tvLastCapture.text =
                    recent.firstOrNull()?.let { "Last local capture: ${formatIst(it.timestamp)}" } ?: "Last local capture: —"
                tvLastSync.text =
                    lastSynced?.syncedAt?.let { "Last sync: ${formatIst(it)}" } ?: "Last sync: —"
                tvSession.text =
                    if (currentSession.length > 8) currentSession.take(8) + "…" else currentSession

                eventAdapter.submit(events)
            }
        }
    }

    private fun refreshCloudAndLocal() {
        cloudLoadJob?.cancel()
        cloudLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val localRecent = locationDao.getRecent(300)
            val localEvents = eventDao.recent(100)

            // When "All Tracked Employees" is selected, do not eagerly pull 15,000 points from Firebase.
            // Only pull cloud history when a specific employee is chosen by the admin.
            val empIdsToQuery = if (selectedEmployeeId > 0) {
                listOf(selectedEmployeeId)
            } else {
                emptyList()
            }

            val empNameMap = activeEmployees.associate { (it.employeeId.toIntOrNull() ?: 0) to it.name }
            val allPeriods = mutableListOf<OfflinePeriodItem>()
            val allPointsForMap = mutableListOf<LocalLocation>()

            // 1. Check local periods first
            val localPeriods = buildOfflinePeriods(localRecent, localEvents, sessionStore.employeeId(), empNameMap[sessionStore.employeeId()] ?: "This Device")
            allPeriods.addAll(localPeriods)
            allPointsForMap.addAll(localRecent)

            // 2. Fetch history for selected employee (cached or targeted 200 points)
            for (eid in empIdsToQuery) {
                if (eid <= 0) continue
                val hist = getCachedOrFetchHistory(eid, 200)
                if (hist.isNotEmpty()) {
                    val localConverted = hist.map { h ->
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
                            isOfflineCapture = h.movementState.contains("offline", ignoreCase = true)
                        )
                    }

                    val empName = empNameMap[eid] ?: "Employee #$eid"
                    val empPeriods = buildOfflinePeriods(localConverted, emptyList(), eid, empName)
                    allPeriods.addAll(empPeriods)
                    allPointsForMap.addAll(localConverted)
                }
            }

            val sortedPeriods = allPeriods.distinctBy { it.id }.sortedByDescending { it.startTime }

            withContext(Dispatchers.Main) {
                tvPeriodCountBadge.text = "${sortedPeriods.size} periods"
                periodAdapter.submit(sortedPeriods)
                tvNoOfflinePeriods.visibility = if (sortedPeriods.isEmpty()) View.VISIBLE else View.GONE

                val activeSel = selectedPeriod
                if (activeSel != null) {
                    drawLocalRoute(activeSel.points, isOfflinePeriod = true, periodLabel = "${formatTimeOnly(activeSel.startTime)} - ${formatTimeOnly(activeSel.endTime)}")
                } else if (allPointsForMap.isNotEmpty()) {
                    drawLocalRoute(allPointsForMap.take(300))
                }
            }
        }
    }

    private fun loadCloudHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val empId = if (selectedEmployeeId > 0) {
                selectedEmployeeId
            } else {
                activeEmployees.firstOrNull()?.employeeId?.toIntOrNull() ?: sessionStore.employeeId()
            }

            if (empId <= 0) {
                withContext(Dispatchers.Main) {
                    tvRemoteHistory.text = "Cloud history: No employee selected"
                }
                return@launch
            }

            val history = getCachedOrFetchHistory(empId, 300)
            withContext(Dispatchers.Main) {
                tvRemoteHistory.text = if (history.isEmpty()) {
                    "Cloud history: No Firebase history for employee #$empId"
                } else {
                    val first = history.first().timestamp ?: "—"
                    val last = history.last().timestamp ?: "—"
                    "Cloud history: ${history.size} points for #$empId • $first → $last"
                }

                if (history.isNotEmpty()) {
                    drawRemoteRoute(history)
                }
            }
        }
    }

    private fun buildOfflinePeriods(
        locations: List<LocalLocation>,
        events: List<OfflineTrackingEvent>,
        employeeId: Int,
        employeeName: String
    ): List<OfflinePeriodItem> {
        val sorted = locations.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()

        val result = mutableListOf<OfflinePeriodItem>()
        val clusters = mutableListOf<MutableList<LocalLocation>>()
        var curCluster = mutableListOf<LocalLocation>()

        for (i in sorted.indices) {
            val loc = sorted[i]
            val last = curCluster.lastOrNull()

            // A cluster is either consecutive offline points, OR points surrounding a large gap (>15m)
            val gap = if (last != null) loc.timestamp - last.timestamp else 0L
            if (loc.isOfflineCapture || (gap > 15 * 60 * 1000L && gap < 24 * 3600 * 1000L)) {
                if (curCluster.isNotEmpty() && gap > 30 * 60 * 1000L) {
                    clusters.add(curCluster)
                    curCluster = mutableListOf()
                }
                curCluster.add(loc)
            } else {
                if (curCluster.isNotEmpty()) {
                    clusters.add(curCluster)
                    curCluster = mutableListOf()
                }
            }
        }
        if (curCluster.isNotEmpty()) {
            clusters.add(curCluster)
        }

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

            val relatedEvent = events.firstOrNull {
                abs(it.eventTime - first.timestamp) < 30 * 60 * 1000L &&
                        (it.message.contains("reason", ignoreCase = true) || it.eventType == "OFFLINE_PERIOD")
            }

            val reason = when {
                relatedEvent?.message?.contains("airplane", ignoreCase = true) == true -> "Airplane Mode"
                relatedEvent?.message?.contains("manual", ignoreCase = true) == true -> "Mobile Data / Wi-Fi Off"
                else -> "Network Disconnected / Signal Loss"
            }

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
                    inRadiusCount = c.count { it.accuracy > 0 },
                    isSynced = c.all { it.syncState == LocalLocation.SYNCED || it.syncState == LocalLocation.FIREBASE_SYNCED },
                    points = c
                )
            )
        }

        return result
    }

    private fun drawLocalRoute(
        points: List<LocalLocation>,
        isOfflinePeriod: Boolean = false,
        periodLabel: String? = null
    ) {
        val valid = points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        mapView.overlays.clear()

        if (valid.isEmpty()) {
            mapView.invalidate()
            return
        }

        val geoPoints = valid.map { GeoPoint(it.latitude, it.longitude) }
        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = if (isOfflinePeriod) Color.parseColor("#E65100") else Color.parseColor("#1565C0")
            outlinePaint.strokeWidth = if (isOfflinePeriod) 10f else 8f
        }
        mapView.overlays.add(polyline)

        // Start marker
        val startLoc = valid.first()
        Marker(mapView).apply {
            position = GeoPoint(startLoc.latitude, startLoc.longitude)
            title = "Start: ${formatTimeOnly(startLoc.timestamp)}"
            snippet = if (isOfflinePeriod) "Offline disconnected here" else "First point"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }

        // End marker
        val endLoc = valid.last()
        Marker(mapView).apply {
            position = GeoPoint(endLoc.latitude, endLoc.longitude)
            title = "End / Reconnect: ${formatTimeOnly(endLoc.timestamp)}"
            snippet = if (isOfflinePeriod) "Reconnected here" else "Last point"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }

        zoomToFit(geoPoints)
    }

    private fun drawRemoteRoute(points: List<SignalRManager.LiveLocation>) {
        val valid = points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        mapView.overlays.clear()

        if (valid.isEmpty()) {
            mapView.invalidate()
            return
        }

        val geoPoints = valid.map { GeoPoint(it.latitude, it.longitude) }
        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#43A047")
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(polyline)

        val first = valid.first()
        Marker(mapView).apply {
            position = GeoPoint(first.latitude, first.longitude)
            title = "Cloud History Start"
            snippet = first.timestamp ?: "—"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }

        val last = valid.last()
        Marker(mapView).apply {
            position = GeoPoint(last.latitude, last.longitude)
            title = "Cloud History Latest"
            snippet = last.timestamp ?: "—"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }

        zoomToFit(geoPoints)
    }

    private fun zoomToFit(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            mapView.controller.setCenter(points.first())
            mapView.controller.setZoom(16.0)
            mapView.invalidate()
            return
        }

        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0

        for (p in points) {
            minLat = min(minLat, p.latitude)
            maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude)
            maxLon = max(maxLon, p.longitude)
        }

        val box = BoundingBox(maxLat + 0.002, maxLon + 0.002, minLat - 0.002, minLon - 0.002)
        mapView.zoomToBoundingBox(box, true, 40)
        mapView.invalidate()
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    private fun formatIst(time: Long): String =
        SimpleDateFormat("dd-MMM HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(time))

    private fun formatTimeOnly(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(time))

    private fun parseTrackingTimestamp(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(str).toEpochMilli() }
            .getOrElse {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(str)?.time ?: 0L
                }.getOrDefault(0L)
            }
    }

    override fun onDestroy() {
        mapView.onDetach()
        super.onDestroy()
    }
}

data class EmployeeStatusRow(
    val employeeId: Int,
    val employeeName: String,
    val status: String,
    val lastUpdatedUtc: Long?,
    val ageSeconds: Long,
    val movementState: String,
    val speedKmh: Double,
    val latitude: Double,
    val longitude: Double,
    val isWithinRadius: Boolean
)

data class OfflinePeriodItem(
    val id: String,
    val employeeId: Int = 0,
    val employeeName: String = "",
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val reason: String,
    val pointsCount: Int,
    val distanceMeters: Double,
    val inRadiusCount: Int,
    val isSynced: Boolean,
    val points: List<LocalLocation>
)

private class OfflineEmployeeStatusAdapter(
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<OfflineEmployeeStatusAdapter.Holder>() {

    private var items = listOf<EmployeeStatusRow>()

    fun submit(newItems: List<EmployeeStatusRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_employee_status, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onSelect)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvEmpName = view.findViewById<TextView>(R.id.tvEmpName)
        private val tvEmpId = view.findViewById<TextView>(R.id.tvEmpId)
        private val tvStatusBadge = view.findViewById<TextView>(R.id.tvStatusBadge)
        private val tvLastUpdate = view.findViewById<TextView>(R.id.tvLastUpdate)
        private val tvAge = view.findViewById<TextView>(R.id.tvAge)
        private val tvMovementAndSpeed = view.findViewById<TextView>(R.id.tvMovementAndSpeed)
        private val tvGeofence = view.findViewById<TextView>(R.id.tvGeofence)
        private val tvCoordinates = view.findViewById<TextView>(R.id.tvCoordinates)
        private val card = view.findViewById<MaterialCardView>(R.id.cardEmployeeStatus)

        fun bind(item: EmployeeStatusRow, onSelect: (Int) -> Unit) {
            tvEmpName.text = item.employeeName
            tvEmpId.text = "ID #${item.employeeId}"

            when (item.status) {
                "Live" -> {
                    tvStatusBadge.text = "LIVE"
                    tvStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                "Stale" -> {
                    tvStatusBadge.text = "STALE"
                    tvStatusBadge.setTextColor(Color.parseColor("#F57F17"))
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FFF8E1"))
                }
                else -> {
                    tvStatusBadge.text = "OFFLINE"
                    tvStatusBadge.setTextColor(Color.parseColor("#C62828"))
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FFEBEE"))
                }
            }

            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).apply { timeZone = tz }
            tvLastUpdate.text = item.lastUpdatedUtc?.let { "Last: ${fmt.format(Date(it))}" } ?: "Last: Never"

            tvAge.text = formatAge(item.ageSeconds)
            tvMovementAndSpeed.text = "${item.movementState} • ${String.format(Locale.US, "%.1f", item.speedKmh)} km/h"
            tvGeofence.text = if (item.isWithinRadius) "Within range" else "Outside range"
            tvGeofence.setTextColor(if (item.isWithinRadius) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))

            tvCoordinates.text = if (item.latitude != 0.0 || item.longitude != 0.0) {
                String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude)
            } else {
                "No GPS fix recorded"
            }

            card.setOnClickListener {
                onSelect(item.employeeId)
            }
        }

        private fun formatAge(seconds: Long): String {
            if (seconds == Long.MAX_VALUE || seconds < 0) return "Offline"
            val mins = seconds / 60
            val hrs = mins / 60
            return when {
                hrs > 24 -> "${hrs / 24}d ago"
                hrs > 0 -> "${hrs}h ${mins % 60}m ago"
                mins > 0 -> "${mins}m ago"
                else -> "${seconds}s ago"
            }
        }
    }
}

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
        private val tvTitle = view.findViewById<TextView>(R.id.tvPeriodTitle)
        private val tvDuration = view.findViewById<TextView>(R.id.tvPeriodDuration)
        private val tvReason = view.findViewById<TextView>(R.id.tvPeriodReason)
        private val tvSyncStatus = view.findViewById<TextView>(R.id.tvPeriodSyncStatus)
        private val tvStats = view.findViewById<TextView>(R.id.tvPeriodStats)
        private val btnInspect = view.findViewById<MaterialButton>(R.id.btnViewPeriodRoute)

        fun bind(item: OfflinePeriodItem, onInspect: (OfflinePeriodItem) -> Unit) {
            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd-MMM HH:mm:ss", Locale.US).apply { timeZone = tz }
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = tz }

            val empPrefix = if (item.employeeName.isNotBlank()) "${item.employeeName} • " else ""
            tvTitle.text = "$empPrefix${fmt.format(Date(item.startTime))} → ${timeFmt.format(Date(item.endTime))} IST"

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

            val distText = if (item.distanceMeters >= 1000) {
                String.format(Locale.US, "%.2f km", item.distanceMeters / 1000.0)
            } else {
                String.format(Locale.US, "%.0f m", item.distanceMeters)
            }

            tvStats.text = "${item.pointsCount} points captured • Distance: $distText • Local storage verified"

            btnInspect.setOnClickListener {
                onInspect(item)
            }
        }
    }
}

private class OfflineEventAdapter :
    RecyclerView.Adapter<OfflineEventAdapter.Holder>() {

    private var items: List<OfflineTrackingEvent> = emptyList()

    fun submit(value: List<OfflineTrackingEvent>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_tracking_event, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvEventTitle)
        private val message = view.findViewById<TextView>(R.id.tvEventMessage)
        private val meta = view.findViewById<TextView>(R.id.tvEventMeta)

        fun bind(event: OfflineTrackingEvent) {
            title.text = "${event.eventType} • ${event.severity}"
            message.text = event.message

            val time = SimpleDateFormat(
                "dd-MMM-yyyy HH:mm:ss.SSS",
                Locale.US
            ).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.format(Date(event.eventTime))

            meta.text =
                "$time IST  •  network=${if (event.networkAvailable) "ONLINE" else "OFFLINE"}  •  queue=${event.queueDepth}" +
                        (event.correlationId?.let { "  •  id=${it.take(8)}…" } ?: "")
        }
    }
}
