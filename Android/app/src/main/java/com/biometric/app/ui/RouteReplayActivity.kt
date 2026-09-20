package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LocationTrack
import com.biometric.app.databinding.ActivityRouteReplayBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class RouteReplayActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRouteReplayBinding
    private var googleMap: GoogleMap? = null
    private val database = FirebaseDatabase.getInstance().reference
    
    private var staffId: String = ""
    private var staffName: String = ""
    private var selectedDateMillis: Long = System.currentTimeMillis()

    @Inject lateinit var sessionStore: MobileSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRouteReplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        staffId = intent.getStringExtra("STAFF_ID") ?: ""
        staffName = intent.getStringExtra("STAFF_NAME") ?: "Employee"
        binding.tvStaffName.text = staffName

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        updateDateLabel()
        binding.btnDate.setOnClickListener {
            showDatePicker()
        }

        binding.fabPlay.setOnClickListener {
            startReplay()
        }
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
        fetchRouteData()
    }

    private fun fetchRouteData() {
        if (staffId.isEmpty()) return
        val ownerUid = sessionStore.firebaseOwnerUid() ?: return
        
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val datePrefix = sdf.format(Date(selectedDateMillis))

        lifecycleScope.launch {
            try {
                // REQUIREMENT: Route Playback must use the Realtime Database tracking/history 
                // node, which is the authoritative SSOT for all platform GPS evidence.
                // Filter by Timestamp to show only the selected date.
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

                val tracks = snapshot.children.mapNotNull { child ->
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

                if (tracks.isNotEmpty()) {
                    drawRoute(tracks)
                } else {
                    googleMap?.clear()
                    binding.tvRouteSummary.text = "No tracking data for this date"
                    Toast.makeText(this@RouteReplayActivity, "No data found for the selected date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Replay", "Error loading route", e)
                Toast.makeText(this@RouteReplayActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawRoute(tracks: List<LocationTrack>) {
        val map = googleMap ?: return
        map.clear()

        val points = tracks.map { LatLng(it.latitude, it.longitude) }
        val polylineOptions = PolylineOptions()
            .addAll(points)
            .color(getColor(R.color.colorPrimary))
            .width(10f)
            .jointType(JointType.ROUND)

        map.addPolyline(polylineOptions)

        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(it) }
        
        map.addMarker(MarkerOptions().position(points.first()).title("Start").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        map.addMarker(MarkerOptions().position(points.last()).title("End").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))

        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 150))
        } catch (e: Exception) {
            Log.e("Replay", "Error framing markers", e)
        }
        
        binding.tvRouteSummary.text = "Tracked Points: ${tracks.size}"
    }

    private fun startReplay() {
        Toast.makeText(this, "Starting Replay...", Toast.LENGTH_SHORT).show()
    }
}
