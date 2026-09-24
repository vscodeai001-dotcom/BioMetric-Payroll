package com.biometric.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminPendingPunchDto
import com.biometric.app.api.MobileApiService
import com.biometric.app.databinding.ActivityPunchCorrectionApprovalBinding
import com.biometric.app.databinding.ItemPunchCorrectionApprovalCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PunchCorrectionApprovalActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityPunchCorrectionApprovalBinding

    @Inject lateinit var api: MobileApiService

    private lateinit var adapter: PunchCorrectionApprovalAdapter
    private val allPunches = mutableListOf<AdminPendingPunchDto>()
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
            binding.swipeRefresh.isRefreshing = false
        }

        binding.btnRefreshPunchApproval.setOnClickListener {
            loadPendingRequests()
        }
    }

    private fun loadPendingRequests() {
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            try {
                val response = api.adminPendingPunches(auth())
                if (response.isSuccessful) {
                    allPunches.clear()
                    allPunches.addAll(response.body().orEmpty())
                    filterAndRender()
                } else {
                    toast("Unable to load punch requests (HTTP ${response.code()}) ⚠️")
                }
            } catch (e: Exception) {
                toast("Network error loading requests: ${e.message} ⚠️")
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun filterAndRender() {
        // Calculate KPI Counters
        val totalPending = allPunches.size
        val clockInCount = allPunches.count { it.logType.contains("IN", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
        val clockOutCount = allPunches.count { it.logType.contains("OUT", ignoreCase = true) && !it.logType.contains("BREAK", ignoreCase = true) }
        val staffCount = allPunches.map { it.employeeId }.distinct().count()

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
        }.sortedByDescending { it.punchTime }

        adapter.submitList(filtered)
        binding.llEmptyState.isVisible = filtered.isEmpty()
        binding.rvPendingPunches.isVisible = filtered.isNotEmpty()
    }

    private fun confirmApprovePunch(punch: AdminPendingPunchDto) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Approve Punch ✅")
            .setMessage("Approve ${punch.logType} punch for ${punch.employeeName} at ${punch.punchTime}?")
            .setPositiveButton("Approve") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val response = api.adminApprovePunch(auth(), punch.id)
                        if (response.isSuccessful) {
                            toast("Punch approved successfully ✅")
                            allPunches.removeAll { it.id == punch.id }
                            filterAndRender()
                        } else {
                            toast("Failed to approve punch (HTTP ${response.code()}) ⚠️")
                        }
                    } catch (e: Exception) {
                        toast("Action failed: ${e.message} ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRejectPunch(punch: AdminPendingPunchDto) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reject Punch ❌")
            .setMessage("Reject and discard ${punch.logType} punch for ${punch.employeeName}?")
            .setPositiveButton("Reject") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val response = api.adminRejectPunch(auth(), punch.id)
                        if (response.isSuccessful) {
                            toast("Punch rejected and removed 🗑️")
                            allPunches.removeAll { it.id == punch.id }
                            filterAndRender()
                        } else {
                            toast("Failed to reject punch (HTTP ${response.code()}) ⚠️")
                        }
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
        private val onApprove: (AdminPendingPunchDto) -> Unit,
        private val onReject: (AdminPendingPunchDto) -> Unit
    ) : RecyclerView.Adapter<PunchCorrectionApprovalAdapter.ViewHolder>() {

        private val items = mutableListOf<AdminPendingPunchDto>()

        fun submitList(newItems: List<AdminPendingPunchDto>) {
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

            fun bind(item: AdminPendingPunchDto) {
                itemBinding.tvEmployeeName.text = item.employeeName
                itemBinding.tvEmployeeSubtitle.text = "Staff #${item.employeeId}"

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
                itemBinding.tvReasonNote.text = "Submitted for admin verification & recalculation"

                // Buttons
                itemBinding.btnApprovePunch.setOnClickListener { onApprove(item) }
                itemBinding.btnRejectPunch.setOnClickListener { onReject(item) }
            }
        }
    }
}
