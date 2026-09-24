package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.AdminRemarkRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalFbpDeclaration
import com.biometric.app.databinding.ActivityFbpDeclarationApprovalBinding
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
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FbpDeclarationApprovalActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityFbpDeclarationApprovalBinding

    @Inject lateinit var sync: FirebaseSyncManager
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore
    @Inject lateinit var repo: MainRepository

    private var allEmployees = listOf<Employee>()
    private var pendingDeclarationsMap = mapOf<Int, List<LocalFbpDeclaration>>()

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFbpDeclarationApprovalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clFbpApprovalRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        observeDeclarations()
    }

    private fun observeDeclarations() {
        lifecycleScope.launch {
            val employeeFlow = sync.getDataFlow<Employee>("employees")
            val fbpFlow = sync.getDataFlow<LocalFbpDeclaration>("fbp_declarations")

            combine(employeeFlow, fbpFlow) { employees, declarations ->
                employees to declarations
            }.collectLatest { (employees, declarations) ->
                allEmployees = employees
                pendingDeclarationsMap = declarations
                    .filter { it.status.equals("Submitted", ignoreCase = true) || it.status.equals("Pending", ignoreCase = true) }
                    .groupBy { it.employeeId }

                updateKpiDashboard()
                renderDeclarations()
            }
        }
    }

    private fun updateKpiDashboard() {
        val pendingCount = pendingDeclarationsMap.size
        val totalAllocated = pendingDeclarationsMap.values.sumOf { group ->
            group.sumOf { it.annualAllocatedAmount }
        }

        binding.tvKpiPendingSubmissions.text = pendingCount.toString()
        binding.tvKpiTotalAllocated.text = currency.format(totalAllocated)

        binding.tvStatus.text = "SSOT Realtime • $pendingCount employee submissions pending review"
    }

    private fun renderDeclarations() {
        val container = binding.llDeclarationsContainer
        container.removeAllViews()

        val hasData = pendingDeclarationsMap.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        val inflater = LayoutInflater.from(this)

        pendingDeclarationsMap.values.sortedBy { it.first().employeeId }.forEach { rows ->
            val first = rows.first()
            val empId = first.employeeId
            val fy = first.financialYear
            val totalAllocated = rows.sumOf { it.annualAllocatedAmount }

            val emp = namesMap[empId]
            val empName = emp?.name ?: "Employee #$empId"
            val monthlySalary = emp?.salaryRate ?: 0.0
            // Available allowance pool = 10% of annual salary (standard web business logic)
            val availableAllowance = monthlySalary * 12.0 * 0.10
            val isOverLimit = totalAllocated > availableAllowance + 0.005

            val card = inflater.inflate(R.layout.item_fbp_declaration_approval_row, container, false)

            val tvStaffName = card.findViewById<TextView>(R.id.tvStaffName)
            val tvStaffMeta = card.findViewById<TextView>(R.id.tvStaffMeta)
            val tvFyBadge = card.findViewById<TextView>(R.id.tvFyBadge)
            val tvTotalAllocated = card.findViewById<TextView>(R.id.tvTotalAllocated)
            val tvAvailableAllowance = card.findViewById<TextView>(R.id.tvAvailableAllowance)
            val tvOverLimitWarning = card.findViewById<TextView>(R.id.tvOverLimitWarning)
            val btnLockFbp = card.findViewById<MaterialButton>(R.id.btnLockFbp)
            val btnRejectFbp = card.findViewById<MaterialButton>(R.id.btnRejectFbp)

            tvStaffName.text = empName
            val deptRole = listOfNotNull(emp?.role).filter { it.isNotBlank() }.joinToString(" • ")
            tvStaffMeta.text = "Staff ID #$empId • $deptRole"
            tvFyBadge.text = "FY $fy-${fy + 1}"

            tvTotalAllocated.text = "${currency.format(totalAllocated)} / yr"
            tvAvailableAllowance.text = "${currency.format(availableAllowance)} / yr"

            if (isOverLimit) {
                tvOverLimitWarning.isVisible = true
                tvOverLimitWarning.text = "⚠️ Total allocation (${currency.format(totalAllocated)}) exceeds available limit (${currency.format(availableAllowance)})! Cannot lock."
                btnLockFbp.isEnabled = false
                btnLockFbp.alpha = 0.5f
            } else {
                tvOverLimitWarning.isVisible = false
                btnLockFbp.isEnabled = true
                btnLockFbp.alpha = 1.0f
            }

            btnLockFbp.setOnClickListener {
                HapticUtil.vibrateClick(it)
                confirmApprove(empId, empName, fy, rows)
            }

            btnRejectFbp.setOnClickListener {
                HapticUtil.vibrateClick(it)
                confirmReject(empId, empName, fy, rows)
            }

            container.addView(card)
        }
    }

    private fun confirmApprove(empId: Int, empName: String, fy: Int, rows: List<LocalFbpDeclaration>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock & Approve FBP? 🔒")
            .setMessage("Lock annual FBP allocation for $empName for FY $fy-${fy + 1}? Once locked, this allocation is sealed for payroll calculation.")
            .setPositiveButton("Lock & Approve") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val owner = sync.getOwnerRef()
                        if (owner != null) {
                            rows.forEach { item ->
                                owner.child("fbp_declarations").child(item.declarationId.toString()).updateChildren(
                                    mapOf("status" to "Approved")
                                ).await()
                            }
                            sync.notifyRealtimeChanged("FBPDeclaration", "UPDATED", empId.toString())
                        }

                        runCatching {
                            val auth = "Bearer ${session.token().orEmpty()}"
                            api.approveAdminFbp(auth, empId, fy)
                        }

                        Toast.makeText(this@FbpDeclarationApprovalActivity, "FBP allocation locked & approved for $empName 🔒 ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@FbpDeclarationApprovalActivity, "Approval failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject(empId: Int, empName: String, fy: Int, rows: List<LocalFbpDeclaration>) {
        val input = EditText(this).apply {
            hint = "Enter reason for rejection"
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Reject FBP Allocation ❌")
            .setMessage("Please provide a reason why the FBP declaration for $empName is being rejected:")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val remarks = input.text.toString().trim()
                if (remarks.isBlank()) {
                    Toast.makeText(this, "Rejection reason is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        val owner = sync.getOwnerRef()
                        if (owner != null) {
                            // Update declarations to Rejected or remove so employee can re-submit
                            rows.forEach { item ->
                                owner.child("fbp_declarations").child(item.declarationId.toString()).removeValue().await()
                            }
                            sync.notifyRealtimeChanged("FBPDeclaration", "DELETED", empId.toString())
                        }

                        runCatching {
                            val auth = "Bearer ${session.token().orEmpty()}"
                            api.rejectAdminFbp(auth, empId, fy, AdminRemarkRequest(remarks))
                        }

                        Toast.makeText(this@FbpDeclarationApprovalActivity, "Declaration rejected. Employee can now resubmit ⚠️", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@FbpDeclarationApprovalActivity, "Rejection failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
