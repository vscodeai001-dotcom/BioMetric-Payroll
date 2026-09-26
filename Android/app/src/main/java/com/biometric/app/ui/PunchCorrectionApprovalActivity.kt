package com.biometric.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminRegularizationStatusRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.RegularizationRequest
import com.biometric.app.data.repository.FirebaseAdminAttendanceRepository
import com.biometric.app.databinding.ActivityPunchCorrectionApprovalBinding
import com.biometric.app.databinding.ItemPunchCorrectionApprovalCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class PendingCorrectionUiModel(
    val id: String,
    val apiId: Int? = null,
    val employeeId: Int = 0,
    val employeeName: String = "",
    val punchTime: String = "",
    val timestamp: Long = 0L,
    val logType: String = "",
    val deviceId: String = "",
    val reasonNote: String = "",
    val attendancePunch: AttendancePunch? = null,
    val regularizationRequest: RegularizationRequest? = null
)

@AndroidEntryPoint
class PunchCorrectionApprovalActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityPunchCorrectionApprovalBinding

    @Inject lateinit var api: MobileApiService
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var attendanceSafety: FirebaseAdminAttendanceRepository

    private lateinit var adapter: PunchCorrectionApprovalAdapter
    private val allPunches = mutableListOf<PendingCorrectionUiModel>()
    private var filterType: String = "ALL" // ALL, IN, OUT, BREAK

    private fun auth() = "Bearer ${sessionStore.token().orEmpty()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPunchCorrectionApprovalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.clPunchCorrectionApprovalRoot, binding.appBar)

        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        setupListeners()

        loadPendingRequests()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = PunchCorrectionApprovalAdapter(
            onApprove = { punch -> confirmApprovePunch(punch) },
            onReject = { punch -> confirmRejectPunch(punch) }
        )
        binding.rvPendingPunches.layoutManager = LinearLayoutManager(this)
        binding.rvPendingPunches.adapter = adapter
    }

    private fun setupFilterChips() {
        binding.chipGroupPunchFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterType = when (checkedIds.firstOrNull()) {
                R.id.chipInPunches -> "IN"
                R.id.chipOutPunches -> "OUT"
                R.id.chipBreakPunches -> "BREAK"
                else -> "ALL"
            }
            filterAndRender()
        }
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            loadPendingRequests()
        }

        binding.btnRefreshPunchApproval.setOnClickListener {
            loadPendingRequests()
        }
    }

    private fun loadPendingRequests() {
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            val mergedItems = mutableListOf<PendingCorrectionUiModel>()
            val seenKeys = mutableSetOf<String>()

            // 1. Attempt to fetch pending punches from backend API
            try {
                val response = api.adminPendingPunches(auth())
                if (response.isSuccessful) {
                    response.body().orEmpty().forEach { dto ->
                        val key = "api_${dto.id}"
                        if (seenKeys.add(key)) {
                            val parsedTs = runCatching {
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(dto.punchTime)?.time
                                    ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(dto.punchTime)?.time
                            }.getOrNull() ?: 0L

                            mergedItems.add(
                                PendingCorrectionUiModel(
                                    id = key,
                                    apiId = dto.id,
                                    employeeId = dto.employeeId,
                                    employeeName = dto.employeeName.ifBlank { "Staff #${dto.employeeId}" },
                                    punchTime = dto.punchTime,
                                    timestamp = parsedTs,
                                    logType = dto.logType,
                                    deviceId = dto.deviceId,
                                    reasonNote = if (dto.deviceId.isNotBlank()) "Source: ${dto.deviceId}" else "Source: Mobile API"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Graceful fallback for offline / physical device: no scary network error toast
                Log.d("PunchApproval", "Backend API unavailable, using offline/Room cache: ${e.message}")
            }

            // 2. Load from local Room / Firebase repository
            try {
                val employees = repository.allEmployeesFlow.first()
                val empMap = employees.associateBy { it.employeeId }

                // Local Pending AttendancePunches
                val punches = repository.allAttendancePunchesFlow.first()
                val pendingPunches = punches.filter { it.status.equals("PENDING", ignoreCase = true) }
                for (p in pendingPunches) {
                    val key = "punch_${p.punchId}"
                    if (seenKeys.add(key)) {
                        val emp = empMap[p.staffId]
                        val empId = p.staffId.toIntOrNull() ?: 0
                        val empName = emp?.name?.ifBlank { null } ?: "Staff #${p.staffId}"
                        val timeStr = if (p.timestamp > 0) {
                            SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault()).format(Date(p.timestamp))
                        } else {
                            p.date
                        }

                        mergedItems.add(
                            PendingCorrectionUiModel(
                                id = key,
                                apiId = null,
                                employeeId = empId,
                                employeeName = empName,
                                punchTime = timeStr,
                                timestamp = p.timestamp,
                                logType = p.type,
                                deviceId = p.deviceId.ifBlank { p.source },
                                reasonNote = "Source: ${p.source}",
                                attendancePunch = p
                            )
                        )
                    }
                }

                // Local Pending Regularizations
                val regularizations = repository.allRegularizationsFlow.first()
                val pendingRegs = regularizations.filter { it.status.equals("Pending", ignoreCase = true) }
                for (r in pendingRegs) {
                    val key = "reg_${r.id}"
                    if (seenKeys.add(key)) {
                        val emp = empMap[r.staffId] ?: empMap[r.employeeId]
                        val empId = r.staffId.toIntOrNull() ?: r.employeeId.toIntOrNull() ?: 0
                        val empName = r.staffName.ifBlank { emp?.name?.ifBlank { null } ?: "Staff #${r.staffId}" }
                        val timeStr = if (r.requestedTime > 0) {
                            SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault()).format(Date(r.requestedTime))
                        } else {
                            r.date
                        }

                        mergedItems.add(
                            PendingCorrectionUiModel(
                                id = key,
                                apiId = null,
                                employeeId = empId,
                                employeeName = empName,
                                punchTime = timeStr,
                                timestamp = r.requestedTime,
                                logType = r.punchType,
                                deviceId = "Mobile Regularization",
                                reasonNote = if (r.reason.isNotBlank()) "Reason: ${r.reason}" else "Regularization Request",
                                regularizationRequest = r
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("PunchApproval", "Error loading local Room punches/regularizations", e)
            } finally {
                binding.progressBar.isVisible = false
                binding.swipeRefresh.isRefreshing = false
            }

            allPunches.clear()
            allPunches.addAll(mergedItems)
            filterAndRender()
        }
    }

    private fun filterAndRender() {
        // Calculate KPI Counters
        val totalPending = allPunches.size
        val clockInCount = allPunches.count { it.logType.contains("IN", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
        val clockOutCount = allPunches.count { it.logType.contains("OUT", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
        val staffCount = allPunches.map { it.employeeId }.filter { it > 0 }.distinct().count()

        binding.tvTotalPendingCount.text = totalPending.toString()
        binding.tvClockInCount.text = clockInCount.toString()
        binding.tvClockOutCount.text = clockOutCount.toString()
        binding.tvStaffCount.text = staffCount.toString()

        // Apply Chip Filter
        val filtered = when (filterType) {
            "IN" -> allPunches.filter { it.logType.contains("IN", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
            "OUT" -> allPunches.filter { it.logType.contains("OUT", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
            "BREAK" -> allPunches.filter { it.logType.contains("BREAK", ignoreCase = true) }
            else -> allPunches
        }.sortedByDescending { it.timestamp.takeIf { t -> t > 0L } ?: Long.MIN_VALUE }

        adapter.submitList(filtered)
        binding.llEmptyState.isVisible = filtered.isEmpty()
        binding.rvPendingPunches.isVisible = filtered.isNotEmpty()
    }

    private fun confirmApprovePunch(item: PendingCorrectionUiModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Approve Punch ✅")
            .setMessage("Approve ${item.logType} punch for ${item.employeeName} at ${item.punchTime}?")
            .setPositiveButton("Approve") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val punchTimeMs = if (item.timestamp > 0) item.timestamp else System.currentTimeMillis()
                        if (attendanceSafety.isPayrollLocked(item.employeeId, punchTimeMs)) {
                            toast("Payroll is finalized for this month. Cannot modify punches. 🔒")
                            return@launch
                        }

                        // Try API approve if API ID exists
                        if (item.apiId != null && item.apiId > 0) {
                            try {
                                api.adminApprovePunch(auth(), item.apiId)
                            } catch (_: Exception) {}
                        }

                        // If it's a regularization request
                        if (item.regularizationRequest != null) {
                            val reg = item.regularizationRequest
                            repository.updateRegularizationStatus(reg.id, "Approved", "Approved by Admin")

                            val punchDate = if (reg.date.isNotBlank()) reg.date else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(punchTimeMs))
                            val approvedPunch = AttendancePunch(
                                punchId = UUID.randomUUID().toString(),
                                staffId = reg.staffId.ifBlank { reg.employeeId },
                                date = punchDate,
                                type = reg.punchType.uppercase(Locale.US),
                                timestamp = if (reg.requestedTime > 0) reg.requestedTime else punchTimeMs,
                                source = "REGULARIZATION_APPROVED",
                                status = "APPROVED"
                            )
                            repository.insertPunch(approvedPunch)

                            try {
                                api.setAdminRegularizationStatus(
                                    auth(),
                                    reg.id,
                                    AdminRegularizationStatusRequest("Approved", "Approved by Admin")
                                )
                            } catch (_: Exception) {}
                        } else if (item.attendancePunch != null) {
                            val punch = item.attendancePunch
                            repository.insertPunch(punch.copy(status = "APPROVED"))
                        }

                        toast("Punch approved successfully ✅")
                        allPunches.removeAll { it.id == item.id }
                        filterAndRender()
                    } catch (e: Exception) {
                        toast("Action failed: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRejectPunch(item: PendingCorrectionUiModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reject Punch ❌")
            .setMessage("Reject and discard ${item.logType} punch for ${item.employeeName}?")
            .setPositiveButton("Reject") { _, _ ->
                lifecycleScope.launch {
                    try {
                        if (item.apiId != null && item.apiId > 0) {
                            try {
                                api.adminRejectPunch(auth(), item.apiId)
                            } catch (_: Exception) {}
                        }

                        if (item.regularizationRequest != null) {
                            val reg = item.regularizationRequest
                            repository.updateRegularizationStatus(reg.id, "Rejected", "Rejected by Admin")
                            try {
                                api.setAdminRegularizationStatus(
                                    auth(),
                                    reg.id,
                                    AdminRegularizationStatusRequest("Rejected", "Rejected by Admin")
                                )
                            } catch (_: Exception) {}
                        } else if (item.attendancePunch != null) {
                            val punch = item.attendancePunch
                            repository.insertPunch(punch.copy(status = "REJECTED"))
                        }

                        toast("Punch rejected and removed 🗑️")
                        allPunches.removeAll { it.id == item.id }
                        filterAndRender()
                    } catch (e: Exception) {
                        toast("Action failed: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // RECYCLERVIEW ADAPTER
    // ============================================================
    private inner class PunchCorrectionApprovalAdapter(
        private val onApprove: (PendingCorrectionUiModel) -> Unit,
        private val onReject: (PendingCorrectionUiModel) -> Unit
    ) : RecyclerView.Adapter<PunchCorrectionApprovalAdapter.ViewHolder>() {

        private val items = mutableListOf<PendingCorrectionUiModel>()

        fun submitList(newItems: List<PendingCorrectionUiModel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemPunchCorrectionApprovalCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val itemBinding: ItemPunchCorrectionApprovalCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: PendingCorrectionUiModel) {
                itemBinding.tvEmployeeName.text = item.employeeName
                itemBinding.tvEmployeeSubtitle.text = if (item.employeeId > 0) "Staff #${item.employeeId}" else "Staff Member"

                // Log Type Badge
                val logType = item.logType.uppercase(Locale.US)
                itemBinding.tvLogTypeBadge.text = logType

                when {
                    logType.contains("IN") && !logType.contains("BREAK") -> {
                        itemBinding.tvLogTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        itemBinding.tvLogTypeBadge.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    logType.contains("OUT") && !logType.contains("BREAK") -> {
                        itemBinding.tvLogTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                        itemBinding.tvLogTypeBadge.setTextColor(Color.parseColor("#1565C0"))
                    }
                    else -> { // BREAK
                        itemBinding.tvLogTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        itemBinding.tvLogTypeBadge.setTextColor(Color.parseColor("#EF6C00"))
                    }
                }

                // Punch Date & Time
                itemBinding.tvPunchDateTime.text = item.punchTime

                // Device / Source
                val deviceSource = if (item.deviceId.isNotBlank()) {
                    "Device / Source: ${item.deviceId}"
                } else {
                    "Source: Mobile Regularization"
                }
                itemBinding.tvDeviceSource.text = deviceSource

                // Reason / Note
                itemBinding.tvReasonNote.text = item.reasonNote.ifBlank { "Submitted for admin verification & recalculation" }

                // Buttons
                itemBinding.btnApprovePunch.setOnClickListener { onApprove(item) }
                itemBinding.btnRejectPunch.setOnClickListener { onReject(item) }
            }
        }
    }
}
