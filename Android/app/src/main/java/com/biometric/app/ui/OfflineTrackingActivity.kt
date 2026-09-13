package com.biometric.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.LocalLocation
import com.biometric.app.data.LocationDao
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.OfflineTrackingEventDao
import com.biometric.app.domain.location.OfflineSyncWorker
import com.biometric.app.domain.location.OfflineTrackingMonitor
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
import javax.inject.Inject

@AndroidEntryPoint
class OfflineTrackingActivity : AppCompatActivity() {
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var eventDao: OfflineTrackingEventDao
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var monitor: OfflineTrackingMonitor

    private lateinit var mapView: MapView
    private lateinit var tvConnectivity: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvLastCapture: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvSession: TextView
    private lateinit var eventAdapter: OfflineEventAdapter
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_tracking)
        monitor.start()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        tvConnectivity = findViewById(R.id.tvConnectivity)
        tvQueue = findViewById(R.id.tvQueue)
        tvTotal = findViewById(R.id.tvTotal)
        tvLastCapture = findViewById(R.id.tvLastCapture)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvSession = findViewById(R.id.tvSession)
        mapView = findViewById(R.id.offlineMapView)

        setupMap()
        setupEvents()
        findViewById<MaterialButton>(R.id.btnSyncNow).setOnClickListener {
            OfflineSyncWorker.schedule(this)
            Toast.makeText(this, "Offline GPS sync queued", Toast.LENGTH_SHORT).show()
            refreshOnce()
        }
        findViewById<MaterialButton>(R.id.btnRefreshOffline).setOnClickListener { refreshOnce() }
    }

    private fun setupMap() {
        OsmConfig.getInstance().tileFileSystemCacheMaxBytes = 200 * 1024 * 1024L
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == Configuration.UI_MODE_NIGHT_YES) {
            mapView.overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(floatArrayOf(
                    0.25f, 0f, 0f, 0f, 0f,
                    0f, 0.25f, 0f, 0f, 0f,
                    0f, 0f, 0.25f, 0f, 30f,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        }
    }

    private fun setupEvents() {
        eventAdapter = OfflineEventAdapter()
        findViewById<RecyclerView>(R.id.rvOfflineEvents).apply {
            layoutManager = LinearLayoutManager(this@OfflineTrackingActivity)
            adapter = eventAdapter
            setHasFixedSize(false)
        }
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshOnce()
                delay(2000L)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun refreshOnce() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pending = locationDao.getPendingCount()
            val total = locationDao.getTotalCount()
            val recent = locationDao.getRecent(1500)
            val lastSynced = locationDao.getLastSynced()
            val events = eventDao.recent(200)
            val currentSession = sessionStore.gpsSessionId()
            withContext(Dispatchers.Main) {
                tvConnectivity.text = if (monitor.isOnline()) "ONLINE • Server reachable" else "OFFLINE • Local capture active"
                tvQueue.text = pending.toString()
                tvTotal.text = total.toString()
                tvLastCapture.text = recent.firstOrNull()?.let { formatIst(it.timestamp) } ?: "—"
                tvLastSync.text = lastSynced?.syncedAt?.let { formatIst(it) } ?: "—"
                tvSession.text = currentSession.take(8) + "…"
                eventAdapter.submit(events)
                drawLocalRoute(recent)
            }
        }
    }

    private fun drawLocalRoute(points: List<LocalLocation>) {
        mapView.overlays.clear()
        val ordered = points.sortedWith(compareBy<LocalLocation> { it.timestamp }.thenBy { it.sequence })
        if (ordered.isEmpty()) {
            mapView.invalidate()
            return
        }

        val geo = ordered.map { GeoPoint(it.latitude, it.longitude) }
        val line = Polyline(mapView).apply {
            setPoints(geo)
            width = 8f
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(line)

        val first = geo.first()
        val last = geo.last()
        mapView.overlays.add(Marker(mapView).apply {
            position = first
            title = "Route start"
            snippet = formatIst(ordered.first().timestamp)
        })
        mapView.overlays.add(Marker(mapView).apply {
            position = last
            title = "Latest local GPS"
            snippet = "${formatIst(ordered.last().timestamp)} • ${ordered.last().syncState}"
        })

        if (geo.size > 1) {
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), true, 80)
        } else {
            mapView.controller.setCenter(first)
        }
        mapView.invalidate()
    }

    private fun formatIst(time: Long): String =
        SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(time)) + " IST"

    override fun onDestroy() {
        mapView.onDetach()
        super.onDestroy()
    }
}

private class OfflineEventAdapter : RecyclerView.Adapter<OfflineEventAdapter.Holder>() {
    private var items: List<com.biometric.app.data.entity.OfflineTrackingEvent> = emptyList()

    fun submit(value: List<com.biometric.app.data.entity.OfflineTrackingEvent>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_offline_tracking_event, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvEventTitle)
        private val message = view.findViewById<TextView>(R.id.tvEventMessage)
        private val meta = view.findViewById<TextView>(R.id.tvEventMeta)
        fun bind(event: com.biometric.app.data.entity.OfflineTrackingEvent) {
            title.text = "${event.eventType} • ${event.severity}"
            message.text = event.message
            val time = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.format(Date(event.eventTime))
            meta.text = "$time IST  •  network=${if (event.networkAvailable) "ONLINE" else "OFFLINE"}  •  queue=${event.queueDepth}" +
                (event.correlationId?.let { "  •  id=${it.take(8)}…" } ?: "")
        }
    }
}
