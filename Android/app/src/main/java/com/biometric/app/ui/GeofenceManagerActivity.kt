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
import com.biometric.app.data.dao.GeofenceDao
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.biometric.app.data.entity.UserRole
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.data.MobileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceManagerActivity : MotionBaseActivity(), OnMapReadyCallback {

    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var geofenceDao: GeofenceDao

    private lateinit var binding: ActivityGeofenceManagerBinding
    private var googleMap: GoogleMap? = null
    private val auth = FirebaseAuth.getInstance()
    private val dbRef by lazy {
        val ownerUid = sessionStore.firebaseOwnerUid()
            ?.takeIf { it.isNotBlank() }
            ?: auth.currentUser?.uid
            ?: "unknown"
        FirebaseDatabase.getInstance().getReference("owners")
            .child(ownerUid).child("geofences")
    }

    private lateinit var adapter: GeofenceAdapter
    private val geofenceList = mutableListOf<GeofenceLocation>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeofenceManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clGeofenceManagerRoot, binding.appBar)

        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .getString("user_role", UserRole.STAFF.name).orEmpty()
        if (!role.equals(UserRole.ADMIN.name, true) && !role.equals(UserRole.SUPER_ADMIN.name, true) &&
            !role.equals("Admin", true) && !role.equals("SuperAdmin", true)) {
            Toast.makeText(this, "Only Admin/SuperAdmin can manage geofences.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

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
                    
                    // Cache locally for offline validation
                    val snapshotList = ArrayList(geofenceList)
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            geofenceDao.clearAll()
                            geofenceDao.insertAll(snapshotList)
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

                if (name.isNotEmpty() && lat in -90.0..90.0 && lon in -180.0..180.0 && radius > 0f) {
                    val id = UUID.randomUUID().toString()
                    val newGeofence = GeofenceLocation(id, name, lat, lon, radius)
                    CoroutineScope(Dispatchers.IO).launch { runCatching { firebaseSync.pushGeofenceRaw(id, newGeofence) } }
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
                CoroutineScope(Dispatchers.IO).launch { runCatching { firebaseSync.deleteGeofenceRaw(g.id) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
