package com.biometric.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.dao.LocalEmployeeDao
import com.biometric.app.data.dao.LocalShopDao
import com.biometric.app.data.repository.TrackingConfigurationRepository
import com.biometric.app.data.repository.TrackingScopeRepository
import com.biometric.app.databinding.ActivityTrackingScopeBinding
import com.biometric.app.domain.location.TrackingWindowResolver
import com.biometric.app.sync.FirebaseAuthSecurityGate
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.tasks.await
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TrackingScopeActivity : MotionBaseActivity() {
    private lateinit var binding: ActivityTrackingScopeBinding
    @Inject lateinit var securityGate: FirebaseAuthSecurityGate
    @Inject lateinit var scopeRepository: TrackingScopeRepository
    @Inject lateinit var employeeDao: LocalEmployeeDao
    @Inject lateinit var shopDao: LocalShopDao
    @Inject lateinit var firebaseSync: com.biometric.app.sync.FirebaseSyncManager

    private val scopeTypes = listOf("Employee", "Shop")
    private val modes = listOf(TrackingWindowResolver.MODE_24_7, TrackingWindowResolver.MODE_SHIFT, TrackingWindowResolver.MODE_CUSTOM)
    private var employeeIds = emptyList<String>()
    private var employeeLabels = emptyList<String>()
    private var shopIds = emptyList<String>()
    private var shopLabels = emptyList<String>()
    private var allowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackingScopeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clTrackingScopeRoot, binding.appBar)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.spinnerScopeType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scopeTypes)
        binding.spinnerScopeMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerScopeType.setSelection(0)
        binding.spinnerScopeType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                loadTargets(position == 0)
            }
        }
        binding.spinnerScopeTarget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { loadSelectedAssignment() }
        }
        binding.spinnerScopeMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val custom = modes.getOrElse(position) { modes[0] } == TrackingWindowResolver.MODE_CUSTOM
                binding.editScopeStart.isEnabled = custom
                binding.editScopeEnd.isEnabled = custom
            }
        }
        binding.btnSaveScope.setOnClickListener { saveAssignment() }
        binding.btnClearScope.setOnClickListener { clearAssignment() }
        lifecycleScope.launch { authorizeAndLoad() }
    }

    private suspend fun authorizeAndLoad() {
        val result = securityGate.validateCurrentSession()
        val adminRole = result.role.equals("ADMIN", true) || result.role.equals("SUPER_ADMIN", true) || result.role.equals("Admin", true) || result.role.equals("SuperAdmin", true)
        val adminCanEdit = runCatching {
            firebaseCanAdminEditSettings()
        }.getOrDefault(false)
        allowed = result.allowed && (result.role.equals("SUPER_ADMIN", true) || result.role.equals("SuperAdmin", true) || (adminRole && adminCanEdit))
        if (!allowed) {
            Toast.makeText(this, result.message.ifBlank { "Only Admin or SuperAdmin can manage tracking assignments." }, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadTargets(true)
    }

    private suspend fun firebaseCanAdminEditSettings(): Boolean {
        val role = securityGate.validateCurrentSession().role
        if (role.equals("SUPER_ADMIN", true) || role.equals("SuperAdmin", true)) return true
        val snapshot = firebaseSync.getOwnerRef()?.child("feature_settings")?.child("1")?.get()?.await() ?: return false
        return snapshot.child("adminCanEditSettings").getValue(Boolean::class.java)
            ?: snapshot.child("AdminCanEditSettings").getValue(Boolean::class.java)
            ?: false
    }

    private fun loadTargets(employee: Boolean) {
        if (!allowed) return
        lifecycleScope.launch {
            if (employee) {
                val rows = employeeDao.getAll().filter { it.isActive }.sortedBy { it.name.lowercase() }
                employeeIds = rows.map { it.employeeId }
                employeeLabels = rows.map { "${it.name} (${it.employeeId})" }
                binding.spinnerScopeTarget.adapter = ArrayAdapter(this@TrackingScopeActivity, android.R.layout.simple_spinner_dropdown_item, employeeLabels)
            } else {
                val rows = shopDao.getAllShops().first().sortedBy { it.name.lowercase() }
                shopIds = rows.map { it.shopId }
                shopLabels = rows.map { "${it.name} (${it.shopId})" }
                binding.spinnerScopeTarget.adapter = ArrayAdapter(this@TrackingScopeActivity, android.R.layout.simple_spinner_dropdown_item, shopLabels)
            }
            loadSelectedAssignment()
        }
    }

    private fun selectedTargetId(): String? {
        val employee = binding.spinnerScopeType.selectedItemPosition == 0
        val position = binding.spinnerScopeTarget.selectedItemPosition
        return if (employee) employeeIds.getOrNull(position) else shopIds.getOrNull(position)
    }

    private fun loadSelectedAssignment() {
        lifecycleScope.launch {
            val id = selectedTargetId() ?: return@launch
            val employee = binding.spinnerScopeType.selectedItemPosition == 0
            val assignment = if (employee) scopeRepository.loadEmployeeAssignment(id) else scopeRepository.loadShopAssignment(id)
            if (assignment == null) {
                binding.switchScopeEnabled.isChecked = true
                binding.spinnerScopeMode.setSelection(0)
                binding.editScopeStart.setText("")
                binding.editScopeEnd.setText("")
                binding.editScopeInterval.setText("30")
                binding.textScopeStatus.text = "No override. This target inherits the global configuration."
            } else {
                binding.switchScopeEnabled.isChecked = assignment.enabled
                binding.spinnerScopeMode.setSelection(modes.indexOf(assignment.mode).coerceAtLeast(0))
                binding.editScopeStart.setText(assignment.customStart)
                binding.editScopeEnd.setText(assignment.customEnd)
                binding.editScopeInterval.setText(assignment.intervalSeconds.toString())
                binding.textScopeStatus.text = "Explicit ${if (employee) "employee" else "shop"} override is active."
            }
        }
    }

    private fun saveAssignment() {
        if (!allowed) return
        val id = selectedTargetId() ?: run { Toast.makeText(this, "Select a target first", Toast.LENGTH_SHORT).show(); return }
        val mode = binding.spinnerScopeMode.selectedItem?.toString() ?: modes[0]
        val interval = binding.editScopeInterval.text?.toString()?.trim()?.toIntOrNull() ?: 30
        if (interval !in 15..3600) { binding.editScopeInterval.error = "Enter 15 to 3600 seconds"; return }
        val start = binding.editScopeStart.text?.toString()?.trim().orEmpty()
        val end = binding.editScopeEnd.text?.toString()?.trim().orEmpty()
        if (mode == TrackingWindowResolver.MODE_CUSTOM && (start.isBlank() || end.isBlank())) { Toast.makeText(this, "Custom start and end are required", Toast.LENGTH_LONG).show(); return }
        val assignment = TrackingScopeRepository.Assignment(mode, start, end, interval, binding.switchScopeEnabled.isChecked)
        lifecycleScope.launch {
            runCatching {
                if (binding.spinnerScopeType.selectedItemPosition == 0) scopeRepository.saveEmployeeAssignment(id, assignment)
                else scopeRepository.saveShopAssignment(id, assignment)
            }.onSuccess {
                binding.textScopeStatus.text = "Override saved to Firebase. Effective precedence: Employee → Shop → Global."
                Toast.makeText(this@TrackingScopeActivity, "Tracking assignment saved ✅", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@TrackingScopeActivity, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun clearAssignment() {
        if (!allowed) return
        val id = selectedTargetId() ?: return
        lifecycleScope.launch {
            runCatching {
                if (binding.spinnerScopeType.selectedItemPosition == 0) scopeRepository.clearEmployeeAssignment(id)
                else scopeRepository.clearShopAssignment(id)
            }.onSuccess {
                loadSelectedAssignment()
                Toast.makeText(this@TrackingScopeActivity, "Override cleared. Target inherits the next scope. ✅", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@TrackingScopeActivity, "Clear failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
