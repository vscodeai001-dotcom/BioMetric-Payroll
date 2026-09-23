package com.biometric.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.GeofenceLocation
import com.biometric.app.databinding.ActivityPunchBinding
import com.biometric.app.domain.location.GeofenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.biometric.app.data.dao.GeofenceDao
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class PunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPunchBinding
    private var imageCapture: ImageCapture? = null
    
    @Inject lateinit var geofenceManager: GeofenceManager
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var geofenceDao: GeofenceDao
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val auth = FirebaseAuth.getInstance()
    private val dbRef by lazy {
        FirebaseDatabase.getInstance().getReference("owners")
            .child(auth.currentUser?.uid ?: "unknown").child("geofences")
    }

    private val geofences = mutableListOf<GeofenceLocation>()
    private var nearestGeofence: GeofenceLocation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPunchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (allPermissionsGranted()) {
            startCamera()
            loadGeofences()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.btnPunch.setOnClickListener {
            takeSelfieAndPunch()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("PunchActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadGeofences() {
        lifecycleScope.launch(Dispatchers.IO) {
            val localList = runCatching { geofenceDao.getAllActive() }.getOrDefault(emptyList())
            if (localList.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    geofences.clear()
                    geofences.addAll(localList)
                    validateLocation()
                }
            } else {
                // Fallback to single fetch only if local DB is empty
                dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        geofences.clear()
                        for (child in snapshot.children) {
                            child.getValue(GeofenceLocation::class.java)?.let { geofences.add(it) }
                        }
                        validateLocation()
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
        }
    }

    private fun validateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val check = geofenceManager.validatePunchLocation(
                    location.latitude, location.longitude, location.accuracy, geofences
                )
                
                nearestGeofence = check.nearestGeofence
                
                if (check.isInside) {
                    binding.tvLocationStatus.text = "At ${check.nearestGeofence?.name} ✅"
                    binding.btnPunch.isEnabled = true
                } else {
                    binding.tvLocationStatus.text = getString(R.string.outside_geofence)
                    binding.btnPunch.isEnabled = false
                }
            }
        }
    }

    private fun takeSelfieAndPunch() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(externalCacheDir, "${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                performPunch(photoFile.absolutePath)
            }
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(this@PunchActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun performPunch(photoPath: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && nearestGeofence != null) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val punch = AttendancePunch(
                    punchId = UUID.randomUUID().toString(),
                    staffId = auth.currentUser?.uid ?: "unknown",
                    date = date,
                    type = "IN",
                    timestamp = System.currentTimeMillis(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    geofenceId = nearestGeofence?.id,
                    photoId = photoPath,
                    source = "GEOFENCE",
                    status = "APPROVED"
                )
                
                lifecycleScope.launch {
                    repository.insertPunch(punch)
                    Toast.makeText(this@PunchActivity, "Punch Recorded Successfully! 🎉", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                loadGeofences()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
