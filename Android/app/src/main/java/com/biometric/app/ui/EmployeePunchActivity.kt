package com.biometric.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.domain.attendance.EmployeeAttendanceStateMachine
import com.google.android.gms.location.LocationServices
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Standalone Employee punch screen.
 *
 * Uses the same Firebase feature settings and attendance state machine
 * as EmployeeHomeActivity.
 *
 * Manual punching never bypasses the attendance feature rules,
 * geo-fencing rules, or dual-attendance configuration.
 */
@AndroidEntryPoint
class EmployeePunchActivity : AppCompatActivity() {

    @Inject
    lateinit var session: MobileSessionStore

    @Inject
    lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    @Inject
    lateinit var repository: MainRepository

    private var latitude = 0.0
    private var longitude = 0.0
    private var accuracy = 0.0

    private var dashboardLoaded = false

    private var featureState =
        EmployeeAttendanceStateMachine.FeatureState(
            false,
            false,
            false
        )

    private var officeLat = 0.0
    private var officeLon = 0.0
    private var radius = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_employee_punch)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener {
                finish()
            }

        findViewById<MaterialButton>(R.id.btnPunch)
            .setOnClickListener {
                submit()
            }

        locate()
        loadState()
    }

    private fun loadState() {
        lifecycleScope.launch {
            try {
                val data = selfService.dashboard()

                dashboardLoaded = true

                officeLat = data.officeLatitude
                officeLon = data.officeLongitude
                radius = data.geoRadiusMeters

                featureState =
                    EmployeeAttendanceStateMachine.FeatureState(
                        geoFencingEnabled = data.enableGeoFencing,
                        dualAttendanceEnabled = data.enableDualAttendance,
                        automaticGeofencePunchingEnabled =
                            data.enableAutomaticGeofencePunching
                    )

                val state =
                    findViewById<TextView>(R.id.tvPunchState)

                state.text = when {
                    featureState.biometricAttendanceActive ->
                        "Biometric attendance is active 🛡️"

                    featureState.automaticGeofenceAttendanceActive ->
                        "Automatic geofence attendance is active 🛰️"

                    else ->
                        "Manual PUNCH IN / OUT is available 🏢"
                }

                updateEligibility()

            } catch (e: Exception) {

                findViewById<TextView>(R.id.tvPunchState).text =
                    "Unable to load attendance settings ⚠️"

            }
        }
    }

    private fun locate() {

        val fineGranted =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {

            findViewById<TextView>(R.id.tvLocation).text =
                "Location permission is required 📍"

            return
        }

        LocationServices
            .getFusedLocationProviderClient(this)
            .lastLocation
            .addOnSuccessListener { location ->

                if (location != null) {

                    latitude = location.latitude
                    longitude = location.longitude
                    accuracy = location.accuracy.toDouble()

                    findViewById<TextView>(R.id.tvLocation).text =
                        "GPS ready • ±${location.accuracy.toInt()} m 📍"

                } else {

                    findViewById<TextView>(R.id.tvLocation).text =
                        "Waiting for GPS fix… 🛰️"
                }

                updateEligibility()
            }
    }

    private fun updateEligibility() {

        val button =
            findViewById<MaterialButton>(R.id.btnPunch)

        if (!dashboardLoaded ||
            latitude == 0.0 ||
            longitude == 0.0
        ) {
            button.isEnabled = false
            return
        }

        if (!featureState.manualPunchVisible) {
            button.isEnabled = false
            return
        }

        if (officeLat == 0.0 ||
            officeLon == 0.0 ||
            radius <= 0
        ) {
            button.isEnabled = false
            return
        }

        val results = FloatArray(1)

        Location.distanceBetween(
            officeLat,
            officeLon,
            latitude,
            longitude,
            results
        )

        val locationState =
            EmployeeAttendanceStateMachine.LocationState(
                hasLocation = true,
                distanceMeters = results[0].toDouble(),
                allowedRadiusMeters = radius
            )

        button.isEnabled =
            EmployeeAttendanceStateMachine.ManualPunchState(
                feature = featureState,
                location = locationState
            ).canPunch
    }

    private fun submit() {

        lifecycleScope.launch {

            val button =
                findViewById<MaterialButton>(R.id.btnPunch)

            button.isEnabled = false

            try {

                if (!dashboardLoaded) {
                    throw IllegalStateException(
                        "Attendance settings are still loading"
                    )
                }

                if (!featureState.manualPunchVisible) {
                    throw IllegalStateException(
                        "Manual punching is disabled by the current attendance mode"
                    )
                }

                if (latitude == 0.0 ||
                    longitude == 0.0
                ) {
                    throw IllegalStateException(
                        "Waiting for GPS location"
                    )
                }

                if (officeLat == 0.0 ||
                    officeLon == 0.0 ||
                    radius <= 0
                ) {
                    throw IllegalStateException(
                        "Attendance geo-fence configuration is unavailable"
                    )
                }

                val results = FloatArray(1)

                Location.distanceBetween(
                    officeLat,
                    officeLon,
                    latitude,
                    longitude,
                    results
                )

                val locationState =
                    EmployeeAttendanceStateMachine.LocationState(
                        hasLocation = true,
                        distanceMeters = results[0].toDouble(),
                        allowedRadiusMeters = radius,
                        punching = true
                    )

                val decision =
                    EmployeeAttendanceStateMachine.ManualPunchState(
                        featureState,
                        locationState
                    )

                if (!decision.canPunch) {
                    throw IllegalStateException(
                        "You are outside the allowed attendance range or manual punching is disabled"
                    )
                }

                val employeeId =
                    session.employeeId()

                if (employeeId <= 0) {
                    throw IllegalStateException(
                        "Employee session is missing"
                    )
                }

                val nextType =
                    selfService.nextPunchType()

                val punch =
                    AttendancePunch(
                        punchId = UUID.randomUUID().toString(),
                        staffId = employeeId.toString(),
                        date = SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.US
                        ).format(Date()),
                        type = nextType,
                        timestamp = System.currentTimeMillis(),
                        latitude = latitude,
                        longitude = longitude,
                        accuracy = accuracy.toFloat(),
                        deviceId = session.deviceId(),
                        source = "MANUAL",
                        status = "APPROVED"
                    )

                repository.insertPunch(punch)

                Toast.makeText(
                    this@EmployeePunchActivity,
                    "$nextType punch recorded successfully! 💎",
                    Toast.LENGTH_LONG
                ).show()

                finish()

            } catch (e: Exception) {

                Toast.makeText(
                    this@EmployeePunchActivity,
                    "Punch failed: ${
                        e.message ?: "Unable to record punch"
                    } ⚠️",
                    Toast.LENGTH_LONG
                ).show()

                updateEligibility()
            }
        }
    }
}