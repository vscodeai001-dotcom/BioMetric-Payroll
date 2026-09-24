package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminLeaveStatusRequest
import com.biometric.app.api.CreateAdminLeaveRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LeaveRequest
import com.biometric.app.databinding.ActivityLeaveManagementBinding
import com.biometric.app.databinding.DialogAdminGrantLeaveBinding
import com.biometric.app.databinding.ItemAdminLeaveCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class LeaveManagementActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityLeaveManagementBinding

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService

    private lateinit var adapter: LeaveAdapter

    private val allRequests = mutableListOf<LeaveRequest>()
    private val allEmployees = mutableListOf<Employee>()

    private var currentTab = "Pending"
    private var filterEmployeeId: Int? = null

    private var filterStartDate = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var filterEndDate = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
    }

    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val apiAuth get() = "Bearer ${sessionStore.token().orEmpty()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaveManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.clLeaveManagementRoot, binding.appBar)

        setupToolbar()
        setupDatePickers()
        setupTabs()
        setupRecyclerView()
        setupListeners()

        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDatePickers() {
        binding.tvFilterStartDate.text = displayDateFormat.format(filterStartDate.time)
        binding.tvFilterEndDate.text = displayDateFormat.format(filterEndDate.time)

        binding.cardFilterStartDate.setOnClickListener {
            showDatePicker(filterStartDate) { picked ->
                filterStartDate = picked
                binding.tvFilterStartDate.text = displayDateFormat.format(filterStartDate.time)
                filterAndRender()
            }
        }

        binding.cardFilterEndDate.setOnClickListener {
            showDatePicker(filterEndDate) { picked ->
                filterEndDate = picked
                binding.tvFilterEndDate.text = displayDateFormat.format(filterEndDate.time)
                filterAndRender()
            }
        }
    }

    private fun showDatePicker(base: Calendar, onDateSelected: (Calendar) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val result = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                onDateSelected(result)
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.text?.toString() ?: "Pending"
                filterAndRender()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun setupRecyclerView() {
        adapter = LeaveAdapter(
            onApprove = { req -> updateRequestStatus(req, approved = true) },
            onReject = { req -> updateRequestStatus(req, approved = false) },
            onDelete = { req -> confirmDeleteRequest(req) }
        )
        binding.rvLeaveRequests.layoutManager = LinearLayoutManager(this)
        binding.rvLeaveRequests.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            filterAndRender()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.fabAddLeave.setOnClickListener {
            showGrantLeaveDialog()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.allLeaveRequestsFlow.collectLatest { list ->
                allRequests.clear()
                allRequests.addAll(list)
                filterAndRender()
            }
        }

        lifecycleScope.launch {
            repository.allEmployeesFlow.collectLatest { employees ->
                allEmployees.clear()
                allEmployees.addAll(employees.filter { it.isActive }.sortedBy { it.name })

                val items = mutableListOf("All Employees")
                items += allEmployees.map { "${it.name} (#${it.employeeId})" }
                binding.spFilterEmployee.adapter = ArrayAdapter(
                    this@LeaveManagementActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items
                )
            }
        }

        binding.spFilterEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                filterEmployeeId = null
                filterAndRender()
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterEmployeeId = allEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull()
                filterAndRender()
            }
        }
    }

    private fun filterAndRender() {
        // Date range boundaries
        val startMs = Calendar.getInstance().apply {
            time = filterStartDate.time
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endMs = Calendar.getInstance().apply {
            time = filterEndDate.time
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        // Employee & Date filter
        val inScope = allRequests.filter { req ->
            val matchesEmployee = filterEmployeeId == null || req.staffId.toIntOrNull() == filterEmployeeId
            val matchesDate = req.startDate in startMs..endMs || req.endDate in startMs..endMs
            matchesEmployee && matchesDate
        }

        // Compute KPI counters
        val pendingCount = inScope.count { it.status.equals("Pending", ignoreCase = true) }
        val approvedCount = inScope.count { it.status.equals("Approved", ignoreCase = true) }
        val rejectedCount = inScope.count { it.status.equals("Rejected", ignoreCase = true) }
        val totalCount = inScope.size

        binding.tvPendingCount.text = pendingCount.toString()
        binding.tvApprovedCount.text = approvedCount.toString()
        binding.tvRejectedCount.text = rejectedCount.toString()
        binding.tvTotalCount.text = totalCount.toString()

        // Tab filter
        val tabFiltered = when (currentTab) {
            "Approved" -> inScope.filter { it.status.equals("Approved", ignoreCase = true) }
            "Rejected" -> inScope.filter { it.status.equals("Rejected", ignoreCase = true) }
            "All" -> inScope
            else -> inScope.filter { it.status.equals("Pending", ignoreCase = true) }
        }.sortedByDescending { it.startDate }

        adapter.submitList(tabFiltered)
        binding.llEmptyState.isVisible = tabFiltered.isEmpty()
        binding.rvLeaveRequests.isVisible = tabFiltered.isNotEmpty()
    }

    private fun updateRequestStatus(request: LeaveRequest, approved: Boolean) {
        val input = EditText(this).apply {
            hint = "Admin remarks (optional)"
            setPadding(32, 16, 32, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (approved) "Approve Leave ✅" else "Reject Leave ❌")
            .setMessage("Staff: ${request.staffName}\nType: ${request.leaveType}\nPeriod: ${formatDate(request.startDate)} to ${formatDate(request.endDate)}")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val remarks = input.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        val reqId = request.id.toIntOrNull() ?: 0
                        val r = mobileApi.setAdminLeaveStatus(
                            apiAuth,
                            reqId,
                            AdminLeaveStatusRequest(approved, remarks.ifBlank { null })
                        )
                        if (!r.isSuccessful) {
                            throw IllegalStateException("Server returned HTTP ${r.code()}")
                        }
                        // Update local repository memory if needed
                        request.status = if (approved) "Approved" else "Rejected"
                        request.adminNotes = remarks
                        filterAndRender()
                        toast("Leave request ${if (approved) "Approved ✅" else "Rejected ❌"}")
                    } catch (e: Exception) {
                        toast("Unable to update leave: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteRequest(request: LeaveRequest) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Revoke / Delete Leave Record 🗑️")
            .setMessage("Are you sure you want to delete the leave record for ${request.staffName} (${formatDate(request.startDate)})?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val reqId = request.id.toIntOrNull() ?: 0
                        val r = mobileApi.deleteAdminLeave(apiAuth, reqId)
                        if (!r.isSuccessful) {
                            throw IllegalStateException("Server returned HTTP ${r.code()}")
                        }
                        allRequests.removeAll { it.id == request.id }
                        filterAndRender()
                        toast("Leave record deleted / revoked 🗑️")
                    } catch (e: Exception) {
                        toast("Unable to delete record: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGrantLeaveDialog() {
        val dialogBinding = DialogAdminGrantLeaveBinding.inflate(layoutInflater)

        // Setup employee spinner
        val empNames = allEmployees.map { "${it.name} (#${it.employeeId})" }
        dialogBinding.spDialogEmployee.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            empNames
        )

        // Setup leave types
        val leaveTypes = listOf("Paid Leave", "Sick Leave", "Casual Leave", "Comp Off", "Loss of Pay (Auto)")
        dialogBinding.spDialogLeaveType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            leaveTypes
        )

        var dialogStart = Calendar.getInstance()
        var dialogEnd = Calendar.getInstance()

        dialogBinding.tvDialogStartDate.text = isoDateFormat.format(dialogStart.time)
        dialogBinding.tvDialogEndDate.text = isoDateFormat.format(dialogEnd.time)

        dialogBinding.cardDialogStartDate.setOnClickListener {
            showDatePicker(dialogStart) { picked ->
                dialogStart = picked
                dialogBinding.tvDialogStartDate.text = isoDateFormat.format(dialogStart.time)
                if (dialogEnd.before(dialogStart)) {
                    dialogEnd = dialogStart.clone() as Calendar
                    dialogBinding.tvDialogEndDate.text = isoDateFormat.format(dialogEnd.time)
                }
            }
        }

        dialogBinding.cardDialogEndDate.setOnClickListener {
            showDatePicker(dialogEnd) { picked ->
                dialogEnd = picked
                dialogBinding.tvDialogEndDate.text = isoDateFormat.format(dialogEnd.time)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Grant Leave ✅") { _, _ ->
                val selectedEmp = allEmployees.getOrNull(dialogBinding.spDialogEmployee.selectedItemPosition)
                if (selectedEmp == null) {
                    toast("Please select an employee ⚠️")
                    return@setPositiveButton
                }

                val empId = selectedEmp.employeeId.toIntOrNull() ?: 0
                val leaveDateStr = dialogBinding.tvDialogStartDate.text.toString()
                val leaveTypeStr = dialogBinding.spDialogLeaveType.selectedItem.toString()
                val isHalf = dialogBinding.switchHalfDay.isChecked
                val notes = dialogBinding.etDialogNotes.text?.toString()?.trim()

                lifecycleScope.launch {
                    try {
                        val req = CreateAdminLeaveRequest(
                            employeeId = empId,
                            leaveDate = leaveDateStr,
                            leaveType = leaveTypeStr,
                            isHalfDay = isHalf,
                            notes = notes?.ifBlank { null }
                        )
                        val r = mobileApi.createAdminLeave(apiAuth, req)
                        if (!r.isSuccessful) {
                            throw IllegalStateException("Server returned HTTP ${r.code()}")
                        }
                        toast("Approved leave granted for ${selectedEmp.name} ✅")
                    } catch (e: Exception) {
                        toast("Unable to grant leave: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDate(v: Long): String = displayDateFormat.format(Date(v))

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // RECYCLERVIEW ADAPTER
    // ============================================================
    private inner class LeaveAdapter(
        private val onApprove: (LeaveRequest) -> Unit,
        private val onReject: (LeaveRequest) -> Unit,
        private val onDelete: (LeaveRequest) -> Unit
    ) : RecyclerView.Adapter<LeaveAdapter.LeaveViewHolder>() {

        private val items = mutableListOf<LeaveRequest>()

        fun submitList(newList: List<LeaveRequest>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LeaveViewHolder {
            val itemBinding = ItemAdminLeaveCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return LeaveViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: LeaveViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class LeaveViewHolder(private val itemBinding: ItemAdminLeaveCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: LeaveRequest) {
                itemBinding.tvStaffName.text = item.staffName.ifBlank { "Staff #${item.staffId}" }
                itemBinding.tvStaffIdSubtitle.text = "Staff ID: ${item.staffId}"

                // Status Badge Color
                val status = item.status.uppercase(Locale.US)
                itemBinding.tvStatusBadge.text = status

                when {
                    item.status.equals("Approved", ignoreCase = true) -> {
                        itemBinding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        itemBinding.tvStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                        itemBinding.llPendingActions.isVisible = false
                        itemBinding.btnRevokeOrDelete.isVisible = true
                    }
                    item.status.equals("Rejected", ignoreCase = true) -> {
                        itemBinding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                        itemBinding.tvStatusBadge.setTextColor(Color.parseColor("#C62828"))
                        itemBinding.llPendingActions.isVisible = false
                        itemBinding.btnRevokeOrDelete.isVisible = true
                    }
                    else -> { // Pending
                        itemBinding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        itemBinding.tvStatusBadge.setTextColor(Color.parseColor("#EF6C00"))
                        itemBinding.llPendingActions.isVisible = true
                        itemBinding.btnRevokeOrDelete.isVisible = false
                    }
                }

                // Leave Type Chip
                itemBinding.tvLeaveTypeChip.text = when {
                    item.leaveType.contains("Paid", ignoreCase = true) -> "🏖️ ${item.leaveType}"
                    item.leaveType.contains("Sick", ignoreCase = true) -> "🤒 ${item.leaveType}"
                    else -> "🟣 ${item.leaveType}"
                }

                // Half Day Badge
                itemBinding.tvHalfDayBadge.isVisible = item.isHalfDay

                // Calculate Days Count
                val diffMs = kotlin.math.max(0L, item.endDate - item.startDate)
                val days = (TimeUnit.MILLISECONDS.toDays(diffMs) + 1).toInt()
                itemBinding.tvLeaveDaysCount.text = if (item.isHalfDay) "0.5 Day" else "$days ${if (days == 1) "Day" else "Days"}"

                // Date Range Text
                val startFormatted = formatDate(item.startDate)
                val endFormatted = formatDate(item.endDate)
                itemBinding.tvLeaveDates.text = if (startFormatted == endFormatted) {
                    "🗓️ $startFormatted"
                } else {
                    "🗓️ $startFormatted to $endFormatted"
                }

                // Reason & Notes
                itemBinding.tvReasonText.text = item.reason.ifBlank { "No reason provided" }
                if (!item.adminNotes.isNullOrBlank()) {
                    itemBinding.tvAdminNotesText.isVisible = true
                    itemBinding.tvAdminNotesText.text = "Admin Note: ${item.adminNotes}"
                } else {
                    itemBinding.tvAdminNotesText.isVisible = false
                }

                // Click Listeners
                itemBinding.btnApproveLeave.setOnClickListener { onApprove(item) }
                itemBinding.btnRejectLeave.setOnClickListener { onReject(item) }
                itemBinding.btnRevokeOrDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
