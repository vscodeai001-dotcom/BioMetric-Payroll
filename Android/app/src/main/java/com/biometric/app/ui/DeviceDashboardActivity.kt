package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.databinding.ActivityDeviceDashboardBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.adapter.DeviceAdapter
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DeviceDashboardActivity : AppCompatActivity() {

    @Inject lateinit var firebaseSync: FirebaseSyncManager

    private lateinit var binding: ActivityDeviceDashboardBinding
    private val dbRef = FirebaseDatabase.getInstance().getReference("owners")

    private lateinit var adapter: DeviceAdapter
    private val deviceList = mutableListOf<UserProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        listenToDevices()
    }

    private fun setupRecyclerView() {
        adapter = DeviceAdapter(deviceList) { user ->
            revokeDevice(user)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
    }

    private fun listenToDevices() {
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    deviceList.clear()
                    for (owner in snapshot.children) {
                        val staffRef = owner.child("user_profiles")
                        if (staffRef.exists()) {
                            for (staff in staffRef.children) {
                                try {
                                    val profile = staff.getValue(UserProfile::class.java)
                                    if (profile != null && profile.deviceId != null) {
                                        deviceList.add(profile)
                                    }
                                } catch (e: Exception) {
                                    Log.e("Devices", "Parse error", e)
                                }
                            }
                        }
                    }
                    adapter.notifyDataSetChanged()
                } catch (e: Exception) {
                    Log.e("Devices", "Query error", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@DeviceDashboardActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun revokeDevice(user: UserProfile) {
        AlertDialog.Builder(this)
            .setTitle("Revoke Device? 📱")
            .setMessage("Remove device binding for ${user.name}? They will need to log in and register a new device.")
            .setPositiveButton("Revoke 🗑️") { _, _ ->
                FirebaseDatabase.getInstance().getReference("user_profiles")
                    .child(user.uid)
                    .child("deviceId")
                    .removeValue()
                    .addOnSuccessListener {
                        firebaseSync.notifyRealtimeChanged("UserProfile", "MODIFIED")
                        Toast.makeText(this, "Device revoked successfully", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
