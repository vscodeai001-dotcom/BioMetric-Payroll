package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalTaxDeclaration
import com.biometric.app.data.repository.FirebaseAdminFinanceRepository
import com.biometric.app.databinding.ActivityAdminTaxDeclarationsBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AdminTaxDeclarationsActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminTaxDeclarationsBinding

    @Inject lateinit var firebaseFinance: FirebaseAdminFinanceRepository
    @Inject lateinit var firebaseSync: FirebaseSyncManager

    private var allDeclarations = listOf<LocalTaxDeclaration>()
    private var allEmployees = listOf<Employee>()

    private var selectedYear: Int = currentFinancialYear()
    private var filterStatus: String = "All" // "All", "Pending", "Approved", "Rejected"

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminTaxDeclarationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clAdminTaxRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        setupFinancialYearSpinner()
        setupFilterChips()
        setupListeners()
        observeRealtimeDeclarations()
    }

    private fun currentFinancialYear(): Int {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) // 0-based: 0 is Jan, 3 is Apr
        val year = cal.get(Calendar.YEAR)
        return if (month >= 3) year else year - 1
    }

    private fun setupFinancialYearSpinner() {
        val currentYear = currentFinancialYear()
        val years = listOf(
            currentYear to "FY $currentYear-${currentYear + 1} (Current)",
            (currentYear - 1) to "FY ${currentYear - 1}-$currentYear (Previous)",
            (currentYear + 1) to "FY ${currentYear + 1}-${currentYear + 2} (Upcoming)"
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years.map { it.second })
        binding.spFinancialYear.adapter = adapter
        binding.spFinancialYear.setSelection(0)

        binding.spFinancialYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newYear = years[position].first
                if (selectedYear != newYear) {
                    HapticUtil.vibrateClick(binding.spFinancialYear)
                    selectedYear = newYear
                    updateUI()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFilterChips() {
        binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticUtil.vibrateClick(binding.chipGroupStatus)
            filterStatus = when {
                checkedIds.contains(R.id.chipFilterPending) -> "Pending"
                checkedIds.contains(R.id.chipFilterApproved) -> "Approved"
                checkedIds.contains(R.id.chipFilterRejected) -> "Rejected"
                else -> "All"
            }
            renderDeclarations()
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            HapticUtil.vibrateClick(it)
            Toast.makeText(this, "Refreshed live declarations 🔄", Toast.LENGTH_SHORT).show()
            renderDeclarations()
        }
    }

    private fun observeRealtimeDeclarations() {
        lifecycleScope.launch {
            val taxFlow = firebaseSync.getDataFlow<LocalTaxDeclaration>("tax_declarations")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")

            combine(taxFlow, employeeFlow) { taxes, employees ->
                taxes to employees
            }.collectLatest { (taxes, employees) ->
                allDeclarations = taxes
                allEmployees = employees
                binding.tvStatus.text = "Syncing SSOT Realtime • ${taxes.size} total submissions"
                updateUI()
            }
        }
    }

    private fun updateUI() {
        updateKpiDashboard()
        renderDeclarations()
    }

    private fun updateKpiDashboard() {
        val yearDeclarations = allDeclarations.filter { it.financialYear == selectedYear }
        val pendingCount = yearDeclarations.count { it.status.equals("Pending", ignoreCase = true) }
        val approvedCount = yearDeclarations.count { it.status.equals("Approved", ignoreCase = true) }

        binding.tvKpiTotal.text = yearDeclarations.size.toString()
        binding.tvKpiPending.text = pendingCount.toString()
        binding.tvKpiApproved.text = approvedCount.toString()
    }

    private fun renderDeclarations() {
        val container = binding.llRowsContainer
        container.removeAllViews()

        var filtered = allDeclarations.filter { it.financialYear == selectedYear }

        if (filterStatus != "All") {
            filtered = filtered.filter { it.status.equals(filterStatus, ignoreCase = true) }
        }

        val hasData = filtered.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        val inflater = LayoutInflater.from(this)

        filtered.sortedByDescending { it.declarationId }.forEach { dec ->
            val card = inflater.inflate(R.layout.item_admin_tax_declaration_row, container, false)

            val emp = namesMap[dec.employeeId]
            val empName = emp?.name ?: "Employee #${dec.employeeId}"
            val empRole = emp?.role ?: "Staff"

            val tvEmployeeName = card.findViewById<TextView>(R.id.tvEmployeeName)
            val tvEmployeeMeta = card.findViewById<TextView>(R.id.tvEmployeeMeta)
            val tvRegimeBadge = card.findViewById<TextView>(R.id.tvRegimeBadge)
            val tvStatusBadge = card.findViewById<TextView>(R.id.tvStatusBadge)
            val chip80C = card.findViewById<TextView>(R.id.chip80C)
            val chip80D = card.findViewById<TextView>(R.id.chip80D)
            val chipHra = card.findViewById<TextView>(R.id.chipHra)
            val chipOther = card.findViewById<TextView>(R.id.chipOther)
            val tvRemarks = card.findViewById<TextView>(R.id.tvRemarks)
            val llActionsRow = card.findViewById<View>(R.id.llActionsRow)
            val btnApprove = card.findViewById<MaterialButton>(R.id.btnApprove)
            val btnReject = card.findViewById<MaterialButton>(R.id.btnReject)

            tvEmployeeName.text = empName
            tvEmployeeMeta.text = "ID #${dec.employeeId} • $empRole"

            val isNewRegime = dec.regime.equals("New", ignoreCase = true)
            if (isNewRegime) {
                tvRegimeBadge.text = "NEW REGIME"
                tvRegimeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue_light))
                tvRegimeBadge.setTextColor(getColor(R.color.blue))

                chip80C.text = "🛡️ 80C: N/A"
                chip80D.text = "🏥 80D: N/A"
                chipHra.text = "🏠 Rent: N/A"
                chipOther.text = "Default Standard Regime"
            } else {
                tvRegimeBadge.text = "OLD REGIME"
                tvRegimeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue_light))
                tvRegimeBadge.setTextColor(getColor(R.color.colorPrimary))

                chip80C.text = "🛡️ 80C: ${currency.format(dec.section80C)}"
                chip80D.text = "🏥 80D: ${currency.format(dec.section80D)}"
                chipHra.text = "🏠 Rent: ${currency.format(dec.hraRentPaid)}"
                chipOther.text = "🪙 Other: ${currency.format(dec.otherExemptions)}"
            }

            // Status Badge
            when {
                dec.status.equals("Approved", ignoreCase = true) -> {
                    tvStatusBadge.text = "APPROVED"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                    tvStatusBadge.setTextColor(getColor(R.color.green_700))

                    btnApprove.text = "🔓 Revert to Draft"
                    btnApprove.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                    btnApprove.setOnClickListener {
                        HapticUtil.vibrateClick(it)
                        confirmUnlock(dec, empName)
                    }
                    btnReject.isVisible = false
                    llActionsRow.isVisible = true
                }
                dec.status.equals("Rejected", ignoreCase = true) -> {
                    tvStatusBadge.text = "REJECTED"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                    tvStatusBadge.setTextColor(getColor(R.color.red_700))
                    llActionsRow.isVisible = false
                }
                dec.status.equals("Draft", ignoreCase = true) -> {
                    tvStatusBadge.text = "DRAFT"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorSurfaceVariant))
                    tvStatusBadge.setTextColor(getColor(R.color.text_secondary))
                    llActionsRow.isVisible = false
                }
                else -> {
                    // Pending
                    tvStatusBadge.text = "PENDING"
                    tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.amber_light))
                    tvStatusBadge.setTextColor(getColor(R.color.amber_900))

                    btnApprove.text = "✅ Approve"
                    btnApprove.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_700))
                    btnApprove.isVisible = true
                    btnApprove.setOnClickListener {
                        HapticUtil.vibrateClick(it)
                        confirmApprove(dec, empName)
                    }

                    btnReject.text = "❌ Reject"
                    btnReject.isVisible = true
                    btnReject.setOnClickListener {
                        HapticUtil.vibrateClick(it)
                        confirmReject(dec, empName)
                    }
                    llActionsRow.isVisible = true
                }
            }

            if (!dec.adminRemarks.isNullOrBlank()) {
                tvRemarks.isVisible = true
                tvRemarks.text = "Remarks: ${dec.adminRemarks}"
            } else {
                tvRemarks.isVisible = false
            }

            container.addView(card)
        }
    }

    private fun confirmApprove(dec: LocalTaxDeclaration, empName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Approve Tax Declaration? 📑")
            .setMessage("Approve declaration for $empName (${dec.regime} Regime)? This locks the tax regime for statutory payroll deduction.")
            .setPositiveButton("Approve") { _, _ ->
                lifecycleScope.launch {
                    try {
                        firebaseFinance.approveTaxDeclaration(dec.declarationId, "Approved by Admin")
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Tax declaration approved ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Failed to approve: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject(dec: LocalTaxDeclaration, empName: String) {
        val input = EditText(this).apply {
            hint = "Enter reason for rejection"
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Reject Tax Declaration ❌")
            .setMessage("Please specify why the declaration for $empName is being rejected:")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isBlank()) {
                    Toast.makeText(this, "Rejection reason is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        firebaseFinance.rejectTaxDeclaration(dec.declarationId, reason)
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Declaration rejected ⚠️", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Failed to reject: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUnlock(dec: LocalTaxDeclaration, empName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Revert to Draft? 🔓")
            .setMessage("Unlock declaration for $empName to allow the employee to edit and resubmit their tax declarations?")
            .setPositiveButton("Unlock") { _, _ ->
                lifecycleScope.launch {
                    try {
                        firebaseFinance.unlockTaxDeclaration(dec.declarationId)
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Declaration unlocked to Draft 🔓", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminTaxDeclarationsActivity, "Failed to unlock: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
