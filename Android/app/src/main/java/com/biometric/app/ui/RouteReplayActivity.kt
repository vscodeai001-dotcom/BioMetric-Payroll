package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.entity.LocationTrack
import com.biometric.app.databinding.ActivityRouteReplayBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@AndroidEntryPoint
class RouteReplayActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRouteReplayBinding
    private var googleMap: GoogleMap? = null
    private val db = FirebaseFirestore.getInstance()
    
    private var staffId: String = ""
    private var staffName: String = ""
    private var selectedDateMillis: Long = System.currentTimeMillis()

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

        binding.fabPlay.setOnClickListener {
            startReplay()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        fetchRouteData()
    }

    private fun fetchRouteData() {
        if (staffId.isEmpty()) return

        lifecycleScope.launch {
            try {
                // Querying the correct collection name
                val snapshot = db.collection("location_tracking")
                    .whereEqualTo("staffId", staffId)
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .get()
                    .await()

                val tracks = snapshot.toObjects(LocationTrack::class.java)
                if (tracks.isNotEmpty()) {
                    drawRoute(tracks)
                } else {
                    Toast.makeText(this@RouteReplayActivity, "No data found for this employee", Toast.LENGTH_SHORT).show()
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
