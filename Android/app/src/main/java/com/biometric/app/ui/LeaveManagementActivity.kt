package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LeaveRequest
import com.biometric.app.databinding.ActivityLeaveManagementBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class LeaveManagementActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLeaveManagementBinding
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    private val allRequests = mutableListOf<LeaveRequest>()
    private val filteredRequests = mutableListOf<LeaveRequest>()
    private var currentTab = "Pending"
    private val apiAuth get() = "Bearer ${sessionStore.token().orEmpty()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaveManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvLeaveRequests.layoutManager = LinearLayoutManager(this)
        binding.rvLeaveRequests.adapter = LeaveAdapter()
        setupTabs()
        listenToRequests()
        binding.fabAddLeave.setOnClickListener { showCreateLeaveDialog() }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { currentTab = tab?.text.toString(); filterRequests() }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun listenToRequests() {
        lifecycleScope.launch {
            repository.allLeaveRequestsFlow.collect {
                allRequests.clear(); allRequests.addAll(it); filterRequests()
            }
        }
    }

    private fun filterRequests() {
        filteredRequests.clear()
        filteredRequests.addAll(allRequests.filter { when (currentTab) {
            "Approved" -> it.status.equals("Approved", true)
            "Rejected" -> it.status.equals("Rejected", true)
            else -> it.status.equals("Pending", true)
        } })
        binding.rvLeaveRequests.adapter?.notifyDataSetChanged()
    }

    private fun updateRequestStatus(request: LeaveRequest, approved: Boolean) {
        val input = EditText(this).apply { hint = "Admin remarks (optional)" }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (approved) "Approve Leave ✅" else "Reject Leave ❌")
            .setMessage("${request.staffName}\n${request.leaveType}\n${formatDate(request.startDate)}")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val r = mobileApi.setAdminLeaveStatus(apiAuth, request.id.toIntOrNull() ?: 0, AdminLeaveStatusRequest(approved, input.text.toString()))
                        if (!r.isSuccessful) throw IllegalStateException("Leave action failed (${r.code()})")
                        toast("Leave ${if (approved) "approved" else "rejected"} ✅")
                    } catch (e: Exception) { toast("Unable to update leave: ${e.message} ⚠️") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCreateLeaveDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 0) }
        val employees = allEmployees()
        val employeeSpinner = Spinner(this)
        employeeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, employees.map { "${it.employeeId} • ${it.name}" })
        val date = EditText(this).apply { hint = "Leave date (yyyy-MM-dd)"; isFocusable = false; setOnClickListener { pickDate(this) } }
        val type = Spinner(this).apply { adapter = ArrayAdapter(this@LeaveManagementActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Paid Leave", "Sick Leave", "Casual Leave", "Loss of Pay (Auto)")) }
        val half = CheckBox(this).apply { text = "Half day 🌗" }
        val notes = EditText(this).apply { hint = "Notes (optional)" }
        box.addView(employeeSpinner); box.addView(date); box.addView(type); box.addView(half); box.addView(notes)
        MaterialAlertDialogBuilder(this).setTitle("Add Approved Leave 🌴")
            .setView(box).setPositiveButton("Save") { _, _ ->
                val emp = employees.getOrNull(employeeSpinner.selectedItemPosition)
                if (emp == null || date.text.isNullOrBlank()) { toast("Employee and date are required ⚠️"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        val req = CreateAdminLeaveRequest(emp.employeeId, date.text.toString(), type.selectedItem.toString(), half.isChecked, notes.text.toString().ifBlank { null })
                        val r = mobileApi.createAdminLeave(apiAuth, req)
                        if (!r.isSuccessful) throw IllegalStateException("Create failed (${r.code()})")
                        toast("Approved leave added ✅")
                    } catch (e: Exception) { toast("Unable to create leave: ${e.message} ⚠️") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun allEmployees() = repository.allEmployeesFlow.value.filter { !it.isDeleted }.sortedBy { it.name }
    private fun pickDate(target: EditText) { val c = Calendar.getInstance(); DatePickerDialog(this, { _, y, m, d -> target.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show() }
    private fun formatDate(v: Long) = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(v))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    inner class LeaveAdapter : RecyclerView.Adapter<LeaveAdapter.VH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_history_row, p, false))
        override fun getItemCount() = filteredRequests.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = filteredRequests[pos]
            h.v.findViewById<TextView>(R.id.tvHistoryIcon).text = if (r.status == "Approved") "✅" else if (r.status == "Rejected") "❌" else "🌴"
            h.v.findViewById<TextView>(R.id.tvHistoryTitle).text = "👤 ${r.staffName} • ${r.leaveType}"
            h.v.findViewById<TextView>(R.id.tvHistoryDate).text = "🗓️ ${formatDate(r.startDate)} - ${formatDate(r.endDate)}"
            h.v.findViewById<TextView>(R.id.tvHistoryReason).apply { text = "📝 ${r.reason}"; visibility = View.VISIBLE }
            h.v.setOnClickListener { if (r.status.equals("Pending", true)) MaterialAlertDialogBuilder(this@LeaveManagementActivity).setTitle("Leave Action").setMessage("Manage ${r.staffName}'s request?").setPositiveButton("Approve") { _, _ -> updateRequestStatus(r, true) }.setNegativeButton("Reject") { _, _ -> updateRequestStatus(r, false) }.setNeutralButton("Close", null).show() }
        }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v)
    }
}
