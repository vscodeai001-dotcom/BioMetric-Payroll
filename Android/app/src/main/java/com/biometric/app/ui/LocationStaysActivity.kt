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
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class LocationStaysActivity : AppCompatActivity() {

    @Inject lateinit var repo: MainRepository
    @Inject lateinit var signal: SignalRManager

    private lateinit var mapView: MapView
    private lateinit var spnEmployee: Spinner
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

        initViews()
        setupMap()
        setupAdapters()
        setupPickers()
        observeData()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        spnEmployee = findViewById(R.id.spnEmployee)
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
        stayAdapter = LocationStayAdapter { stay ->
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
    }

    private fun updateEmployeeSpinner() {
        val options = mutableListOf("All employees")
        options.addAll(activeEmployees.map { "${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spnEmployee.adapter = adapter
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
            val deferred = empIdsToQuery.map { eid ->
                async {
                    signal.loadTrackingHistory(eid, 1500)
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

            val totalStayMs = detectedStays.sumOf { it.durationMs }
            val distinctEmployeesCount = detectedStays.map { it.employeeId }.distinct().size

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnSearchRadar.isEnabled = true

                tvMetricEmployees.text = distinctEmployeesCount.toString()
                tvMetricStays.text = detectedStays.size.toString()
                tvMetricTotalStay.text = formatDuration(totalStayMs)
                tvMetricPoints.text = filteredPoints.size.toString()
                tvMapPointsBadge.text = "${filteredPoints.size} points"
                tvStaysCountBadge.text = "${detectedStays.size} stays"

                stayAdapter.submit(detectedStays)
                tvNoStays.visibility = if (detectedStays.isEmpty()) View.VISIBLE else View.GONE

                renderMap(filteredPoints, detectedStays)
            }
        }
    }

    /**
     * SSOT stay detection identical to Web LocationTrackingHistory.razor.DetectStays:
     * Points clustered within 10-15m for > 10 minutes.
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

                // Web SSOT: Minimum 10 minutes (600,000 ms)
                if (durationMs >= 10 * 60 * 1000L) {
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
                            matchedLocation = "Unknown / Other Location",
                            distanceFromMatchedLocationMeters = 0.0,
                            radiusMeters = run.first().allowedRadiusMeters,
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
                // Threshold: 10-15 meters cluster distance
                if (dist <= 15.0) {
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
        if (shops.isEmpty()) return

        for (stay in stays) {
            val match = shops
                .filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .map { shop ->
                    val dist = calculateDistance(stay.latitude, stay.longitude, shop.latitude, shop.longitude)
                    shop to dist
                }
                .filter { (_, dist) -> dist <= 120.0 } // 120m geofence radius match
                .minByOrNull { (_, dist) -> dist }

            if (match != null) {
                val (shop, dist) = match
                stay.matchedLocation = shop.name.ifBlank { "Branch Shop" }
                stay.distanceFromMatchedLocationMeters = dist
                stay.radiusMeters = 100
            }
        }
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
    var distanceFromMatchedLocationMeters: Double,
    var radiusMeters: Int,
    val arrivalSpeedKmh: Double,
    val departureSpeedKmh: Double
)

private class LocationStayAdapter(
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
        holder.bind(items[position], onStayClick)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSeq = view.findViewById<TextView>(R.id.tvStaySequence)
        private val tvEmployee = view.findViewById<TextView>(R.id.tvStayEmployee)
        private val tvTime = view.findViewById<TextView>(R.id.tvStayTime)
        private val tvDuration = view.findViewById<TextView>(R.id.tvStayDuration)
        private val tvLocation = view.findViewById<TextView>(R.id.tvStayLocation)
        private val tvAccuracy = view.findViewById<TextView>(R.id.tvStayAccuracy)
        private val tvPoints = view.findViewById<TextView>(R.id.tvStayPoints)
        private val tvSpeeds = view.findViewById<TextView>(R.id.tvStaySpeeds)
        private val tvCoords = view.findViewById<TextView>(R.id.tvStayCoords)
        private val card = view.findViewById<MaterialCardView>(R.id.cardStay)

        fun bind(item: LocationStayItem, onStayClick: (LocationStayItem) -> Unit) {
            tvSeq.text = item.sequence.toString()
            tvEmployee.text = item.employeeName

            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US).apply { timeZone = tz }
            val timeOnly = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
            tvTime.text = "${fmt.format(Date(item.startEpoch))} - ${timeOnly.format(Date(item.endEpoch))} IST"

            val mins = item.durationMs / 60000
            val hrs = mins / 60
            val remMins = mins % 60
            tvDuration.text = if (hrs > 0) "${hrs}h ${remMins}m" else "${mins}m"

            tvLocation.text = item.matchedLocation
            tvAccuracy.text = "±${String.format(Locale.US, "%.1f", item.accuracyMeters)} m"
            tvPoints.text = "${item.pointCount} GPS fixes analyzed"

            val arrStr = if (item.arrivalSpeedKmh > 0) "${String.format(Locale.US, "%.0f", item.arrivalSpeedKmh)} km/h" else "0 km/h"
            val depStr = if (item.departureSpeedKmh > 0) "${String.format(Locale.US, "%.0f", item.departureSpeedKmh)} km/h" else "0 km/h"
            tvSpeeds.text = "Arr: $arrStr • Dep: $depStr"

            tvCoords.text = String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude)

            card.setOnClickListener {
                onStayClick(item)
            }
        }
    }
}
