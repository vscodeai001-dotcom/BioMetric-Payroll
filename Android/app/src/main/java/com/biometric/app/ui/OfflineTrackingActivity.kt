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
import com.biometric.app.data.dao.LocalAttendancePunchDao
import com.biometric.app.data.dao.OfflineTrackingEventDao
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
import android.text.Editable
import android.text.TextWatcher
import com.biometric.app.data.entity.Attendance
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.text.SimpleDateFormat
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

    private lateinit var mapView: MapView
    private lateinit var tvConnectivity: TextView
    private lateinit var tvLiveCount: TextView
    private lateinit var tvStaleCount: TextView
    private lateinit var tvOfflineCount: TextView
    private lateinit var tvLastRefreshTime: TextView
    private lateinit var tvEmployeesCountBadge: TextView
    private lateinit var rvEmployeeConnectionStatus: RecyclerView
    private lateinit var tvNoEmployeesRegistered: TextView

    // Search & Filter
    private lateinit var tilSearchEmployee: TextInputLayout
    private lateinit var etSearchEmployee: TextInputEditText
    private lateinit var chipGroupStatusFilter: ChipGroup

    // Selected Employee Inspection Panel
    private lateinit var cardSelectedEmployeeDetail: MaterialCardView
    private lateinit var tvSelectedEmpName: TextView
    private lateinit var tvSelectedEmpId: TextView
    private lateinit var tvSelectedEmpSyncBadge: TextView
    private lateinit var tvSelectedEmpStatusBadge: TextView
    private lateinit var btnCloseSelectedEmployee: MaterialButton
    private lateinit var tvSelectedPunchIn: TextView
    private lateinit var tvSelectedPunchLocation: TextView
    private lateinit var tvSelectedPunchOut: TextView
    private lateinit var tvSelectedWorkHours: TextView
    private lateinit var tvSelectedLiveCoord: TextView
    private lateinit var tvSelectedLiveMovement: TextView
    private lateinit var tvSelectedLiveGeofence: TextView
    private lateinit var tvSelectedLiveLastSeen: TextView
    private lateinit var tvSelectedCloudPoints: TextView
    private lateinit var tvSelectedPendingPoints: TextView
    private lateinit var tvSelectedOfflineIntervalsSummary: TextView
    private lateinit var btnFocusSelectedOnMap: MaterialButton
    private lateinit var btnReloadCloudForSelected: MaterialButton

    private lateinit var spnFilterEmployee: Spinner
    private lateinit var tvPeriodCountBadge: TextView
    private lateinit var rvOfflinePeriods: RecyclerView
    private lateinit var tvNoOfflinePeriods: TextView

    private lateinit var tvMapRouteBanner: TextView
    private lateinit var btnResetMapFilter: MaterialButton
    private lateinit var tvMapEmptyNotice: TextView

    // Journey Timeline
    private lateinit var cardJourneyTimeline: MaterialCardView
    private lateinit var tvJourneyTimelineCount: TextView
    private lateinit var rvJourneyTimeline: RecyclerView
    private lateinit var tvNoTimelineEvents: TextView
    private lateinit var journeyTimelineAdapter: JourneyTimelineAdapter

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
    private var allAttendanceList: List<Attendance> = emptyList()
    private var allPunchesList: List<LocalAttendancePunch> = emptyList()
    private var officeLat: Double = 0.0
    private var officeLng: Double = 0.0
    private var officeRadiusMeters: Int = 100
    private var searchQuery: String = ""
    private var statusFilter: String = "ALL"
    private var pendingGpsCount: Int = 0

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

        tvConnectivity = findViewById(R.id.tvConnectivity)
        tvLiveCount = findViewById(R.id.tvLiveCount)
        tvStaleCount = findViewById(R.id.tvStaleCount)
        tvOfflineCount = findViewById(R.id.tvOfflineCount)
        tvLastRefreshTime = findViewById(R.id.tvLastRefreshTime)
        tvEmployeesCountBadge = findViewById(R.id.tvEmployeesCountBadge)
        rvEmployeeConnectionStatus = findViewById(R.id.rvEmployeeConnectionStatus)
        tvNoEmployeesRegistered = findViewById(R.id.tvNoEmployeesRegistered)

        // Search & Filter
        tilSearchEmployee = findViewById(R.id.tilSearchEmployee)
        etSearchEmployee = findViewById(R.id.etSearchEmployee)
        chipGroupStatusFilter = findViewById(R.id.chipGroupStatusFilter)

        // Selected Employee Detail Panel
        cardSelectedEmployeeDetail = findViewById(R.id.cardSelectedEmployeeDetail)
        tvSelectedEmpName = findViewById(R.id.tvSelectedEmpName)
        tvSelectedEmpId = findViewById(R.id.tvSelectedEmpId)
        tvSelectedEmpSyncBadge = findViewById(R.id.tvSelectedEmpSyncBadge)
        tvSelectedEmpStatusBadge = findViewById(R.id.tvSelectedEmpStatusBadge)
        btnCloseSelectedEmployee = findViewById(R.id.btnCloseSelectedEmployee)
        tvSelectedPunchIn = findViewById(R.id.tvSelectedPunchIn)
        tvSelectedPunchLocation = findViewById(R.id.tvSelectedPunchLocation)
        tvSelectedPunchOut = findViewById(R.id.tvSelectedPunchOut)
        tvSelectedWorkHours = findViewById(R.id.tvSelectedWorkHours)
        tvSelectedLiveCoord = findViewById(R.id.tvSelectedLiveCoord)
        tvSelectedLiveMovement = findViewById(R.id.tvSelectedLiveMovement)
        tvSelectedLiveGeofence = findViewById(R.id.tvSelectedLiveGeofence)
        tvSelectedLiveLastSeen = findViewById(R.id.tvSelectedLiveLastSeen)
        tvSelectedCloudPoints = findViewById(R.id.tvSelectedCloudPoints)
        tvSelectedPendingPoints = findViewById(R.id.tvSelectedPendingPoints)
        tvSelectedOfflineIntervalsSummary = findViewById(R.id.tvSelectedOfflineIntervalsSummary)
        btnFocusSelectedOnMap = findViewById(R.id.btnFocusSelectedOnMap)
        btnReloadCloudForSelected = findViewById(R.id.btnReloadCloudForSelected)

        spnFilterEmployee = findViewById(R.id.spnFilterEmployee)
        tvPeriodCountBadge = findViewById(R.id.tvPeriodCountBadge)
        rvOfflinePeriods = findViewById(R.id.rvOfflinePeriods)
        tvNoOfflinePeriods = findViewById(R.id.tvNoOfflinePeriods)

        tvMapRouteBanner = findViewById(R.id.tvMapRouteBanner)
        btnResetMapFilter = findViewById(R.id.btnResetMapFilter)
        tvMapEmptyNotice = findViewById(R.id.tvMapEmptyNotice)
        mapView = findViewById(R.id.offlineMapView)

        // Journey Timeline
        cardJourneyTimeline = findViewById(R.id.cardJourneyTimeline)
        tvJourneyTimelineCount = findViewById(R.id.tvJourneyTimelineCount)
        rvJourneyTimeline = findViewById(R.id.rvJourneyTimeline)
        tvNoTimelineEvents = findViewById(R.id.tvNoTimelineEvents)

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
            selectEmployee(empId)
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

        journeyTimelineAdapter = JourneyTimelineAdapter()
        rvJourneyTimeline.apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = journeyTimelineAdapter
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
        // Search Input
        etSearchEmployee.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshStatusTable()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter Chips
        chipGroupStatusFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterLive -> "LIVE"
                R.id.chipFilterStale -> "STALE"
                R.id.chipFilterOffline -> "OFFLINE"
                R.id.chipFilterPendingSync -> "PENDING_SYNC"
                R.id.chipFilterSynced -> "SYNCED"
                else -> "ALL"
            }
            refreshStatusTable()
        }

        // Close Selected Employee Inspection Panel
        btnCloseSelectedEmployee.setOnClickListener {
            clearSelectedEmployee()
        }

        // Focus Map on Selected Route
        btnFocusSelectedOnMap.setOnClickListener {
            mapView.parent?.requestChildFocus(mapView, mapView)
        }

        // Reload Cloud History for Selected Employee
        btnReloadCloudForSelected.setOnClickListener {
            if (selectedEmployeeId > 0) {
                cloudHistoryCache.remove(selectedEmployeeId)
                refreshCloudAndLocal()
                Toast.makeText(this, "Refreshed cloud tracking history", Toast.LENGTH_SHORT).show()
            }
        }

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
                val newId = if (position == 0) {
                    0
                } else {
                    activeEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull() ?: 0
                }
                if (newId != selectedEmployeeId) {
                    if (newId == 0) {
                        clearSelectedEmployee()
                    } else {
                        selectEmployee(newId)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectEmployee(empId: Int) {
        selectedEmployeeId = empId
        selectedPeriod = null
        btnResetMapFilter.visibility = View.GONE

        val pos = activeEmployees.indexOfFirst { it.employeeId.toIntOrNull() == empId }
        if (pos >= 0 && spnFilterEmployee.selectedItemPosition != (pos + 1)) {
            spnFilterEmployee.setSelection(pos + 1)
        }

        refreshStatusTable()
        refreshCloudAndLocal()
    }

    private fun clearSelectedEmployee() {
        selectedEmployeeId = 0
        selectedPeriod = null
        btnResetMapFilter.visibility = View.GONE
        cardSelectedEmployeeDetail.visibility = View.GONE

        if (spnFilterEmployee.selectedItemPosition != 0) {
            spnFilterEmployee.setSelection(0)
        }

        refreshStatusTable()
        refreshCloudAndLocal()
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

        lifecycleScope.launch {
            repo.allAttendanceFlow.collect { attList ->
                allAttendanceList = attList
                refreshStatusTable()
            }
        }

        lifecycleScope.launch {
            punchDao.getAllFlow().collect { punches ->
                allPunchesList = punches
                refreshStatusTable()
            }
        }

        lifecycleScope.launch {
            repo.companySettingsFlow.collect { settings ->
                if (settings != null) {
                    officeLat = settings.officeLatitude
                    officeLng = settings.officeLongitude
                    officeRadiusMeters = settings.geoRadiusMeters
                    refreshStatusTable()
                }
            }
        }
    }

    private fun updateEmployeeSpinner() {
        val options = mutableListOf("All Tracked Employees")
        options.addAll(activeEmployees.map { "${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spnFilterEmployee.adapter = adapter
    }

    private fun isToday(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        val cal1 = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val cal2 = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { timeInMillis = timestamp }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatDuration(ms: Long): String {
        val mins = ms / 60000
        val secs = (ms % 60000) / 1000
        val hrs = mins / 60
        return when {
            hrs > 0 -> "${hrs}h ${mins % 60}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
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

            // Punches today
            val empPunches = allPunchesList.filter { p ->
                (p.staffId == emp.employeeId || p.staffId == empId.toString()) && isToday(p.timestamp)
            }.sortedBy { it.timestamp }

            val empAttendance = allAttendanceList.firstOrNull { a ->
                (a.employeeId == emp.employeeId || a.employeeId == empId.toString()) && isToday(a.checkInTime)
            }

            val punchIn = empPunches.firstOrNull { it.type.equals("IN", ignoreCase = true) }
            val punchOut = empPunches.lastOrNull { it.type.equals("OUT", ignoreCase = true) }
            val checkInTime = punchIn?.timestamp ?: empAttendance?.checkInTime
            val checkOutTime = punchOut?.timestamp ?: empAttendance?.checkOutTime

            val punchSummary = when {
                checkInTime != null && checkOutTime != null -> {
                    val inTimeStr = formatTimeOnly(checkInTime)
                    val outTimeStr = formatTimeOnly(checkOutTime)
                    "🔴 Punched Out: $outTimeStr (In: $inTimeStr)"
                }
                checkInTime != null -> {
                    val inTimeStr = formatTimeOnly(checkInTime)
                    val rangeNote = if (punchIn != null && officeLat != 0.0) {
                        val dist = calculateDistance(punchIn.latitude, punchIn.longitude, officeLat, officeLng)
                        if (dist <= officeRadiusMeters) " • Inside Range" else " • Outside Range (${dist.toInt()}m)"
                    } else ""
                    "🟢 Punch In: $inTimeStr$rangeNote"
                }
                else -> "⚪ No Punch In Recorded Today"
            }

            val hasPendingPunch = empPunches.any { it.syncState == 0 }
            val hasPendingGps = if (empId == sessionStore.employeeId()) (pendingGpsCount > 0) else false
            val hasPendingSync = hasPendingPunch || hasPendingGps

            val syncBadgeText = when {
                hasPendingSync -> "⏳ Pending Sync"
                status == "Live" -> "📡 Live Stream"
                else -> "✓ Synced"
            }

            val syncBadgeColor = when {
                hasPendingSync -> Color.parseColor("#E65100")
                status == "Live" -> Color.parseColor("#1565C0")
                else -> Color.parseColor("#2E7D32")
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
                isWithinRadius = live?.isWithinAllowedRadius ?: false,
                punchSummary = punchSummary,
                syncBadgeText = syncBadgeText,
                syncBadgeColor = syncBadgeColor,
                hasPendingSync = hasPendingSync,
                isSelected = (empId == selectedEmployeeId)
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

        // Apply Search and Status Filters
        val filtered = rows.filter { row ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                row.employeeName.contains(searchQuery, ignoreCase = true) ||
                        row.employeeId.toString().contains(searchQuery)
            }
            val matchesStatus = when (statusFilter) {
                "LIVE" -> row.status == "Live"
                "STALE" -> row.status == "Stale"
                "OFFLINE" -> row.status == "Offline"
                "PENDING_SYNC" -> row.hasPendingSync
                "SYNCED" -> !row.hasPendingSync
                else -> true
            }
            matchesSearch && matchesStatus
        }

        tvEmployeesCountBadge.text = if (filtered.size == activeEmployees.size) {
            "${activeEmployees.size} employees"
        } else {
            "Showing ${filtered.size} of ${activeEmployees.size} employees"
        }

        employeeStatusAdapter.submit(filtered)
        tvNoEmployeesRegistered.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        // Update selected employee inspection panel if active
        if (selectedEmployeeId > 0) {
            val selectedEmp = activeEmployees.firstOrNull { it.employeeId.toIntOrNull() == selectedEmployeeId }
            val selectedRow = rows.firstOrNull { it.employeeId == selectedEmployeeId }
            if (selectedEmp != null) {
                val empPunches = allPunchesList.filter { p ->
                    (p.staffId == selectedEmp.employeeId || p.staffId == selectedEmployeeId.toString()) && isToday(p.timestamp)
                }.sortedBy { it.timestamp }
                val empAttendance = allAttendanceList.firstOrNull { a ->
                    (a.employeeId == selectedEmp.employeeId || a.employeeId == selectedEmployeeId.toString()) && isToday(a.checkInTime)
                }
                val punchIn = empPunches.firstOrNull { it.type.equals("IN", ignoreCase = true) }
                val punchOut = empPunches.lastOrNull { it.type.equals("OUT", ignoreCase = true) }
                val checkIn = punchIn?.timestamp ?: empAttendance?.checkInTime
                val checkOut = punchOut?.timestamp ?: empAttendance?.checkOutTime

                updateSelectedEmployeeCard(selectedEmp, selectedRow, punchIn, punchOut, checkIn, checkOut)
            } else {
                cardSelectedEmployeeDetail.visibility = View.GONE
            }
        } else {
            cardSelectedEmployeeDetail.visibility = View.GONE
        }
    }

    private fun updateSelectedEmployeeCard(
        emp: Employee,
        row: EmployeeStatusRow?,
        punchIn: LocalAttendancePunch?,
        punchOut: LocalAttendancePunch?,
        checkInTime: Long?,
        checkOutTime: Long?
    ) {
        cardSelectedEmployeeDetail.visibility = View.VISIBLE
        tvSelectedEmpName.text = emp.name
        tvSelectedEmpId.text = "ID #${emp.employeeId} • ${emp.role.ifBlank { "Staff" }}"

        val status = row?.status ?: "Offline"
        tvSelectedEmpStatusBadge.text = status.uppercase()
        when (status) {
            "Live" -> {
                tvSelectedEmpStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                tvSelectedEmpStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
            }
            "Stale" -> {
                tvSelectedEmpStatusBadge.setTextColor(Color.parseColor("#F57F17"))
                tvSelectedEmpStatusBadge.setBackgroundColor(Color.parseColor("#FFF8E1"))
            }
            else -> {
                tvSelectedEmpStatusBadge.setTextColor(Color.parseColor("#C62828"))
                tvSelectedEmpStatusBadge.setBackgroundColor(Color.parseColor("#FFEBEE"))
            }
        }

        val hasPending = row?.hasPendingSync ?: false
        tvSelectedEmpSyncBadge.text = if (hasPending) "⏳ Pending Sync" else "✓ Synced"
        tvSelectedEmpSyncBadge.setTextColor(if (hasPending) Color.parseColor("#E65100") else Color.parseColor("#2E7D32"))
        tvSelectedEmpSyncBadge.setBackgroundColor(if (hasPending) Color.parseColor("#FFF3E0") else Color.parseColor("#E8F5E9"))

        // Section A: Attendance
        if (checkInTime != null) {
            tvSelectedPunchIn.text = "🟢 Punch In: ${formatIst(checkInTime)}"
            if (punchIn != null && punchIn.latitude != 0.0 && punchIn.longitude != 0.0) {
                val dist = if (officeLat != 0.0) calculateDistance(punchIn.latitude, punchIn.longitude, officeLat, officeLng) else 0.0
                val geoNote = if (officeLat != 0.0 && dist <= officeRadiusMeters) {
                    "Inside Office Radius (${dist.toInt()}m)"
                } else if (officeLat != 0.0) {
                    "Outside Office Radius (${dist.toInt()}m away)"
                } else "Coords recorded"
                tvSelectedPunchLocation.text = "Punch In Location: ${String.format(Locale.US, "%.5f, %.5f", punchIn.latitude, punchIn.longitude)} ($geoNote) • Source: ${punchIn.source}"
            } else {
                tvSelectedPunchLocation.text = "Punch In Location: Web / Biometric terminal punch"
            }
        } else {
            tvSelectedPunchIn.text = "🟢 Punch In: Not recorded today"
            tvSelectedPunchLocation.text = "Punch In Location: —"
        }

        if (checkOutTime != null) {
            tvSelectedPunchOut.text = "🔴 Punch Out: ${formatIst(checkOutTime)}"
            val durMs = (checkOutTime - (checkInTime ?: checkOutTime)).coerceAtLeast(0L)
            tvSelectedWorkHours.text = "Total Shift Completed: ${formatDuration(durMs)}"
        } else if (checkInTime != null) {
            tvSelectedPunchOut.text = "🔴 Punch Out: Currently Active / Working on duty"
            val durMs = (System.currentTimeMillis() - checkInTime).coerceAtLeast(0L)
            tvSelectedWorkHours.text = "Current Working Duration: ${formatDuration(durMs)} (Active Shift)"
        } else {
            tvSelectedPunchOut.text = "🔴 Punch Out: Not on duty"
            tvSelectedWorkHours.text = "Shift Duration: —"
        }

        // Section B: Live GPS
        if (row != null && (row.latitude != 0.0 || row.longitude != 0.0)) {
            tvSelectedLiveCoord.text = "📍 GPS: ${String.format(Locale.US, "%.6f, %.6f", row.latitude, row.longitude)}"
            tvSelectedLiveMovement.text = "Movement: ${row.movementState} • Speed: ${String.format(Locale.US, "%.1f", row.speedKmh)} km/h"
            val dist = if (officeLat != 0.0) calculateDistance(row.latitude, row.longitude, officeLat, officeLng) else 0.0
            if (officeLat != 0.0) {
                if (row.isWithinRadius) {
                    tvSelectedLiveGeofence.text = "🏢 Geofence: Inside allowed office zone (${dist.toInt()}m from center)"
                    tvSelectedLiveGeofence.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    tvSelectedLiveGeofence.text = "🏢 Geofence: Outside office zone (${dist.toInt()}m from center)"
                    tvSelectedLiveGeofence.setTextColor(Color.parseColor("#C62828"))
                }
            } else {
                tvSelectedLiveGeofence.text = "🏢 Geofence: ${if (row.isWithinRadius) "Inside Range" else "Outside Range"}"
            }
            tvSelectedLiveLastSeen.text = "Last Update: ${row.lastUpdatedUtc?.let { formatIst(it) } ?: "Never"} (${formatAge(row.ageSeconds)})"
        } else {
            tvSelectedLiveCoord.text = "📍 GPS: No live coordinates received today"
            tvSelectedLiveMovement.text = "Movement: Device inactive / offline"
            tvSelectedLiveGeofence.text = "🏢 Geofence: —"
            tvSelectedLiveLastSeen.text = "Last Update: Device Offline"
        }

        // Section C: Cloud & Offline Telemetry
        val cachedHistory = cloudHistoryCache[emp.employeeId.toIntOrNull() ?: 0]?.second ?: emptyList()
        tvSelectedCloudPoints.text = "☁️ Cloud Points Synced: ${cachedHistory.size} points synchronized to Firebase"
        tvSelectedPendingPoints.text = if (hasPending) "⏳ Local Queue: Offline data waiting to sync" else "✓ Local Queue: 0 points pending (Fully synchronized to Cloud)"
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
            pendingGpsCount = pending
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

            val empNameMap = activeEmployees.associate { (it.employeeId.toIntOrNull() ?: 0) to it.name }
            val allLocalPunches = try { punchDao.getAll() } catch (_: Exception) { emptyList<LocalAttendancePunch>() }
            val allPeriods = mutableListOf<OfflinePeriodItem>()

            if (selectedEmployeeId > 0) {
                val empId = selectedEmployeeId
                val empName = empNameMap[empId] ?: "Employee #$empId"
                val hist = getCachedOrFetchHistory(empId, 300)

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

                // If this is the current device's employee, also combine localRecent
                val combinedPoints = if (empId == sessionStore.employeeId()) {
                    (localConverted + localRecent).distinctBy { "${it.latitude}_${it.longitude}_${it.timestamp}" }
                } else {
                    localConverted
                }

                val empProfile = activeEmployees.firstOrNull { it.employeeId == empId.toString() }
                val empPeriods = buildOfflinePeriods(combinedPoints, localEvents, empId, empName, allLocalPunches, empProfile)
                allPeriods.addAll(empPeriods)

                val liveLoc = signalR.liveLocations.value[empId]
                val empPunches = allLocalPunches.filter { p ->
                    (p.staffId == empId.toString()) && isToday(p.timestamp)
                }.sortedBy { it.timestamp }

                val empAttendance = allAttendanceList.firstOrNull { a ->
                    (a.employeeId == empId.toString()) && isToday(a.checkInTime)
                }
                val punchIn = empPunches.firstOrNull { it.type.equals("IN", ignoreCase = true) }
                val punchOut = empPunches.lastOrNull { it.type.equals("OUT", ignoreCase = true) }
                val checkIn = punchIn?.timestamp ?: empAttendance?.checkInTime
                val checkOut = punchOut?.timestamp ?: empAttendance?.checkOutTime

                val timeline = buildJourneyTimeline(empName, punchIn, punchOut, checkIn, checkOut, hist, empPeriods, liveLoc)

                withContext(Dispatchers.Main) {
                    tvPeriodCountBadge.text = "${empPeriods.size} periods"
                    periodAdapter.submit(empPeriods)
                    tvNoOfflinePeriods.visibility = if (empPeriods.isEmpty()) View.VISIBLE else View.GONE

                    tvSelectedCloudPoints.text = "☁️ Cloud Points Synced: ${hist.size} points synchronized to Firebase"
                    tvSelectedOfflineIntervalsSummary.text = if (empPeriods.isNotEmpty()) {
                        "📴 Offline Intervals: ${empPeriods.size} period(s) detected • All reconciled"
                    } else {
                        "📴 Offline Intervals: None detected today. Realtime continuous stream."
                    }

                    // Draw map
                    drawSelectedEmployeeMap(empName, punchIn, punchOut, hist, empPeriods, liveLoc)

                    // Update timeline
                    cardJourneyTimeline.visibility = View.VISIBLE
                    tvJourneyTimelineCount.text = "${timeline.size} events"
                    journeyTimelineAdapter.submit(timeline)
                    tvNoTimelineEvents.visibility = if (timeline.isEmpty()) View.VISIBLE else View.GONE
                }
            } else {
                // All Employees Overview
                val myProfile = activeEmployees.firstOrNull { it.employeeId == sessionStore.employeeId().toString() }
                val localPeriods = buildOfflinePeriods(localRecent, localEvents, sessionStore.employeeId(), empNameMap[sessionStore.employeeId()] ?: "This Device", allLocalPunches, myProfile)
                allPeriods.addAll(localPeriods)

                val sortedPeriods = allPeriods.distinctBy { it.id }.sortedByDescending { it.startTime }

                withContext(Dispatchers.Main) {
                    tvPeriodCountBadge.text = "${sortedPeriods.size} periods"
                    periodAdapter.submit(sortedPeriods)
                    tvNoOfflinePeriods.visibility = if (sortedPeriods.isEmpty()) View.VISIBLE else View.GONE

                    drawAllEmployeesMap()

                    cardJourneyTimeline.visibility = View.VISIBLE
                    tvJourneyTimelineCount.text = "All Employees"
                    journeyTimelineAdapter.submit(emptyList())
                    tvNoTimelineEvents.visibility = View.VISIBLE
                    tvNoTimelineEvents.text = "Tap any employee above to view their chronological punch, tracking and sync timeline."
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

                if (history.isNotEmpty() && selectedEmployeeId == empId) {
                    refreshCloudAndLocal()
                }
            }
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
        val clusters = mutableListOf<MutableList<LocalLocation>>()
        var curCluster = mutableListOf<LocalLocation>()

        for (i in sorted.indices) {
            val loc = sorted[i]
            val last = curCluster.lastOrNull()

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

            // Shift vs 24/7 evaluation
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

            // Attendance state just before this gap
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
                        "Live Attendance Updated: $punchDetails • Successfully merged into live ledger"
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
                    isOffShift = isOffShift,
                    shiftTag = shiftTag,
                    liveImpactSummary = liveImpact,
                    priorState = priorState
                )
            )
        }

        return result
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

    private fun buildJourneyTimeline(
        empName: String,
        punchIn: LocalAttendancePunch?,
        punchOut: LocalAttendancePunch?,
        checkInTime: Long?,
        checkOutTime: Long?,
        history: List<SignalRManager.LiveLocation>,
        offlinePeriods: List<OfflinePeriodItem>,
        live: SignalRManager.LiveLocation?
    ): List<TimelineStepItem> {
        val steps = mutableListOf<TimelineStepItem>()

        // 1. Punch In
        if (checkInTime != null) {
            val latLngStr = if (punchIn != null && punchIn.latitude != 0.0) {
                String.format(Locale.US, "Coords: %.5f, %.5f", punchIn.latitude, punchIn.longitude)
            } else "Biometric / Web Punch"
            val distStr = if (punchIn != null && officeLat != 0.0) {
                val d = calculateDistance(punchIn.latitude, punchIn.longitude, officeLat, officeLng)
                if (d <= officeRadiusMeters) " • Inside Office Radius (${d.toInt()}m)" else " • Outside Radius (${d.toInt()}m)"
            } else ""
            val sourceStr = punchIn?.source?.let { " • Source: $it" } ?: ""
            val syncLabel = if (punchIn?.syncState == 1) "✓ Synced to Cloud & Web" else "⏳ Stored Locally (Pending Sync)"

            steps.add(
                TimelineStepItem(
                    time = checkInTime,
                    icon = "🟢",
                    badge = "PUNCH IN",
                    title = "Punched IN at Work",
                    description = "$latLngStr$distStr$sourceStr",
                    syncStatus = syncLabel,
                    isSuccess = true
                )
            )
        }

        // 2. Offline Periods
        for (period in offlinePeriods) {
            val distText = if (period.distanceMeters >= 1000) String.format(Locale.US, "%.1f km", period.distanceMeters / 1000.0) else "${period.distanceMeters.toInt()}m"
            val syncLabel = if (period.isSynced) "✓ Reconciled & Synced to Cloud" else "⏳ Queued in Local Memory"
            val badgeLabel = if (period.isOffShift) "OFF-DUTY GAP" else "OFFLINE GAP"
            val punchSummary = if (period.punches.isNotEmpty()) {
                val pStr = period.punches.joinToString(", ") { p -> "${p.type.uppercase()} at ${formatTimeOnly(p.timestamp)} (${p.changeDetail})" }
                "Punches: $pStr • "
            } else ""
            steps.add(
                TimelineStepItem(
                    time = period.startTime,
                    icon = if (period.reason.contains("airplane", true)) "✈️" else "📴",
                    badge = badgeLabel,
                    title = "Offline Disconnection: ${period.reason} [${period.shiftTag}]",
                    description = "${punchSummary}Duration: ${formatDuration(period.durationMs)} • ${period.pointsCount} points captured offline ($distText traveled)\n${period.liveImpactSummary}",
                    syncStatus = syncLabel,
                    isSuccess = false
                )
            )
            if (period.isSynced) {
                steps.add(
                    TimelineStepItem(
                        time = period.endTime,
                        icon = "🔄",
                        badge = "RECONCILED",
                        title = "Connection Restored & Reconciled",
                        description = "Network returned at ${formatTimeOnly(period.endTime)} • ${period.pointsCount} offline points merged into Live\n${period.liveImpactSummary}",
                        syncStatus = "✓ Integrated into Live Route",
                        isSuccess = true
                    )
                )
            }
        }

        // 3. Historical Route Breadcrumbs
        if (history.isNotEmpty()) {
            val firstH = history.first()
            val lastH = history.last()
            val tFirst = parseTrackingTimestamp(firstH.timestamp)
            val tLast = parseTrackingTimestamp(lastH.timestamp)
            val maxSpeed = (history.maxOfOrNull { it.speedMps } ?: 0.0) * 3.6
            steps.add(
                TimelineStepItem(
                    time = if (tFirst > 0L) tFirst else (checkInTime ?: System.currentTimeMillis()),
                    icon = "📍",
                    badge = "ROUTE",
                    title = "GPS Route Streaming Active",
                    description = "${history.size} points recorded (${formatTimeOnly(tFirst)} → ${formatTimeOnly(tLast)}) • Peak Speed: ${String.format(Locale.US, "%.1f", maxSpeed)} km/h",
                    syncStatus = "✓ Continuously Synced to Cloud",
                    isSuccess = true
                )
            )
        }

        // 4. Punch Out
        if (checkOutTime != null) {
            val latLngStr = if (punchOut != null && punchOut.latitude != 0.0) {
                String.format(Locale.US, "Coords: %.5f, %.5f", punchOut.latitude, punchOut.longitude)
            } else "Biometric / Web Punch"
            val durMs = (checkOutTime - (checkInTime ?: checkOutTime)).coerceAtLeast(0L)
            val syncLabel = if (punchOut?.syncState == 1) "✓ Synced to Cloud & Web" else "⏳ Stored Locally (Pending Sync)"

            steps.add(
                TimelineStepItem(
                    time = checkOutTime,
                    icon = "🔴",
                    badge = "PUNCH OUT",
                    title = "Punched OUT from Work",
                    description = "$latLngStr • Total Shift: ${formatDuration(durMs)}",
                    syncStatus = syncLabel,
                    isSuccess = true
                )
            )
        }

        // 5. Live Now
        if (live != null && live.timestamp != null) {
            val tLive = parseTrackingTimestamp(live.timestamp)
            val distStr = if (officeLat != 0.0 && live.latitude != 0.0) {
                val d = calculateDistance(live.latitude, live.longitude, officeLat, officeLng)
                if (d <= officeRadiusMeters) " • Inside Office Range (${d.toInt()}m)" else " • Outside Range (${d.toInt()}m)"
            } else ""
            steps.add(
                TimelineStepItem(
                    time = if (tLive > 0L) tLive else System.currentTimeMillis(),
                    icon = "🎯",
                    badge = "LIVE NOW",
                    title = "Current Live Position",
                    description = "${String.format(Locale.US, "%.5f, %.5f", live.latitude, live.longitude)}$distStr • Speed: ${String.format(Locale.US, "%.1f", live.speedMps * 3.6)} km/h (${live.movementState})",
                    syncStatus = "📡 Streaming Live via SignalR/Firebase",
                    isSuccess = true
                )
            )
        }

        return steps.sortedBy { it.time }
    }

    private fun drawSelectedEmployeeMap(
        empName: String,
        punchIn: LocalAttendancePunch?,
        punchOut: LocalAttendancePunch?,
        historyPoints: List<SignalRManager.LiveLocation>,
        offlinePeriods: List<OfflinePeriodItem>,
        liveLoc: SignalRManager.LiveLocation?
    ) {
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

        // 2. Punch In Marker
        if (punchIn != null && punchIn.latitude != 0.0 && punchIn.longitude != 0.0) {
            val punchInGeo = GeoPoint(punchIn.latitude, punchIn.longitude)
            val dist = if (officeLat != 0.0) calculateDistance(punchIn.latitude, punchIn.longitude, officeLat, officeLng) else 0.0
            val geoLabel = if (officeLat != 0.0 && dist <= officeRadiusMeters) "Inside Range" else "Outside Range"
            Marker(mapView).apply {
                position = punchInGeo
                title = "🟢 Punch In: $empName"
                snippet = "${formatTimeOnly(punchIn.timestamp)} • $geoLabel (${dist.toInt()}m from office)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
            allGeoPoints.add(punchInGeo)
        }

        // 3. Historical Route Polyline
        val validHist = historyPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (validHist.isNotEmpty()) {
            val histGeo = validHist.map { GeoPoint(it.latitude, it.longitude) }
            val polyline = Polyline().apply {
                setPoints(histGeo)
                outlinePaint.color = Color.parseColor("#1565C0")
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(polyline)
            allGeoPoints.addAll(histGeo)
        }

        // 4. Offline Periods Polylines & Reconnect Markers
        for (period in offlinePeriods) {
            val validPeriodPoints = period.points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            if (validPeriodPoints.isNotEmpty()) {
                val periodGeo = validPeriodPoints.map { GeoPoint(it.latitude, it.longitude) }
                val offlinePoly = Polyline().apply {
                    setPoints(periodGeo)
                    outlinePaint.color = Color.parseColor("#E65100")
                    outlinePaint.strokeWidth = 10f
                }
                mapView.overlays.add(offlinePoly)
                allGeoPoints.addAll(periodGeo)

                val reconnectPoint = periodGeo.last()
                Marker(mapView).apply {
                    position = reconnectPoint
                    title = "🟠 Reconnected: ${formatTimeOnly(period.endTime)}"
                    snippet = "${period.reason} • ${period.pointsCount} offline points"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    mapView.overlays.add(this)
                }
            }
        }

        // 5. Punch Out Marker
        if (punchOut != null && punchOut.latitude != 0.0 && punchOut.longitude != 0.0) {
            val punchOutGeo = GeoPoint(punchOut.latitude, punchOut.longitude)
            Marker(mapView).apply {
                position = punchOutGeo
                title = "🔴 Punch Out: $empName"
                snippet = "Time: ${formatTimeOnly(punchOut.timestamp)}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
            allGeoPoints.add(punchOutGeo)
        }

        // 6. Live Current Location Marker
        if (liveLoc != null && liveLoc.latitude != 0.0 && liveLoc.longitude != 0.0) {
            val liveGeo = GeoPoint(liveLoc.latitude, liveLoc.longitude)
            Marker(mapView).apply {
                position = liveGeo
                title = "🎯 Live Location: $empName"
                snippet = "${liveLoc.movementState} • ${String.format(Locale.US, "%.1f", liveLoc.speedMps * 3.6)} km/h • ${if (liveLoc.isWithinAllowedRadius) "Inside Range" else "Outside Range"}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
            allGeoPoints.add(liveGeo)
        }

        // 7. Zoom & Banner
        if (allGeoPoints.isNotEmpty()) {
            tvMapEmptyNotice.visibility = View.GONE
            tvMapRouteBanner.text = "Showing route & punches for $empName (${allGeoPoints.size} waypoints plotted)"
            zoomToFit(allGeoPoints)
        } else {
            tvMapEmptyNotice.visibility = View.VISIBLE
            tvMapEmptyNotice.text = "No GPS tracking breadcrumbs or punch coordinates recorded today for $empName"
            tvMapRouteBanner.text = "No route points recorded today for $empName"
            if (officeLat != 0.0) {
                mapView.controller.setCenter(GeoPoint(officeLat, officeLng))
                mapView.controller.setZoom(16.0)
            }
            mapView.invalidate()
        }
    }

    private fun drawAllEmployeesMap() {
        mapView.overlays.clear()
        val liveMap = signalR.liveLocations.value
        val allGeo = mutableListOf<GeoPoint>()

        if (officeLat != 0.0 && officeLng != 0.0) {
            val officeGeo = GeoPoint(officeLat, officeLng)
            Marker(mapView).apply {
                position = officeGeo
                title = "🏢 Office Location"
                snippet = "Geofence Radius: ${officeRadiusMeters}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
            allGeo.add(officeGeo)
        }

        for (emp in activeEmployees) {
            val eid = emp.employeeId.toIntOrNull() ?: continue
            val live = liveMap[eid] ?: continue
            if (live.latitude != 0.0 && live.longitude != 0.0) {
                val geo = GeoPoint(live.latitude, live.longitude)
                Marker(mapView).apply {
                    position = geo
                    title = "${emp.name} (#$eid)"
                    snippet = "${live.movementState} • ${String.format(Locale.US, "%.1f", live.speedMps * 3.6)} km/h • ${if (live.isWithinAllowedRadius) "Inside Range" else "Outside Range"}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        selectEmployee(eid)
                        true
                    }
                    mapView.overlays.add(this)
                }
                allGeo.add(geo)
            }
        }

        if (allGeo.isNotEmpty()) {
            tvMapEmptyNotice.visibility = View.GONE
            val empCount = allGeo.size - (if (officeLat != 0.0) 1 else 0)
            tvMapRouteBanner.text = "All Employees Overview • $empCount employee(s) active on live map"
            zoomToFit(allGeo)
        } else {
            tvMapEmptyNotice.visibility = View.VISIBLE
            tvMapEmptyNotice.text = "No live employee GPS positions received today."
            tvMapRouteBanner.text = "All Employees Overview"
            if (officeLat != 0.0) {
                mapView.controller.setCenter(GeoPoint(officeLat, officeLng))
                mapView.controller.setZoom(15.0)
            }
            mapView.invalidate()
        }
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

    private fun parseTrackingTimestamp(str: String?): Long = signalR.parseTrackingTimestamp(str)

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
    val isWithinRadius: Boolean,
    val punchSummary: String = "⚪ No Punch Today",
    val syncBadgeText: String = "✓ Synced",
    val syncBadgeColor: Int = Color.parseColor("#2E7D32"),
    val hasPendingSync: Boolean = false,
    val isSelected: Boolean = false
)

data class OfflinePunchInfo(
    val type: String,
    val timestamp: Long,
    val isSynced: Boolean = true,
    val isOutside: Boolean = false,
    val changeDetail: String = ""
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
    val points: List<LocalLocation>,
    val punches: List<OfflinePunchInfo> = emptyList(),
    val isOffShift: Boolean = false,
    val shiftTag: String = "Active Shift Disconnection",
    val liveImpactSummary: String = "",
    val priorState: String = "OUT"
)

data class TimelineStepItem(
    val time: Long,
    val icon: String,
    val badge: String,
    val title: String,
    val description: String,
    val syncStatus: String,
    val isSuccess: Boolean
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
        private val tvSyncBadge = view.findViewById<TextView>(R.id.tvSyncBadge)
        private val tvStatusBadge = view.findViewById<TextView>(R.id.tvStatusBadge)
        private val tvAttendancePunch = view.findViewById<TextView>(R.id.tvAttendancePunch)
        private val tvSelectHint = view.findViewById<TextView>(R.id.tvSelectHint)
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

            // Sync Badge
            tvSyncBadge.text = item.syncBadgeText
            tvSyncBadge.setTextColor(item.syncBadgeColor)
            tvSyncBadge.setBackgroundColor(if (item.hasPendingSync) Color.parseColor("#FFF3E0") else Color.parseColor("#E8F5E9"))

            // Attendance Punch Today
            tvAttendancePunch.text = item.punchSummary
            tvAttendancePunch.setTextColor(when {
                item.punchSummary.startsWith("🟢") -> Color.parseColor("#2E7D32")
                item.punchSummary.startsWith("🔴") -> Color.parseColor("#C62828")
                else -> Color.parseColor("#757575")
            })

            // Card Selection Highlight
            if (item.isSelected) {
                card.strokeColor = Color.parseColor("#1565C0")
                card.strokeWidth = 4
                card.setCardBackgroundColor(Color.parseColor("#F0F7FF"))
                tvSelectHint.text = "Currently Inspecting ✓"
                tvSelectHint.setTextColor(Color.parseColor("#1565C0"))
            } else {
                card.strokeColor = Color.parseColor("#E0E0E0")
                card.strokeWidth = 2
                card.setCardBackgroundColor(Color.WHITE)
                tvSelectHint.text = "Tap to inspect ➔"
                tvSelectHint.setTextColor(Color.parseColor("#1976D2"))
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

private class JourneyTimelineAdapter : RecyclerView.Adapter<JourneyTimelineAdapter.Holder>() {
    private var items = listOf<TimelineStepItem>()

    fun submit(newItems: List<TimelineStepItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_journey_timeline_step, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], isLast = (position == items.size - 1))
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvIcon = view.findViewById<TextView>(R.id.tvTimelineIcon)
        private val viewConnector = view.findViewById<View>(R.id.viewTimelineConnector)
        private val tvTime = view.findViewById<TextView>(R.id.tvTimelineTime)
        private val tvBadge = view.findViewById<TextView>(R.id.tvTimelineBadge)
        private val tvTitle = view.findViewById<TextView>(R.id.tvTimelineTitle)
        private val tvDesc = view.findViewById<TextView>(R.id.tvTimelineDesc)
        private val tvSyncStatus = view.findViewById<TextView>(R.id.tvTimelineSyncStatus)

        fun bind(item: TimelineStepItem, isLast: Boolean) {
            tvIcon.text = item.icon
            viewConnector.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = tz }
            tvTime.text = "${fmt.format(Date(item.time))} IST"

            tvBadge.text = item.badge
            when (item.badge) {
                "PUNCH IN" -> {
                    tvBadge.setTextColor(Color.parseColor("#2E7D32"))
                    tvBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                "PUNCH OUT" -> {
                    tvBadge.setTextColor(Color.parseColor("#C62828"))
                    tvBadge.setBackgroundColor(Color.parseColor("#FFEBEE"))
                }
                "LIVE NOW" -> {
                    tvBadge.setTextColor(Color.parseColor("#00838F"))
                    tvBadge.setBackgroundColor(Color.parseColor("#E0F7FA"))
                }
                "OFFLINE GAP" -> {
                    tvBadge.setTextColor(Color.parseColor("#E65100"))
                    tvBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                }
                "RECONCILED" -> {
                    tvBadge.setTextColor(Color.parseColor("#1565C0"))
                    tvBadge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                }
                else -> {
                    tvBadge.setTextColor(Color.parseColor("#1565C0"))
                    tvBadge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                }
            }

            tvTitle.text = item.title
            tvDesc.text = item.description
            tvSyncStatus.text = item.syncStatus
            tvSyncStatus.setTextColor(if (item.isSuccess) Color.parseColor("#2E7D32") else Color.parseColor("#E65100"))
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
        private val tvShiftTag = view.findViewById<TextView>(R.id.tvPeriodShiftTag)
        private val tvTitle = view.findViewById<TextView>(R.id.tvPeriodTitle)
        private val tvWindow = view.findViewById<TextView>(R.id.tvPeriodWindow)
        private val tvDuration = view.findViewById<TextView>(R.id.tvPeriodDuration)
        private val tvReason = view.findViewById<TextView>(R.id.tvPeriodReason)
        private val tvSyncStatus = view.findViewById<TextView>(R.id.tvPeriodSyncStatus)
        private val tvPunches = view.findViewById<TextView>(R.id.tvPeriodPunches)
        private val tvLiveImpact = view.findViewById<TextView>(R.id.tvPeriodLiveImpact)
        private val tvStats = view.findViewById<TextView>(R.id.tvPeriodStats)
        private val btnInspect = view.findViewById<MaterialButton>(R.id.btnViewPeriodRoute)

        fun bind(item: OfflinePeriodItem, onInspect: (OfflinePeriodItem) -> Unit) {
            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd-MMM HH:mm:ss", Locale.US).apply { timeZone = tz }
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = tz }
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

            // Punches taken during the offline period:
            if (item.punches.isNotEmpty()) {
                val punchText = item.punches.joinToString("\n") { p ->
                    val icon = if (p.type.equals("OUT", ignoreCase = true)) "🔴" else "🟢"
                    val syncLabel = if (p.isSynced) "Synced to Live" else "Pending Local"
                    val detail = if (p.changeDetail.isNotBlank()) " • ${p.changeDetail}" else ""
                    "$icon ${p.type.uppercase()} Punch at ${punchFmt.format(Date(p.timestamp))} ($syncLabel)$detail"
                }
                tvPunches.text = punchText
                tvPunches.setTextColor(Color.parseColor("#1B5E20"))
            } else {
                tvPunches.text = if (item.isOffShift) {
                    "Off-Duty Window • Tracking paused (No punches taken)"
                } else {
                    "No attendance punches taken during this gap (Maintained previous state: ${item.priorState})"
                }
                tvPunches.setTextColor(Color.parseColor("#757575"))
            }

            // How gap was reconciled to live data:
            tvLiveImpact.text = item.liveImpactSummary.ifBlank {
                if (item.isSynced) {
                    val punchNote = if (item.punches.isNotEmpty()) "${item.punches.size} punch(es) reconciled & " else ""
                    "Live Data Impact: ${punchNote}${item.pointsCount} offline breadcrumbs uploaded to cloud upon reconnection"
                } else {
                    "Live Data Impact: Pending sync - ${item.pointsCount} breadcrumbs and punches stored in local queue"
                }
            }
            tvLiveImpact.setTextColor(if (item.punches.isNotEmpty()) Color.parseColor("#1565C0") else Color.parseColor("#424242"))

            val distText = if (item.distanceMeters >= 1000) {
                String.format(Locale.US, "%.2f km", item.distanceMeters / 1000.0)
            } else {
                String.format(Locale.US, "%.0f m", item.distanceMeters)
            }

            tvStats.text = "${item.pointsCount} points captured • Distance: $distText • Within geofence: ${item.inRadiusCount} / Outside: ${item.pointsCount - item.inRadiusCount}"

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
