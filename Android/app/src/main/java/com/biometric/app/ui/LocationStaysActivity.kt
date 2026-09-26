package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.Shop
import com.biometric.app.sync.SignalRManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.*
import com.biometric.app.data.dao.LocalSettingsDao

@AndroidEntryPoint
class LocationStaysActivity : MotionBaseActivity() {

    @Inject lateinit var repo: MainRepository
    @Inject lateinit var signal: SignalRManager
    @Inject lateinit var localSettingsDao: LocalSettingsDao

    private lateinit var mapView: MapView
    private lateinit var spnEmployee: Spinner
    private lateinit var spnMinDuration: Spinner
    private lateinit var btnDateFrom: MaterialButton
    private lateinit var btnDateTo: MaterialButton
    private lateinit var btnTimeFrom: MaterialButton
    private lateinit var btnTimeTo: MaterialButton
    private lateinit var btnSearchRadar: MaterialButton

    private lateinit var tvMetricEmployees: TextView
    private lateinit var tvMetricStays: TextView
    private lateinit var tvMetricTotalStay: TextView
    private lateinit var tvMetricPoints: TextView
    private lateinit var tvMapPointsBadge: TextView
    private lateinit var tvStaysCountBadge: TextView
    private lateinit var rvStays: RecyclerView
    private lateinit var tvNoStays: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var stayAdapter: LocationStayAdapter

    private var activeEmployees: List<Employee> = emptyList()
    private var allShops: List<Shop> = emptyList()
    private var allDetectedStays: List<LocationStayItem> = emptyList()
    private var currentFilteredPoints: List<SignalRManager.LiveLocation> = emptyList()
    private var minDurationMinutes: Int = 10
    private var configuredDwellMinutes: Int = 10
    private var configuredClusterRadiusMeters: Int = 50

    private val addressCache = ConcurrentHashMap<String, String>()

    private val durationFilterOptions = listOf(
        "≥ 2 mins" to 2,
        "≥ 5 mins" to 5,
        "≥ 10 mins (Default)" to 10,
        "≥ 30 mins" to 30,
        "≥ 1 hour" to 60,
        "≥ 2 hours" to 120,
        "≥ 5 hours" to 300,
        "≥ 12 hours" to 720,
        "≥ 24 hours" to 1440
    )

    private val calFrom = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val calTo = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    private var stayMarkers = mutableListOf<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_stays)
        applyWindowInsets(findViewById(R.id.clLocationStaysRoot), findViewById(R.id.appBar))

        initViews()
        setupMap()
        setupAdapters()
        setupPickers()
        setupDurationSpinner()
        observeData()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        spnEmployee = findViewById(R.id.spnEmployee)
        spnMinDuration = findViewById(R.id.spnMinDuration)
        btnDateFrom = findViewById(R.id.btnDateFrom)
        btnDateTo = findViewById(R.id.btnDateTo)
        btnTimeFrom = findViewById(R.id.btnTimeFrom)
        btnTimeTo = findViewById(R.id.btnTimeTo)
        btnSearchRadar = findViewById(R.id.btnSearchRadar)

        tvMetricEmployees = findViewById(R.id.tvMetricEmployees)
        tvMetricStays = findViewById(R.id.tvMetricStays)
        tvMetricTotalStay = findViewById(R.id.tvMetricTotalStay)
        tvMetricPoints = findViewById(R.id.tvMetricPoints)
        tvMapPointsBadge = findViewById(R.id.tvMapPointsBadge)
        tvStaysCountBadge = findViewById(R.id.tvStaysCountBadge)

        mapView = findViewById(R.id.historyMapView)
        rvStays = findViewById(R.id.rvStays)
        tvNoStays = findViewById(R.id.tvNoStays)
        progressBar = findViewById(R.id.progressBarStays)

        updateDateButtons()
        updateTimeButtons()

        btnSearchRadar.setOnClickListener {
            loadAndAnalyze()
        }
    }

    private fun setupMap() {
        OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200 * 1024 * 1024L
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

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
        stayAdapter = LocationStayAdapter(
            scope = lifecycleScope,
            reverseGeocode = ::reverseGeocodeStay,
            getDwellMinutes = { configuredDwellMinutes }
        ) { stay ->
            focusOnStay(stay)
        }
        rvStays.apply {
            layoutManager = LinearLayoutManager(this@LocationStaysActivity)
            adapter = stayAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupPickers() {
        btnDateFrom.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calFrom.set(Calendar.YEAR, year)
                    calFrom.set(Calendar.MONTH, month)
                    calFrom.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    updateDateButtons()
                },
                calFrom.get(Calendar.YEAR),
                calFrom.get(Calendar.MONTH),
                calFrom.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnDateTo.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calTo.set(Calendar.YEAR, year)
                    calTo.set(Calendar.MONTH, month)
                    calTo.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    updateDateButtons()
                },
                calTo.get(Calendar.YEAR),
                calTo.get(Calendar.MONTH),
                calTo.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnTimeFrom.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    calFrom.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calFrom.set(Calendar.MINUTE, minute)
                    updateTimeButtons()
                },
                calFrom.get(Calendar.HOUR_OF_DAY),
                calFrom.get(Calendar.MINUTE),
                true
            ).show()
        }

        btnTimeTo.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    calTo.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calTo.set(Calendar.MINUTE, minute)
                    updateTimeButtons()
                },
                calTo.get(Calendar.HOUR_OF_DAY),
                calTo.get(Calendar.MINUTE),
                true
            ).show()
        }
    }

    private fun updateDateButtons() {
        val df = SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        btnDateFrom.text = df.format(calFrom.time)
        btnDateTo.text = df.format(calTo.time)
    }

    private fun updateTimeButtons() {
        val tf = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        btnTimeFrom.text = tf.format(calFrom.time)
        btnTimeTo.text = tf.format(calTo.time)
    }

    private fun observeData() {
        lifecycleScope.launch {
            repo.allEmployeesFlow.collect { list ->
                activeEmployees = list.filter { it.isActive }
                updateEmployeeSpinner()
            }
        }

        lifecycleScope.launch {
            repo.allShopsFlow.collect { shops ->
                allShops = shops
            }
        }

        lifecycleScope.launch {
            val cs = withContext(Dispatchers.IO) { localSettingsDao.getCompanySettings() }
            if (cs != null) {
                configuredDwellMinutes = if (cs.stayDwellMinutes > 0) cs.stayDwellMinutes else 10
                configuredClusterRadiusMeters = if (cs.stayClusterRadiusMeters > 0) cs.stayClusterRadiusMeters else 50
                minDurationMinutes = configuredDwellMinutes
                updateDurationSpinnerSelection()
            }
        }
    }

    private fun updateEmployeeSpinner() {
        val options = mutableListOf("All employees")
        options.addAll(activeEmployees.map { "${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spnEmployee.adapter = adapter
    }

    private fun setupDurationSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, durationFilterOptions.map { it.first })
        spnMinDuration.adapter = adapter
        updateDurationSpinnerSelection()
        spnMinDuration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                minDurationMinutes = durationFilterOptions.getOrNull(position)?.second ?: configuredDwellMinutes
                applyStayFilters()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateDurationSpinnerSelection() {
        val idx = durationFilterOptions.indexOfFirst { it.second == minDurationMinutes }
        if (idx >= 0 && spnMinDuration.selectedItemPosition != idx) {
            spnMinDuration.setSelection(idx)
        }
    }

    private fun applyStayFilters() {
        val minMs = minDurationMinutes * 60 * 1000L
        val filteredStays = allDetectedStays.filter { it.durationMs >= minMs }
        val totalStayMs = filteredStays.sumOf { it.durationMs }
        val distinctEmployeesCount = filteredStays.map { it.employeeId }.distinct().size

        tvMetricEmployees.text = distinctEmployeesCount.toString()
        tvMetricStays.text = filteredStays.size.toString()
        tvMetricTotalStay.text = formatDuration(totalStayMs)
        tvMetricPoints.text = currentFilteredPoints.size.toString()
        tvMapPointsBadge.text = "${currentFilteredPoints.size} points"
        tvStaysCountBadge.text = "${filteredStays.size} stays (≥${minDurationMinutes}m)"

        stayAdapter.submit(filteredStays)
        tvNoStays.visibility = if (filteredStays.isEmpty()) View.VISIBLE else View.GONE

        renderMap(currentFilteredPoints, filteredStays)
    }

    private fun loadAndAnalyze() {
        val fromEpoch = calFrom.timeInMillis
        val toEpoch = calTo.timeInMillis

        if (toEpoch < fromEpoch) {
            Toast.makeText(this, "To date/time must be after From date/time", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvNoStays.visibility = View.GONE
        btnSearchRadar.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val selectedIndex = withContext(Dispatchers.Main) { spnEmployee.selectedItemPosition }
            val empIdsToQuery = if (selectedIndex > 0) {
                val emp = activeEmployees.getOrNull(selectedIndex - 1)
                listOfNotNull(emp?.employeeId?.toIntOrNull())
            } else {
                activeEmployees.mapNotNull { it.employeeId.toIntOrNull() }
            }

            val empNameMap = activeEmployees.associate { (it.employeeId.toIntOrNull() ?: 0) to it.name }

            val rawPoints = mutableListOf<SignalRManager.LiveLocation>()
            val queryLimit = if (selectedIndex > 0) 1000 else 200
            val deferred = empIdsToQuery.take(15).map { eid ->
                async {
                    signal.loadTrackingHistory(eid, queryLimit)
                }
            }
            val results = deferred.awaitAll()
            results.forEach { rawPoints.addAll(it) }

            // Filter points within selected datetime range
            val filteredPoints = rawPoints.filter { pt ->
                val ep = parseTrackingTimestamp(pt.timestamp)
                ep in fromEpoch..toEpoch
            }.sortedBy { parseTrackingTimestamp(it.timestamp) }

            val detectedStays = detectStays(filteredPoints, empNameMap)
            enrichShopMatches(detectedStays, allShops)

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnSearchRadar.isEnabled = true

                currentFilteredPoints = filteredPoints
                allDetectedStays = detectedStays

                applyStayFilters()
            }
        }
    }

    /**
     * Stay detection engine:
     * Points clustered within 50m radius for ≥ 10 minutes.
     */
    private fun detectStays(
        points: List<SignalRManager.LiveLocation>,
        empNameMap: Map<Int, String>
    ): List<LocationStayItem> {
        val result = mutableListOf<LocationStayItem>()
        var nextId = 1L

        val sessionGroups = points
            .filter { it.sessionId.isNotBlank() }
            .groupBy { "${it.employeeId}_${it.sessionId}" }

        for ((_, group) in sessionGroups) {
            val ordered = group.sortedBy { parseTrackingTimestamp(it.timestamp) }
            val run = mutableListOf<SignalRManager.LiveLocation>()
            var anchor: SignalRManager.LiveLocation? = null

            fun finishRun() {
                if (run.size < 2 || anchor == null) {
                    run.clear()
                    anchor = null
                    return
                }

                val startEpoch = parseTrackingTimestamp(run.first().timestamp)
                val endEpoch = parseTrackingTimestamp(run.last().timestamp)
                val durationMs = endEpoch - startEpoch

                // Minimum stay requirement from configured company settings
                val minStayMs = configuredDwellMinutes * 60 * 1000L
                if (durationMs >= minStayMs) {
                    val eid = run.first().employeeId
                    val empName = empNameMap[eid] ?: "Employee #$eid"

                    val avgLat = run.map { it.latitude }.average()
                    val avgLon = run.map { it.longitude }.average()
                    val avgAcc = run.map { it.accuracyMeters }.filter { it > 0 }.average().let {
                        if (it.isNaN()) 0.0 else it
                    }

                    val firstIdx = ordered.indexOf(run.first())
                    val lastIdx = ordered.indexOf(run.last())
                    val previous = if (firstIdx > 0) ordered[firstIdx - 1] else null
                    val next = if (lastIdx + 1 < ordered.size) ordered[lastIdx + 1] else null

                    val arrSpeedKmh = calculateSpeedKmh(previous, run.first())
                    val depSpeedKmh = calculateSpeedKmh(run.last(), next)

                    result.add(
                        LocationStayItem(
                            id = nextId++,
                            sequence = 0,
                            employeeId = eid,
                            employeeName = empName,
                            sessionId = run.first().sessionId,
                            startEpoch = startEpoch,
                            endEpoch = endEpoch,
                            durationMs = durationMs,
                            latitude = avgLat,
                            longitude = avgLon,
                            accuracyMeters = avgAcc,
                            pointCount = run.size,
                            matchedLocation = "External Location / Field Halt",
                            placeCategory = "Field Halt",
                            distanceFromMatchedLocationMeters = 0.0,
                            radiusMeters = configuredClusterRadiusMeters,
                            arrivalSpeedKmh = arrSpeedKmh,
                            departureSpeedKmh = depSpeedKmh
                        )
                    )
                }

                run.clear()
                anchor = null
            }

            for (point in ordered) {
                val curAnchor = anchor
                if (curAnchor == null) {
                    anchor = point
                    run.add(point)
                    continue
                }

                val dist = calculateDistance(curAnchor.latitude, curAnchor.longitude, point.latitude, point.longitude)
                // Cluster radius from company settings
                if (dist <= configuredClusterRadiusMeters.toDouble()) {
                    run.add(point)
                } else {
                    finishRun()
                    anchor = point
                    run.add(point)
                }
            }
            finishRun()
        }

        // Assign sequence # per employee or overall ordered by start time
        val sequenceByEmployee = mutableMapOf<Int, Int>()
        val sorted = result.sortedBy { it.startEpoch }
        for (stay in sorted) {
            val seq = (sequenceByEmployee[stay.employeeId] ?: 0) + 1
            sequenceByEmployee[stay.employeeId] = seq
            stay.sequence = seq
        }

        return sorted
    }

    private fun enrichShopMatches(stays: List<LocationStayItem>, shops: List<Shop>) {
        for (stay in stays) {
            val match = shops
                .filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .map { shop ->
                    val dist = calculateDistance(stay.latitude, stay.longitude, shop.latitude, shop.longitude)
                    shop to dist
                }
                .filter { (_, dist) -> dist <= 120.0 } // 120m proximity geofence match
                .minByOrNull { (_, dist) -> dist }

            if (match != null) {
                val (shop, dist) = match
                stay.matchedLocation = shop.name.ifBlank { "Branch Office / Shop" }
                stay.placeCategory = "Worksite"
                stay.distanceFromMatchedLocationMeters = dist
                stay.radiusMeters = configuredClusterRadiusMeters
            } else {
                stay.matchedLocation = "External Client Site / Field Halt"
                stay.placeCategory = "Field Halt"
                stay.distanceFromMatchedLocationMeters = 0.0
                stay.radiusMeters = configuredClusterRadiusMeters
            }
        }
    }

    suspend fun reverseGeocodeStay(latitude: Double, longitude: Double): String {
        val cacheKey = "${String.format(Locale.US, "%.4f", latitude)},${String.format(Locale.US, "%.4f", longitude)}"
        addressCache[cacheKey]?.let { return it }

        val resolved = withContext(Dispatchers.IO) {
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(this@LocationStaysActivity, Locale.getDefault())
                    val addr: android.location.Address? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        suspendCancellableCoroutine { continuation ->
                            try {
                                geocoder.getFromLocation(
                                    latitude,
                                    longitude,
                                    1,
                                    object : android.location.Geocoder.GeocodeListener {
                                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                            continuation.resume(addresses.firstOrNull())
                                        }

                                        override fun onError(errorMessage: String?) {
                                            continuation.resume(null)
                                        }
                                    }
                                )
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    }
                    if (addr != null) {
                        val parts = listOfNotNull(
                            addr.thoroughfare?.takeIf { it.isNotBlank() },
                            addr.subLocality?.takeIf { it.isNotBlank() } ?: addr.locality?.takeIf { it.isNotBlank() },
                            addr.subAdminArea?.takeIf { it.isNotBlank() } ?: addr.locality?.takeIf { it.isNotBlank() },
                            addr.adminArea?.takeIf { it.isNotBlank() },
                            addr.postalCode?.takeIf { it.isNotBlank() }
                        ).distinct()
                        if (parts.isNotEmpty()) parts.joinToString(", ")
                        else addr.getAddressLine(0) ?: "${String.format(Locale.US, "%.6f", latitude)}, ${String.format(Locale.US, "%.6f", longitude)}"
                    } else {
                        "${String.format(Locale.US, "%.6f", latitude)}, ${String.format(Locale.US, "%.6f", longitude)}"
                    }
                } else {
                    "${String.format(Locale.US, "%.6f", latitude)}, ${String.format(Locale.US, "%.6f", longitude)}"
                }
            } catch (_: Exception) {
                "${String.format(Locale.US, "%.6f", latitude)}, ${String.format(Locale.US, "%.6f", longitude)}"
            }
        }
        addressCache[cacheKey] = resolved
        return resolved
    }

    private fun renderMap(
        points: List<SignalRManager.LiveLocation>,
        stays: List<LocationStayItem>
    ) {
        mapView.overlays.clear()
        stayMarkers.clear()

        val validPoints = points.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        val geoPoints = validPoints.map { GeoPoint(it.latitude, it.longitude) }

        // Draw movement trajectory Polyline
        if (geoPoints.size >= 2) {
            val polyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#1976D2")
                outlinePaint.strokeWidth = 6f
            }
            mapView.overlays.add(polyline)
        }

        // Draw numbered stay markers
        for (stay in stays) {
            val gp = GeoPoint(stay.latitude, stay.longitude)
            val marker = Marker(mapView).apply {
                position = gp
                title = "#${stay.sequence} ${stay.employeeName} (${formatDuration(stay.durationMs)})"
                snippet = "${stay.matchedLocation}\n${formatTime(stay.startEpoch)} - ${formatTime(stay.endEpoch)}"
                icon = createNumberedMarkerDrawable(stay.sequence)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    true
                }
            }
            mapView.overlays.add(marker)
            stayMarkers.add(marker)
        }

        // Zoom to fit all points or stays
        val allPoints = mutableListOf<GeoPoint>()
        allPoints.addAll(geoPoints)
        allPoints.addAll(stays.map { GeoPoint(it.latitude, it.longitude) })

        if (allPoints.isNotEmpty()) {
            zoomToFit(allPoints)
        } else {
            mapView.invalidate()
        }
    }

    private fun focusOnStay(stay: LocationStayItem) {
        val target = GeoPoint(stay.latitude, stay.longitude)
        mapView.controller.animateTo(target)
        mapView.controller.setZoom(17.0)

        val m = stayMarkers.firstOrNull { it.position.latitude == stay.latitude && it.position.longitude == stay.longitude }
        m?.showInfoWindow()
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

    private fun createNumberedMarkerDrawable(number: Int): BitmapDrawable {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E65100") // Amber-orange
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        val radius = size / 2f - 2f
        canvas.drawCircle(size / 2f, size / 2f, radius, circlePaint)
        canvas.drawCircle(size / 2f, size / 2f, radius, borderPaint)

        val textY = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(number.toString(), size / 2f, textY, textPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun calculateSpeedKmh(from: SignalRManager.LiveLocation?, to: SignalRManager.LiveLocation?): Double {
        if (from == null || to == null) return 0.0
        val t1 = parseTrackingTimestamp(from.timestamp)
        val t2 = parseTrackingTimestamp(to.timestamp)
        val elapsedSec = (abs(t2 - t1) / 1000.0).coerceAtLeast(1.0)
        if (elapsedSec > 1800.0) return 0.0
        val distM = calculateDistance(from.latitude, from.longitude, to.latitude, to.longitude)
        return (distM / elapsedSec) * 3.6
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

    private fun formatTime(epoch: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epoch))

    private fun formatDuration(ms: Long): String {
        val mins = ms / 60000
        val hrs = mins / 60
        val remMins = mins % 60
        return when {
            hrs > 0 -> "${hrs}h ${remMins}m"
            mins > 0 -> "${mins}m"
            else -> "${ms / 1000}s"
        }
    }

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

data class LocationStayItem(
    val id: Long,
    var sequence: Int,
    val employeeId: Int,
    val employeeName: String,
    val sessionId: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val durationMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val pointCount: Int,
    var matchedLocation: String,
    var placeCategory: String = "Field Halt",
    var distanceFromMatchedLocationMeters: Double,
    var radiusMeters: Int,
    val arrivalSpeedKmh: Double,
    val departureSpeedKmh: Double,
    var address: String? = null
)

private class LocationStayAdapter(
    private val scope: androidx.lifecycle.LifecycleCoroutineScope,
    private val reverseGeocode: suspend (Double, Double) -> String,
    private val getDwellMinutes: () -> Int,
    private val onStayClick: (LocationStayItem) -> Unit
) : RecyclerView.Adapter<LocationStayAdapter.Holder>() {

    private var items = listOf<LocationStayItem>()

    fun submit(newItems: List<LocationStayItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_stay, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], scope, reverseGeocode, getDwellMinutes, onStayClick)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSeq = view.findViewById<TextView>(R.id.tvStaySequence)
        private val tvEmployee = view.findViewById<TextView>(R.id.tvStayEmployee)
        private val tvTime = view.findViewById<TextView>(R.id.tvStayTime)
        private val tvDuration = view.findViewById<TextView>(R.id.tvStayDuration)
        private val tvLocation = view.findViewById<TextView>(R.id.tvStayLocation)
        private val tvCategory = view.findViewById<TextView>(R.id.tvStayCategory)
        private val tvAdminSummary = view.findViewById<TextView>(R.id.tvStayAdminSummary)
        private val tvAccuracy = view.findViewById<TextView>(R.id.tvStayAccuracy)
        private val tvPoints = view.findViewById<TextView>(R.id.tvStayPoints)
        private val tvSpeeds = view.findViewById<TextView>(R.id.tvStaySpeeds)
        private val tvCoords = view.findViewById<TextView>(R.id.tvStayCoords)
        private val tvAddress = view.findViewById<TextView>(R.id.tvStayAddress)
        private val card = view.findViewById<MaterialCardView>(R.id.cardStay)

        fun bind(
            item: LocationStayItem,
            scope: androidx.lifecycle.LifecycleCoroutineScope,
            reverseGeocode: suspend (Double, Double) -> String,
            getDwellMinutes: () -> Int,
            onStayClick: (LocationStayItem) -> Unit
        ) {
            tvSeq.text = item.sequence.toString()
            tvEmployee.text = item.employeeName

            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US).apply { timeZone = tz }
            val timeOnly = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
            tvTime.text = "${fmt.format(Date(item.startEpoch))} - ${timeOnly.format(Date(item.endEpoch))} IST"

            val mins = item.durationMs / 60000
            val hrs = mins / 60
            val remMins = mins % 60
            val durText = if (hrs > 0) "${hrs}h ${remMins}m" else "${mins}m"
            tvDuration.text = durText

            tvLocation.text = item.matchedLocation
            tvCategory.text = item.placeCategory
            tvCategory.setTextColor(if (item.placeCategory == "Worksite") Color.parseColor("#1565C0") else Color.parseColor("#E65100"))
            
            val dwell = getDwellMinutes()
            tvAdminSummary.text = "Stayed $durText at this location (within ${item.radiusMeters}m radius, ≥${dwell}m dwell)"

            tvAccuracy.text = "±${String.format(Locale.US, "%.1f", item.accuracyMeters)} m"
            tvPoints.text = "${item.pointCount} GPS fixes analyzed"

            val arrStr = if (item.arrivalSpeedKmh > 0) "${String.format(Locale.US, "%.0f", item.arrivalSpeedKmh)} km/h" else "0 km/h"
            val depStr = if (item.departureSpeedKmh > 0) "${String.format(Locale.US, "%.0f", item.departureSpeedKmh)} km/h" else "0 km/h"
            tvSpeeds.text = "Arr: $arrStr • Dep: $depStr"

            tvCoords.text = String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude)

            if (!item.address.isNullOrBlank()) {
                tvAddress.text = item.address
            } else {
                tvAddress.text = "Resolving address..."
                scope.launch {
                    val addr = reverseGeocode(item.latitude, item.longitude)
                    item.address = addr
                    tvAddress.text = addr
                }
            }

            card.setOnClickListener {
                onStayClick(item)
            }
        }
    }
}
