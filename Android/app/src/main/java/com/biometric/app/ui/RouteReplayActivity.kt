package com.biometric.app.ui

import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LocationTrack
import com.biometric.app.databinding.ActivityRouteReplayBinding
import com.biometric.app.util.PolylineDecoder
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.database.FirebaseDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class RouteReplayActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRouteReplayBinding
    private var googleMap: GoogleMap? = null
    private val database = FirebaseDatabase.getInstance().reference

    private var staffId: String = ""
    private var staffName: String = ""
    private var selectedDateMillis: Long = System.currentTimeMillis()

    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var osrmApi: OsrmApiService

    // Route and Replay State
    private var rawTracks: List<LocationTrack> = emptyList()
    private var snappedRoutePoints: List<LatLng> = emptyList()
    private var trackIndicesForSnapped: List<Int> = emptyList()

    private var travelledCasing: Polyline? = null
    private var travelledPolyline: Polyline? = null
    private var remainingPolyline: Polyline? = null
    private var movingMarker: Marker? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null

    private var isPlaying = false
    private var isFollowCameraEnabled = true
    private var currentPointIndex = 0
    private var replaySpeedMultiplier = 1.0f

    private val replayHandler = Handler(Looper.getMainLooper())
    private var replayRunnable: Runnable? = null
    private var markerAnimator: ValueAnimator? = null
    private var routeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRouteReplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        staffId = intent.getStringExtra("STAFF_ID") ?: ""
        staffName = intent.getStringExtra("STAFF_NAME") ?: "Employee"
        binding.tvStaffName.text = staffName

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        updateDateLabel()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnDate.setOnClickListener {
            showDatePicker()
        }

        binding.fabPlay.setOnClickListener {
            if (isPlaying) {
                pauseReplay()
            } else {
                startReplay()
            }
        }

        binding.btnSpeed.setOnClickListener {
            replaySpeedMultiplier = when (replaySpeedMultiplier) {
                1.0f -> 2.0f
                2.0f -> 4.0f
                else -> 1.0f
            }
            binding.btnSpeed.text = "${replaySpeedMultiplier.toInt()}x Speed"
            Toast.makeText(this, "Speed set to ${replaySpeedMultiplier.toInt()}x", Toast.LENGTH_SHORT).show()
        }

        binding.btnFollowCamera.setOnClickListener {
            isFollowCameraEnabled = !isFollowCameraEnabled
            binding.btnFollowCamera.setColorFilter(
                if (isFollowCameraEnabled) Color.parseColor("#16A34A") else Color.parseColor("#94A3B8")
            )
            Toast.makeText(
                this,
                if (isFollowCameraEnabled) "Camera Follow Active ⦿" else "Free Pan Mode ◌",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRecenter.setOnClickListener {
            fitRouteBounds()
        }

        binding.seekBarReplay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && snappedRoutePoints.isNotEmpty()) {
                    val index = ((progress / 100.0) * (snappedRoutePoints.size - 1)).toInt()
                        .coerceIn(0, snappedRoutePoints.size - 1)
                    seekToPoint(index)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isPlaying) pauseReplay()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            selectedDateMillis = cal.timeInMillis
            updateDateLabel()
            fetchRouteData()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateDateLabel() {
        binding.btnDate.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis))
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = false
        googleMap?.uiSettings?.isCompassEnabled = true
        googleMap?.uiSettings?.isMyLocationButtonEnabled = false
        fetchRouteData()
    }

    private fun fetchRouteData() {
        if (staffId.isEmpty()) return
        val ownerUid = sessionStore.firebaseOwnerUid() ?: return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val datePrefix = sdf.format(Date(selectedDateMillis))

        stopReplay()

        lifecycleScope.launch {
            try {
                binding.tvRouteSummary.text = "Loading and road-matching route..."
                val snapshot = database.child("owners")
                    .child(ownerUid)
                    .child("tracking")
                    .child("history")
                    .child(staffId)
                    .orderByChild("Timestamp")
                    .startAt(datePrefix)
                    .endAt(datePrefix + "\uf8ff")
                    .get()
                    .await()

                val rawList = snapshot.children.mapNotNull { child ->
                    val lat = child.child("Latitude").getValue(Double::class.java) ?: 0.0
                    val lon = child.child("Longitude").getValue(Double::class.java) ?: 0.0
                    val time = child.child("Timestamp").getValue(String::class.java) ?: ""
                    val ts = runCatching { Instant.parse(time).toEpochMilli() }.getOrDefault(0L)
                    val acc = child.child("AccuracyMeters").getValue(Double::class.java)?.toFloat() ?: 0f
                    val spd = child.child("SpeedMps").getValue(Double::class.java)?.toFloat() ?: 0f
                    val batt = child.child("BatteryLevel").getValue(Int::class.java) ?: 0

                    if (lat != 0.0 && lon != 0.0) {
                        LocationTrack(
                            staffId = staffId,
                            timestamp = ts,
                            latitude = lat,
                            longitude = lon,
                            accuracy = acc,
                            speed = spd,
                            batteryLevel = batt
                        )
                    } else null
                }.sortedBy { it.timestamp }

                // Clean outlier GPS teleport spikes & duplicates
                rawTracks = filterGpsTracks(rawList)

                if (rawTracks.isNotEmpty()) {
                    buildRoadSnappedRoute(rawTracks)
                } else {
                    googleMap?.clear()
                    binding.tvRouteSummary.text = "No tracking data for this date"
                    binding.tvReplayCurrentTime.text = "Point 0 of 0 • --:--"
                    binding.seekBarReplay.progress = 0
                    Toast.makeText(this@RouteReplayActivity, "No route points found for the selected date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Replay", "Error loading route", e)
                Toast.makeText(this@RouteReplayActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterGpsTracks(tracks: List<LocationTrack>): List<LocationTrack> {
        if (tracks.size <= 2) return tracks
        val cleaned = mutableListOf<LocationTrack>()
        cleaned.add(tracks.first())

        for (i in 1 until tracks.size) {
            val prev = cleaned.last()
            val curr = tracks[i]
            val dist = calculateDistanceMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            // Suppress duplicate points (< 4m) and impossible teleport spikes (> 3000m)
            if (dist in 4.0..3000.0) {
                cleaned.add(curr)
            }
        }
        if (cleaned.size < 2 && tracks.size >= 2) {
            cleaned.add(tracks.last())
        }
        return cleaned
    }

    private fun buildRoadSnappedRoute(tracks: List<LocationTrack>) {
        routeJob?.cancel()
        routeJob = lifecycleScope.launch(Dispatchers.IO) {
            val sampledPoints = downsampleForRouting(tracks)
            var snappedPoints: List<LatLng> = emptyList()

            // Try OSRM road routing
            try {
                val coords = sampledPoints.joinToString(";") { "${it.longitude},${it.latitude}" }
                val response = osrmApi.getRoute(coords)
                if (response.isSuccessful) {
                    val encoded = response.body()?.routes?.firstOrNull()?.geometry
                    if (!encoded.isNullOrBlank()) {
                        val decoded = PolylineDecoder.decode(encoded)
                        snappedPoints = decoded.map { LatLng(it.latitude, it.longitude) }
                    }
                }
            } catch (_: Exception) {}

            // Fallback to Catmull-Rom smooth spline interpolation if OSRM is unavailable
            if (snappedPoints.size < 2) {
                val rawLatLngs = tracks.map { LatLng(it.latitude, it.longitude) }
                snappedPoints = generateSmoothSpline(rawLatLngs)
            }

            withContext(Dispatchers.Main) {
                snappedRoutePoints = snappedPoints
                renderRouteOnMap()
            }
        }
    }

    private fun downsampleForRouting(tracks: List<LocationTrack>): List<LocationTrack> {
        if (tracks.size <= 30) return tracks
        val step = (tracks.size / 28).coerceAtLeast(1)
        val sampled = mutableListOf<LocationTrack>()
        sampled.add(tracks.first())
        for (i in 1 until tracks.size - 1 step step) {
            sampled.add(tracks[i])
        }
        sampled.add(tracks.last())
        return sampled
    }

    private fun renderRouteOnMap() {
        val map = googleMap ?: return
        map.clear()

        if (snappedRoutePoints.size < 2) return

        // 1. White Casing for Traveled Route
        travelledCasing = map.addPolyline(
            PolylineOptions()
                .color(Color.WHITE)
                .width(18f)
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .zIndex(2f)
        )

        // 2. Vibrant Green Route (Way Arrived / Traveled Path - Swiggy/Zomato style)
        travelledPolyline = map.addPolyline(
            PolylineOptions()
                .color(Color.parseColor("#10B981"))
                .width(12f)
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .zIndex(3f)
        )

        // 3. Muted Gray Remaining Route
        remainingPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(snappedRoutePoints)
                .color(Color.parseColor("#94A3B8"))
                .width(8f)
                .jointType(JointType.ROUND)
                .pattern(listOf(Dash(20f), Gap(14f)))
                .zIndex(1f)
        )

        // Start & End Markers
        val firstPoint = snappedRoutePoints.first()
        val lastPoint = snappedRoutePoints.last()

        startMarker = map.addMarker(
            MarkerOptions()
                .position(firstPoint)
                .title("Start Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .zIndex(4f)
        )

        endMarker = map.addMarker(
            MarkerOptions()
                .position(lastPoint)
                .title("Final Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(4f)
        )

        // Moving Marker with vehicle/runner icon
        movingMarker = map.addMarker(
            MarkerOptions()
                .position(firstPoint)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .icon(createMovingMarkerIcon())
                .zIndex(10f)
        )

        // Total distance calculation
        var totalDistMeters = 0.0
        for (i in 0 until snappedRoutePoints.size - 1) {
            val a = snappedRoutePoints[i]
            val b = snappedRoutePoints[i + 1]
            totalDistMeters += calculateDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        val distText = if (totalDistMeters < 1000) "${totalDistMeters.toInt()} m" else String.format(Locale.US, "%.2f km", totalDistMeters / 1000.0)
        binding.tvRouteSummary.text = "Distance: $distText • ${rawTracks.size} GPS Points • Road Snapped 🛣️"

        currentPointIndex = 0
        updateProgressViews(0)
        fitRouteBounds()
    }

    private fun fitRouteBounds() {
        val map = googleMap ?: return
        if (snappedRoutePoints.isEmpty()) return
        val builder = LatLngBounds.Builder()
        snappedRoutePoints.forEach { builder.include(it) }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
        } catch (_: Exception) {}
    }

    private fun startReplay() {
        if (snappedRoutePoints.isEmpty()) return
        isPlaying = true
        binding.fabPlay.text = "Pause"
        binding.fabPlay.setIconResource(R.drawable.ic_pause)

        if (currentPointIndex >= snappedRoutePoints.size - 1) {
            currentPointIndex = 0
            seekToPoint(0)
        }

        scheduleNextStep()
    }

    private fun pauseReplay() {
        isPlaying = false
        binding.fabPlay.text = "Play Replay"
        binding.fabPlay.setIconResource(R.drawable.ic_play)
        replayHandler.removeCallbacksAndMessages(null)
        markerAnimator?.cancel()
    }

    private fun stopReplay() {
        pauseReplay()
        currentPointIndex = 0
    }

    private fun scheduleNextStep() {
        if (!isPlaying || currentPointIndex >= snappedRoutePoints.size - 1) {
            if (currentPointIndex >= snappedRoutePoints.size - 1) {
                pauseReplay()
                Toast.makeText(this, "Route Replay Completed 🏁", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val fromPos = snappedRoutePoints[currentPointIndex]
        val toPos = snappedRoutePoints[currentPointIndex + 1]
        val distMeters = calculateDistanceMeters(fromPos.latitude, fromPos.longitude, toPos.latitude, toPos.longitude)
        val baseDuration = (distMeters * 35.0).coerceIn(80.0, 600.0)
        val stepDuration = (baseDuration / replaySpeedMultiplier).toLong()

        animateMarkerToPosition(fromPos, toPos, stepDuration) {
            currentPointIndex++
            updateProgressViews(currentPointIndex)
            scheduleNextStep()
        }
    }

    private fun animateMarkerToPosition(
        from: LatLng,
        to: LatLng,
        durationMs: Long,
        onComplete: () -> Unit
    ) {
        val marker = movingMarker ?: run {
            onComplete()
            return
        }

        val bearing = calculateBearing(from, to).toFloat()
        marker.rotation = bearing

        markerAnimator?.cancel()
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val lat = from.latitude + (to.latitude - from.latitude) * fraction
                val lng = from.longitude + (to.longitude - from.longitude) * fraction
                val pos = LatLng(lat, lng)

                marker.position = pos

                // Update Green Travelled Path
                val travelled = snappedRoutePoints.subList(0, (currentPointIndex + 1).coerceAtMost(snappedRoutePoints.size))
                    .toMutableList()
                travelled.add(pos)
                travelledCasing?.points = travelled
                travelledPolyline?.points = travelled

                // Update Remaining Path
                val remaining = mutableListOf(pos)
                if (currentPointIndex + 1 < snappedRoutePoints.size) {
                    remaining.addAll(snappedRoutePoints.subList(currentPointIndex + 1, snappedRoutePoints.size))
                }
                remainingPolyline?.points = remaining

                // Camera follow
                if (isFollowCameraEnabled) {
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLng(pos), durationMs.toInt(), null)
                }
            }
            doOnEnd {
                onComplete()
            }
            start()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    private fun seekToPoint(index: Int) {
        val safeIndex = index.coerceIn(0, snappedRoutePoints.size - 1)
        currentPointIndex = safeIndex
        val pos = snappedRoutePoints[safeIndex]

        movingMarker?.position = pos
        if (safeIndex < snappedRoutePoints.size - 1) {
            movingMarker?.rotation = calculateBearing(pos, snappedRoutePoints[safeIndex + 1]).toFloat()
        }

        val travelled = snappedRoutePoints.subList(0, safeIndex + 1)
        travelledCasing?.points = travelled
        travelledPolyline?.points = travelled

        val remaining = snappedRoutePoints.subList(safeIndex, snappedRoutePoints.size)
        remainingPolyline?.points = remaining

        updateProgressViews(safeIndex)

        if (isFollowCameraEnabled) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }

    private fun updateProgressViews(index: Int) {
        if (snappedRoutePoints.isEmpty()) return
        val pct = ((index.toDouble() / (snappedRoutePoints.size - 1).coerceAtLeast(1)) * 100).toInt()
        binding.seekBarReplay.progress = pct
        binding.tvReplayProgressPct.text = "$pct%"

        val trackIndex = ((index.toDouble() / (snappedRoutePoints.size - 1).coerceAtLeast(1)) * (rawTracks.size - 1)).toInt()
            .coerceIn(0, (rawTracks.size - 1).coerceAtLeast(0))
        val track = rawTracks.getOrNull(trackIndex)

        val timeString = if (track != null && track.timestamp > 0) {
            SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(track.timestamp))
        } else {
            "--:--"
        }

        binding.tvReplayCurrentTime.text = "Point ${index + 1} of ${snappedRoutePoints.size} • $timeString"
    }

    private fun createMovingMarkerIcon(): BitmapDescriptor {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Outer Glow
        paint.color = Color.parseColor("#3310B981")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // White border
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)

        // Vibrant Emerald Center
        paint.color = Color.parseColor("#10B981")
        canvas.drawCircle(size / 2f, size / 2f, size / 3.2f, paint)

        // Direction pointer arrow
        paint.color = Color.WHITE
        val path = android.graphics.Path()
        val cx = size / 2f
        val cy = size / 2f
        path.moveTo(cx, cy - 12f)
        path.lineTo(cx + 8f, cy + 8f)
        path.lineTo(cx, cy + 4f)
        path.lineTo(cx - 8f, cy + 8f)
        path.close()
        canvas.drawPath(path, paint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun generateSmoothSpline(points: List<LatLng>, pointsPerSegment: Int = 5): List<LatLng> {
        if (points.size <= 2) return points
        val result = mutableListOf<LatLng>()
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
                result.add(LatLng(lat, lng))
            }
        }
        result.add(points.last())
        return result
    }

    override fun onPause() {
        super.onPause()
        pauseReplay()
    }

    override fun onDestroy() {
        pauseReplay()
        routeJob?.cancel()
        super.onDestroy()
    }
}
