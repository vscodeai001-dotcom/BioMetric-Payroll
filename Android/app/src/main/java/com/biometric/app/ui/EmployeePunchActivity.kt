package com.biometric.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.EmployeePunchRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.google.android.gms.location.LocationServices
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EmployeePunchActivity : AppCompatActivity() {
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore
    private var nextType = "IN"
    private var latitude = 0.0
    private var longitude = 0.0
    private var accuracy = 0.0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_employee_punch)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPunch).setOnClickListener { submit() }
        loadStatus(); locate()
    }
    private fun auth() = session.token()?.let { "Bearer $it" }
    private fun loadStatus(){ val t=auth()?:return; lifecycleScope.launch{ val r=api.punchStatus(t); if(r.isSuccessful){r.body()?.let{nextType=it.nextType;findViewById<TextView>(R.id.tvPunchState).text="Ready for ${it.nextType} punch"}}else findViewById<TextView>(R.id.tvPunchState).text="Unable to read attendance status" } }
    private fun locate(){ if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){findViewById<TextView>(R.id.tvLocation).text="Location permission is required";return};LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener{l->if(l!=null){latitude=l.latitude;longitude=l.longitude;accuracy=l.accuracy.toDouble();findViewById<TextView>(R.id.tvLocation).text="GPS ready • ±${accuracy.toInt()} m"}else findViewById<TextView>(R.id.tvLocation).text="Waiting for GPS fix…"}}
    private fun submit(){ if(latitude==0.0&&longitude==0.0){Toast.makeText(this,"Waiting for GPS location",Toast.LENGTH_SHORT).show();locate();return};val t=auth()?:return;lifecycleScope.launch{findViewById<MaterialButton>(R.id.btnPunch).isEnabled=false;val r=api.punch(t,EmployeePunchRequest(nextType,latitude,longitude,accuracy));if(r.isSuccessful){Toast.makeText(this@EmployeePunchActivity,"${nextType} punch recorded",Toast.LENGTH_LONG).show();finish()}else Toast.makeText(this@EmployeePunchActivity,"Punch failed (${r.code()})",Toast.LENGTH_LONG).show();findViewById<MaterialButton>(R.id.btnPunch).isEnabled=true}}
}
