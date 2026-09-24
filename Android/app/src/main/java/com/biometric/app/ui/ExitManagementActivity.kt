package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.ExitStatusRequest
import com.biometric.app.api.FnFSettlement
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.ResignationRequest
import com.biometric.app.databinding.ActivityExitManagementBinding
import com.biometric.app.databinding.DialogFnfCalculatorBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ExitManagementActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityExitManagementBinding

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var firebaseSync: FirebaseSyncManager

    private var allRequests = listOf<ResignationRequest>()
    private var allEmployees = listOf<Employee>()
    private var filterStatus: String = "All" // "All", "Pending", "Approved", "Settled"

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExitManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clExitManagementRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        setupFilterChips()
        observeRealtimeRequests()
    }

    private fun setupFilterChips() {
        binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticUtil.vibrateClick(binding.chipGroupStatus)
            filterStatus = when {
                checkedIds.contains(R.id.chipFilterPending) -> "Pending"
                checkedIds.contains(R.id.chipFilterApproved) -> "Approved"
                checkedIds.contains(R.id.chipFilterSettled) -> "Settled"
                else -> "All"
            }
            renderRequests()
        }
    }

    private fun observeRealtimeRequests() {
        lifecycleScope.launch {
            val requestFlow = firebaseSync.getDataFlow<ResignationRequest>("resignation_requests")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")

            combine(requestFlow, employeeFlow) { requests, employees ->
                requests to employees
            }.collectLatest { (requests, employees) ->
                allRequests = requests.sortedByDescending { it.submissionDate }
                allEmployees = employees
                updateKpiDashboard()
                renderRequests()
            }
        }
    }

    private fun updateKpiDashboard() {
        val pendingCount = allRequests.count { it.status.equals("Pending", ignoreCase = true) }
        val readyFnFCount = allRequests.count { it.status.equals("Approved", ignoreCase = true) && !it.isSettled }
        val settledCount = allRequests.count { it.isSettled }

        binding.tvKpiPending.text = pendingCount.toString()
        binding.tvKpiReadyFnF.text = readyFnFCount.toString()
        binding.tvKpiSettled.text = settledCount.toString()

        binding.tvStatus.text = "SSOT Realtime • ${allRequests.size} total exit records"
    }

    private fun renderRequests() {
        val container = binding.llExitRequestsContainer
        container.removeAllViews()

        var filtered = allRequests

        when (filterStatus) {
            "Pending" -> filtered = filtered.filter { it.status.equals("Pending", ignoreCase = true) }
            "Approved" -> filtered = filtered.filter { it.status.equals("Approved", ignoreCase = true) && !it.isSettled }
            "Settled" -> filtered = filtered.filter { it.isSettled }
        }

        val hasData = filtered.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val namesMap = allEmployees.associateBy { it.employeeId }
        val inflater = LayoutInflater.from(this)

        filtered.forEach { req ->
            val card = inflater.inflate(R.layout.item_exit_request_card, container, false)

            val emp = namesMap[req.employeeId]
            val empName = emp?.name ?: "Employee #${req.employeeId}"
            val empRole = emp?.role ?: "Staff"

            val tvStaffName = card.findViewById<TextView>(R.id.tvStaffName)
            val tvStaffMeta = card.findViewById<TextView>(R.id.tvStaffMeta)
            val tvStatusBadge = card.findViewById<TextView>(R.id.tvStatusBadge)
            val tvReason = card.findViewById<TextView>(R.id.tvReason)
            val tvDesiredLastDay = card.findViewById<TextView>(R.id.tvDesiredLastDay)
            val tvApprovedLastDay = card.findViewById<TextView>(R.id.tvApprovedLastDay)
            val btnReviewApproval = card.findViewById<MaterialButton>(R.id.btnReviewApproval)
            val btnCalculateFnF = card.findViewById<MaterialButton>(R.id.btnCalculateFnF)
            val tvSettledNotice = card.findViewById<TextView>(R.id.tvSettledNotice)

            tvStaffName.text = empName
            val submitStr = if (req.submissionDate > 0) dateFormat.format(Date(req.submissionDate)) else "Recent"
            tvStaffMeta.text = "Staff ID #${req.employeeId} • $empRole • Submitted $submitStr"

            val reasonText = if (!req.reason.isNullOrBlank()) req.reason else "Personal reasons / career move"
            tvReason.text = "Reason: $reasonText"

            val desiredDateStr = if (req.desiredLastWorkingDay > 0) dateFormat.format(Date(req.desiredLastWorkingDay)) else "N/A"
            tvDesiredLastDay.text = "Desired Exit: $desiredDateStr"

            if (req.approvedLastWorkingDay != null && req.approvedLastWorkingDay!! > 0) {
                tvApprovedLastDay.isVisible = true
                tvApprovedLastDay.text = "Approved Last Day: ${dateFormat.format(Date(req.approvedLastWorkingDay!!))}"
            } else {
                tvApprovedLastDay.isVisible = false
            }

            // Status Badge & Action buttons
            when {
                req.isSettled -> {
                    tvStatusBadge.text = "SETTLED"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                    tvStatusBadge.setTextColor(getColor(R.color.green_700))

                    btnReviewApproval.isVisible = false
                    btnCalculateFnF.isVisible = false
                    tvSettledNotice.isVisible = true
                }
                req.status.equals("Approved", ignoreCase = true) -> {
                    tvStatusBadge.text = "APPROVED"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue_light))
                    tvStatusBadge.setTextColor(getColor(R.color.colorPrimary))

                    btnReviewApproval.isVisible = false
                    btnCalculateFnF.isVisible = true
                    tvSettledNotice.isVisible = false

                    btnCalculateFnF.setOnClickListener {
                        HapticUtil.vibrateClick(it)
                        showFnFCalculator(req, empName)
                    }
                }
                req.status.equals("Rejected", ignoreCase = true) -> {
                    tvStatusBadge.text = "REJECTED"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                    tvStatusBadge.setTextColor(getColor(R.color.red_700))

                    btnReviewApproval.isVisible = false
                    btnCalculateFnF.isVisible = false
                    tvSettledNotice.isVisible = false
                }
                else -> {
                    // Pending
                    tvStatusBadge.text = "PENDING"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.amber_light))
                    tvStatusBadge.setTextColor(getColor(R.color.amber_900))

                    btnReviewApproval.isVisible = true
                    btnCalculateFnF.isVisible = false
                    tvSettledNotice.isVisible = false

                    btnReviewApproval.setOnClickListener {
                        HapticUtil.vibrateClick(it)
                        showApprovalDialog(req, empName)
                    }
                }
            }

            container.addView(card)
        }
    }

    private fun showApprovalDialog(req: ResignationRequest, empName: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_remarks, null)
        val etRemarks = dialogView.findViewById<EditText>(R.id.etRemarks)
        val tvLabel = dialogView.findViewById<TextView>(R.id.tvLabel)
        tvLabel.text = "Review Resignation: $empName"

        val selectedDate = Calendar.getInstance().apply {
            if (req.desiredLastWorkingDay > 0) timeInMillis = req.desiredLastWorkingDay
        }
        val btnDate = dialogView.findViewById<TextView>(R.id.btnDate)
        btnDate.visibility = View.VISIBLE
        btnDate.text = "Approved Last Day: ${dateFormat.format(selectedDate.time)}"

        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    selectedDate.set(y, m, d)
                    btnDate.text = "Approved Last Day: ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Step 1: Approve Resignation 🚪")
            .setView(dialogView)
            .setPositiveButton("Approve") { _, _ ->
                updateStatus(req, "Approved", selectedDate.timeInMillis, etRemarks.text.toString().trim())
            }
            .setNegativeButton("Reject") { _, _ ->
                updateStatus(req, "Rejected", null, etRemarks.text.toString().trim())
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun updateStatus(req: ResignationRequest, status: String, lastDay: Long?, remarks: String) {
        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                val approvedDateStr = if (status == "Approved" && lastDay != null) {
                    isoDateFormat.format(Date(lastDay))
                } else null

                // 1. Update Firebase directly for instant SSOT sync
                val owner = firebaseSync.getOwnerRef()
                if (owner != null && req.requestId.isNotBlank()) {
                    owner.child("resignation_requests").child(req.requestId).updateChildren(
                        mapOf(
                            "status" to status,
                            "approvedLastWorkingDay" to lastDay,
                            "adminRemarks" to remarks
                        )
                    ).await()
                    firebaseSync.notifyRealtimeChanged("ResignationRequest", "UPDATED", req.requestId)
                }

                // 2. Also inform API server
                if (token != null) {
                    val reqIdInt = req.requestId.toIntOrNull() ?: 0
                    if (reqIdInt > 0) {
                        runCatching {
                            mobileApi.updateAdminExit(
                                "Bearer $token",
                                reqIdInt,
                                ExitStatusRequest(status, approvedDateStr, remarks)
                            )
                        }
                    }
                }

                Toast.makeText(this@ExitManagementActivity, "Resignation marked $status ✅", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ExitManagement", "Exit status update failed", e)
                Toast.makeText(this@ExitManagementActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFnFCalculator(req: ResignationRequest, empName: String) {
        val reqIdInt = req.requestId.toIntOrNull() ?: 0
        if (reqIdInt <= 0) {
            Toast.makeText(this, "Invalid request ID", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogFnfCalculatorBinding.inflate(layoutInflater)
        dialogBinding.tvTitle.text = "FnF Settlement: $empName 💰"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Finalize & Terminate", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Admin session expired")
                val response = mobileApi.calculateAdminSettlement("Bearer $token", reqIdInt)
                if (!response.isSuccessful) throw IllegalStateException("Settlement calculation failed (${response.code()})")
                val calculated = response.body() ?: throw IllegalStateException("Settlement calculation returned no data")

                dialogBinding.etUnpaidSalary.setText(String.format(Locale.US, "%.2f", calculated.unpaidSalary))
                dialogBinding.etLeaveEncash.setText(String.format(Locale.US, "%.2f", calculated.leaveEncashment))
                dialogBinding.etGratuity.setText(String.format(Locale.US, "%.2f", calculated.gratuity))
                dialogBinding.etBonus.setText(String.format(Locale.US, "%.2f", calculated.bonusPayable))
                dialogBinding.etNoticeRecovery.setText(String.format(Locale.US, "%.2f", calculated.noticePeriodRecovery))
                dialogBinding.etAssetRecovery.setText(String.format(Locale.US, "%.2f", calculated.assetRecoveryCost))
                dialogBinding.etAdvances.setText(String.format(Locale.US, "%.2f", calculated.outstandingAdvances))

                fun recalcNet(): Double {
                    val unpaid = dialogBinding.etUnpaidSalary.text.toString().toDoubleOrNull() ?: 0.0
                    val encash = dialogBinding.etLeaveEncash.text.toString().toDoubleOrNull() ?: 0.0
                    val gratuity = dialogBinding.etGratuity.text.toString().toDoubleOrNull() ?: 0.0
                    val bonus = dialogBinding.etBonus.text.toString().toDoubleOrNull() ?: 0.0

                    val notice = dialogBinding.etNoticeRecovery.text.toString().toDoubleOrNull() ?: 0.0
                    val advances = dialogBinding.etAdvances.text.toString().toDoubleOrNull() ?: 0.0
                    val asset = dialogBinding.etAssetRecovery.text.toString().toDoubleOrNull() ?: 0.0

                    val net = (unpaid + encash + gratuity + bonus) - (notice + advances + asset)
                    dialogBinding.tvNetPayable.text = "Net Payable: ${currency.format(net)}"
                    return net
                }

                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { recalcNet() }
                    override fun afterTextChanged(s: Editable?) {}
                }

                dialogBinding.etBonus.addTextChangedListener(watcher)
                dialogBinding.etAssetRecovery.addTextChangedListener(watcher)
                recalcNet()

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val finalNet = recalcNet()
                    val bonusVal = dialogBinding.etBonus.text.toString().toDoubleOrNull() ?: 0.0
                    val assetVal = dialogBinding.etAssetRecovery.text.toString().toDoubleOrNull() ?: 0.0

                    val updatedFnF = calculated.copy(
                        bonusPayable = bonusVal,
                        assetRecoveryCost = assetVal,
                        netPayable = finalNet
                    )

                    MaterialAlertDialogBuilder(this@ExitManagementActivity)
                        .setTitle("FINAL WARNING ⚠️")
                        .setMessage("This action is irreversible. It will mark $empName as Terminated, finalize the FnF settlement of ${currency.format(finalNet)}, and close all account balances. Continue?")
                        .setPositiveButton("Finalize & Terminate") { _, _ ->
                            finalizeSettlement(updatedFnF, req, dialog)
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("ExitManagement", "FnF calculation failed", e)
                dialog.dismiss()
                Toast.makeText(this@ExitManagementActivity, "Unable to calculate settlement: ${e.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finalizeSettlement(fnf: FnFSettlement, req: ResignationRequest, parentDialog: DialogInterface) {
        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Admin session expired")
                val response = mobileApi.finalizeAdminSettlement("Bearer $token", fnf)
                if (!response.isSuccessful) throw IllegalStateException("Finalization rejected (${response.code()})")

                // Update Firebase SSOT
                val owner = firebaseSync.getOwnerRef()
                if (owner != null && req.requestId.isNotBlank()) {
                    owner.child("resignation_requests").child(req.requestId).updateChildren(
                        mapOf("isSettled" to true, "status" to "Approved")
                    ).await()
                    firebaseSync.notifyRealtimeChanged("ResignationRequest", "SETTLED", req.requestId)
                }

                Toast.makeText(this@ExitManagementActivity, "Settlement finalized & employee terminated ✅ 🏁", Toast.LENGTH_LONG).show()
                parentDialog.dismiss()
            } catch (e: Exception) {
                Log.e("ExitManagement", "FnF finalization failed", e)
                Toast.makeText(this@ExitManagementActivity, "Finalization failed: ${e.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }
}
