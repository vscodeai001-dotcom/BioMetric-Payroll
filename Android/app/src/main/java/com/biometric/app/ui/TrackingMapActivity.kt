package com.biometric.app.ui

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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.OsrmApiService
import com.biometric.app.databinding.ActivityTrackingMapBinding
import com.biometric.app.sync.SignalRManager
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.PolylineDecoder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import javax.inject.Inject

@AndroidEntryPoint
class TrackingMapActivity : MotionBaseActivity() {

    private var _binding: ActivityTrackingMapBinding? = null
    private val binding get() = _binding!!
    
    @Inject lateinit var signalR: SignalRManager
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var osrmApi: OsrmApiService

    private val markers = mutableMapOf<Int, Marker>()
    private val roadLines = mutableMapOf<Int, Polyline>()
    private val roadCasings = mutableMapOf<Int, Polyline>()
    private val lastRouteUpdate = mutableMapOf<Int, Long>()
    private val roadRouteJobs = mutableMapOf<Int, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private var statusFilter = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityTrackingMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyWindowInsets(binding.main, binding.appBar)
        setupMap()
        setupFilters()
        observeLiveLocations()
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

    private fun setupMap() {
        binding.mapview.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            
            // Map Styling
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                overlayManager.tilesOverlay.setColorFilter(
                    ColorMatrixColorFilter(floatArrayOf(
                        0.25f, 0f, 0f, 0f, 0f,
                        0f, 0.25f, 0f, 0f, 0f,
                        0f, 0f, 0.25f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                )
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLiveLocations() {
        signalR.start()
        lifecycleScope.launch {
            signalR.liveLocations
                .debounce(500L) // Throttled updates to prevent UI saturation
                .collect { liveMap ->
                    _binding?.let { updateMapMarkers(liveMap.values.toList()) }
                }
        }
    }

    private fun updateMapMarkers(locations: List<SignalRManager.LiveLocation>) {
        val mapView = binding.mapview
        
        val employeeData = sharedViewModel.allEmployees.value
        val geoPoints = mutableListOf<GeoPoint>()

        val currentIds = locations.map { it.employeeId }
        markers.keys.filter { !currentIds.contains(it) }.forEach { id ->
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
            
            val marker = markers.getOrPut(loc.employeeId) {
                Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = emp?.name ?: "Staff #${loc.employeeId}"
                    mapView.overlays.add(this)
                }
            }

            marker.alpha = 1f
            animateMarker(marker, point, loc.employeeId)
            
            val initials = getInitials(emp?.name ?: "E")
            val cacheKey = "${initials}_${loc.isWithinAllowedRadius}_$status"
            val icon = iconCache.getOrPut(cacheKey) { 
                createPremiumMarkerIcon(initials, loc.isWithinAllowedRadius, status) 
            }
            marker.icon = icon
            
            // Premium Tooltip (Snippet)
            val speedText = formatSpeed(loc.speedMps)
            marker.snippet = "Status: $status | Speed: $speedText\nDist: ${formatDistance(loc.distanceMeters)}"
            
            updateActivityRoadRoute(loc.employeeId, point)
            geoPoints.add(point)
        }

        binding.fabRefresh.setOnClickListener {
            if (geoPoints.isNotEmpty()) {
                val bounds = BoundingBox.fromGeoPoints(geoPoints)
                mapView.zoomToBoundingBox(bounds, true, 150)
            }
        }
        mapView.invalidate()
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
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(timestamp)
            val ageMs = System.currentTimeMillis() - (date?.time ?: 0L)
            
            when {
                ageMs <= 60_000 -> "Live"
                ageMs <= 300_000 -> "Stale"
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
        val startPosition = marker.position
        if (startPosition.latitude == 0.0) {
            marker.position = toPosition
            return
        }
        val startTime = SystemClock.uptimeMillis()
        val duration = 1500L
        val handler = Handler(Looper.getMainLooper())

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startTime
                val t = (elapsed.toFloat() / duration).coerceAtMost(1.0f)
                val lat = t * toPosition.latitude + (1 - t) * startPosition.latitude
                val lng = t * toPosition.longitude + (1 - t) * startPosition.longitude
                val point = GeoPoint(lat, lng)
                
                marker.position = point
                
                empId?.let { id ->
                    roadLines[id]?.let { l ->
                        val pts = l.actualPoints.toMutableList()
                        if (pts.size >= 2) { pts[0] = point; l.setPoints(pts) }
                    }
                    roadCasings[id]?.let { c ->
                        val pts = c.actualPoints.toMutableList()
                        if (pts.size >= 2) { pts[0] = point; c.setPoints(pts) }
                    }
                }
                
                binding.mapview.invalidate()
                if (t < 1.0) handler.postDelayed(this, 16)
            }
        })
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
        roadRouteJobs.values.forEach { it.cancel() }
        roadRouteJobs.clear()
        iconCache.clear()
        
        _binding?.mapview?.onDetach()
        super.onDestroy()
        _binding = null
    }
}
