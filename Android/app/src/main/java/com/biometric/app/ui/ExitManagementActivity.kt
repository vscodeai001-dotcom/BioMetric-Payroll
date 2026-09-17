package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.FnFSettlement
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.ResignationRequest
import com.biometric.app.databinding.ActivityExitManagementBinding
import com.biometric.app.databinding.DialogFnfCalculatorBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ExitManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExitManagementBinding
    
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    private val requests = mutableListOf<ResignationRequest>()
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExitManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        listenToRequests()
    }

    private fun setupRecyclerView() {
        binding.rvExitRequests.layoutManager = LinearLayoutManager(this)
        binding.rvExitRequests.adapter = ExitAdapter()
    }

    private fun listenToRequests() {
        lifecycleScope.launch {
            repository.allResignationRequestsFlow.collectLatest {
                requests.clear()
                requests.addAll(it)
                binding.rvExitRequests.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun handleAction(request: ResignationRequest) {
        when (request.status) {
            "Pending" -> showApprovalDialog(request)
            "Approved" -> if (!request.isSettled) showFnFCalculator(request)
            else -> Toast.makeText(this, "Status: ${request.status}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showApprovalDialog(request: ResignationRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_remarks, null)
        val etRemarks = dialogView.findViewById<EditText>(R.id.etRemarks)
        val tvLabel = dialogView.findViewById<TextView>(R.id.tvLabel)
        tvLabel.text = "Set Last Working Day & Remarks"
        
        val selectedDate = Calendar.getInstance().apply { timeInMillis = request.desiredLastWorkingDay }
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val btnDate = dialogView.findViewById<TextView>(R.id.btnDate)
        btnDate.visibility = View.VISIBLE
        btnDate.text = "Last Day: ${sdf.format(selectedDate.time)}"
        btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                btnDate.text = "Last Day: ${sdf.format(selectedDate.time)}"
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Approve Resignation")
            .setView(dialogView)
            .setPositiveButton("Approve") { _, _ -> updateStatus(request, "Approved", selectedDate.timeInMillis, etRemarks.text.toString()) }
            .setNegativeButton("Reject") { _, _ -> updateStatus(request, "Rejected", null, etRemarks.text.toString()) }
            .show()
    }

    private fun updateStatus(request: ResignationRequest, status: String, lastDay: Long?, remarks: String) {
        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Admin session expired")
                val requestId = request.requestId.toIntOrNull()
                    ?: throw IllegalStateException("Invalid resignation request")
                val approvedDate = if (status == "Approved" && lastDay != null) {
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastDay))
                } else null
                val response = mobileApi.updateAdminExit(
                    "Bearer $token",
                    requestId,
                    com.biometric.app.api.ExitStatusRequest(status, approvedDate, remarks)
                )
                if (!response.isSuccessful) throw IllegalStateException("Server rejected request (${response.code()})")
                Toast.makeText(this@ExitManagementActivity, "Status: $status ✅", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ExitManagement", "Exit status update failed", e)
                Toast.makeText(this@ExitManagementActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFnFCalculator(request: ResignationRequest) {
        val requestId = request.requestId.toIntOrNull()
        if (requestId == null) {
            Toast.makeText(this, "Invalid resignation request", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogFnfCalculatorBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Full & Final Settlement 💰")
            .setView(dialogBinding.root)
            .setPositiveButton("Finalize & Terminate", null)
            .setNegativeButton("Cancel", null)
            .show()

        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Admin session expired")
                val response = mobileApi.calculateAdminSettlement("Bearer $token", requestId)
                if (!response.isSuccessful) throw IllegalStateException("Settlement calculation failed (${response.code()})")
                val calculated = response.body() ?: throw IllegalStateException("Settlement calculation returned no data")

                // The Web ResignationService is authoritative for these calculations.
                // Display the server result read-only; do not recalculate it independently on Android.
                dialogBinding.etUnpaidSalary.setText(String.format(Locale.US, "%.2f", calculated.unpaidSalary))
                dialogBinding.etLeaveEncash.setText(String.format(Locale.US, "%.2f", calculated.leaveEncashment))
                dialogBinding.etGratuity.setText(String.format(Locale.US, "%.2f", calculated.gratuity))
                dialogBinding.etBonus.setText(String.format(Locale.US, "%.2f", calculated.bonusPayable))
                dialogBinding.etNoticeRecovery.setText(String.format(Locale.US, "%.2f", calculated.noticePeriodRecovery))
                dialogBinding.etAssetRecovery.setText(String.format(Locale.US, "%.2f", calculated.assetRecoveryCost))
                dialogBinding.etAdvances.setText(String.format(Locale.US, "%.2f", calculated.outstandingAdvances))
                dialogBinding.tvNetPayable.text = "Net Payable: ${currencyFormat.format(calculated.netPayable)} 💎"

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    MaterialAlertDialogBuilder(this@ExitManagementActivity)
                        .setTitle("FINAL WARNING ⚠️")
                        .setMessage("This will finalize the Full & Final settlement and terminate the employee. Continue?")
                        .setPositiveButton("Yes, Finalize") { _, _ -> finalizeSettlement(calculated, dialog) }
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

    private fun finalizeSettlement(fnf: FnFSettlement, parentDialog: DialogInterface) {
        lifecycleScope.launch {
            try {
                val token = sessionStore.token()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Admin session expired")
                val response = mobileApi.finalizeAdminSettlement("Bearer $token", fnf)
                if (!response.isSuccessful) throw IllegalStateException("Finalization rejected (${response.code()})")
                Toast.makeText(this@ExitManagementActivity, "Settlement finalized & employee terminated ✅💎", Toast.LENGTH_LONG).show()
                parentDialog.dismiss()
            } catch (e: Exception) {
                Log.e("ExitManagement", "FnF finalization failed", e)
                Toast.makeText(this@ExitManagementActivity, "Finalization failed: ${e.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class ExitAdapter : RecyclerView.Adapter<ExitAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val req = requests[position]
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            
            tvIcon.text = "🚪"
            tvTitle.text = "Staff ID: ${req.employeeId}"
            
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            tvDate.text = "Last Day: ${sdf.format(Date(req.desiredLastWorkingDay))}"
            tvReason.text = "${req.status}: ${req.reason ?: "No reason provided"}"
            tvReason.visibility = View.VISIBLE

            holder.itemView.setOnClickListener { handleAction(req) }
        }

        override fun getItemCount() = requests.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
}
