package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.R
import com.biometric.app.data.entity.GeofenceLocation
import com.biometric.app.databinding.ActivityGeofenceManagerBinding
import com.biometric.app.databinding.DialogAddGeofenceBinding
import com.biometric.app.ui.adapter.GeofenceAdapter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import java.util.*

@AndroidEntryPoint
class GeofenceManagerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGeofenceManagerBinding
    private var googleMap: GoogleMap? = null
    private val auth = FirebaseAuth.getInstance()
    private val dbRef by lazy { 
        FirebaseDatabase.getInstance().getReference("owners")
            .child(auth.currentUser?.uid ?: "unknown").child("geofences")
    }

    private lateinit var adapter: GeofenceAdapter
    private val geofenceList = mutableListOf<GeofenceLocation>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeofenceManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupRecyclerView()
        listenToGeofences()

        binding.fabAdd.setOnClickListener {
            showAddGeofenceDialog()
        }
    }

    private fun setupRecyclerView() {
        adapter = GeofenceAdapter(geofenceList, { g ->
            zoomToGeofence(g)
        }, { g ->
            deleteGeofence(g)
        })
        binding.rvGeofences.layoutManager = LinearLayoutManager(this)
        binding.rvGeofences.adapter = adapter
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        updateMapMarkers()
    }

    private fun listenToGeofences() {
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    geofenceList.clear()
                    for (child in snapshot.children) {
                        try {
                            child.getValue(GeofenceLocation::class.java)?.let { geofenceList.add(it) }
                        } catch (e: Exception) {
                            Log.e("Geofence", "Parse error", e)
                        }
                    }
                    adapter.notifyDataSetChanged()
                    updateMapMarkers()
                } catch (e: Exception) {
                    Log.e("Geofence", "Query error", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@GeofenceManagerActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun updateMapMarkers() {
        val map = googleMap ?: return
        map.clear()

        for (g in geofenceList) {
            val pos = LatLng(g.latitude, g.longitude)
            map.addMarker(MarkerOptions().position(pos).title(g.name))
            map.addCircle(
                CircleOptions()
                    .center(pos)
                    .radius(g.radius.toDouble())
                    .strokeColor(getColor(R.color.colorPrimary))
                    .fillColor(0x224F46E5)
            )
        }
    }

    private fun zoomToGeofence(g: GeofenceLocation) {
        val pos = LatLng(g.latitude, g.longitude)
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
    }

    private fun showAddGeofenceDialog() {
        val dialogBinding = DialogAddGeofenceBinding.inflate(LayoutInflater.from(this))

        AlertDialog.Builder(this)
            .setTitle("Add Geofence 📍")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.etName.text.toString()
                val lat = dialogBinding.etLat.text.toString().toDoubleOrNull() ?: 0.0
                val lon = dialogBinding.etLng.text.toString().toDoubleOrNull() ?: 0.0
                val radius = dialogBinding.etRadius.text.toString().toFloatOrNull() ?: 100f

                if (name.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    val newGeofence = GeofenceLocation(id, name, lat, lon, radius)
                    dbRef.child(id).setValue(newGeofence)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGeofence(g: GeofenceLocation) {
        AlertDialog.Builder(this)
            .setTitle("Delete Geofence?")
            .setMessage("Are you sure you want to remove ${g.name}?")
            .setPositiveButton("Delete") { _, _ ->
                dbRef.child(g.id).removeValue()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
