package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalBonusRecord
import com.biometric.app.data.repository.FirebaseAdminFinanceRepository
import com.biometric.app.databinding.ActivityAdminBonusBinding
import com.biometric.app.databinding.DialogAdminIssueBonusBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminBonusActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminBonusBinding

    @Inject lateinit var firebaseFinance: FirebaseAdminFinanceRepository
    @Inject lateinit var firebaseSync: FirebaseSyncManager

    private var allBonuses = listOf<LocalBonusRecord>()
    private var allEmployees = listOf<Employee>()

    private var selectedEmployeeId = 0 // 0 means All
    private var filterStatus: String = "All" // "All", "Pending", "Paid"

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
    }
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBonusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clAdminBonusRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        setupFilterChips()
        setupListeners()
        observeRealtimeBonuses()
    }

    private fun setupFilterChips() {
        binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticUtil.vibrateClick(binding.chipGroupStatus)
            filterStatus = when {
                checkedIds.contains(R.id.chipFilterPending) -> "Pending"
                checkedIds.contains(R.id.chipFilterPaid) -> "Paid"
                else -> "All"
            }
            renderBonuses()
        }
    }

    private fun setupListeners() {
        binding.fabIssueBonus.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showIssueBonusDialog()
        }
        binding.btnIssueBonusTop.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showIssueBonusDialog()
        }
    }

    private fun observeRealtimeBonuses() {
        lifecycleScope.launch {
            val bonusFlow = firebaseSync.getDataFlow<LocalBonusRecord>("bonus_records")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")

            combine(bonusFlow, employeeFlow) { bonuses, employees ->
                bonuses to employees
            }.collectLatest { (bonuses, employees) ->
                allBonuses = bonuses.sortedByDescending { it.bonusDate }
                allEmployees = employees.sortedBy { it.name }

                setupEmployeeSpinner()
                updateKpiDashboard()
                renderBonuses()
            }
        }
    }

    private fun setupEmployeeSpinner() {
        val options = mutableListOf("👤 All Staff Members")
        options.addAll(allEmployees.map { "👤 ${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spEmployeeFilter.adapter = adapter

        if (selectedEmployeeId > 0) {
            val idx = allEmployees.indexOfFirst { (it.employeeId.toIntOrNull() ?: 0) == selectedEmployeeId }
            if (idx >= 0) {
                binding.spEmployeeFilter.setSelection(idx + 1, false)
            }
        }

        binding.spEmployeeFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newId = if (position == 0) {
                    0
                } else {
                    allEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull() ?: 0
                }
                if (selectedEmployeeId != newId) {
                    HapticUtil.vibrateClick(binding.spEmployeeFilter)
                    selectedEmployeeId = newId
                    renderBonuses()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateKpiDashboard() {
        val totalAmount = allBonuses.sumOf { it.amount }
        val pendingCount = allBonuses.count { it.payrollIdPaid == null || it.payrollIdPaid <= 0 }
        val paidCount = allBonuses.count { it.payrollIdPaid != null && it.payrollIdPaid > 0 }

        binding.tvKpiTotalAmount.text = currency.format(totalAmount)
        binding.tvKpiPendingPayroll.text = pendingCount.toString()
        binding.tvKpiPaidPayroll.text = paidCount.toString()

        binding.tvStatus.text = "Syncing SSOT Realtime • ${allBonuses.size} total bonus records"
    }

    private fun renderBonuses() {
        val container = binding.llBonusRowsContainer
        container.removeAllViews()

        var filtered = allBonuses

        // 1. Employee Filter
        if (selectedEmployeeId > 0) {
            filtered = filtered.filter { it.employeeId == selectedEmployeeId }
        }

        // 2. Status Filter
        when (filterStatus) {
            "Pending" -> filtered = filtered.filter { it.payrollIdPaid == null || it.payrollIdPaid <= 0 }
            "Paid" -> filtered = filtered.filter { it.payrollIdPaid != null && it.payrollIdPaid > 0 }
        }

        val hasData = filtered.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        val namesMapStr = allEmployees.associateBy { it.employeeId }
        val inflater = LayoutInflater.from(this)

        filtered.forEach { bonus ->
            val card = inflater.inflate(R.layout.item_admin_bonus_row, container, false)

            val emp = namesMap[bonus.employeeId] ?: namesMapStr[bonus.employeeId.toString()]
            val empName = emp?.name ?: "Employee #${bonus.employeeId}"
            val empRole = emp?.role ?: "Staff"

            val tvStaffName = card.findViewById<TextView>(R.id.tvStaffName)
            val tvBonusMeta = card.findViewById<TextView>(R.id.tvBonusMeta)
            val tvBonusAmount = card.findViewById<TextView>(R.id.tvBonusAmount)
            val tvBonusDescription = card.findViewById<TextView>(R.id.tvBonusDescription)
            val tvStatusBadge = card.findViewById<TextView>(R.id.tvStatusBadge)
            val btnDeleteBonus = card.findViewById<MaterialButton>(R.id.btnDeleteBonus)

            tvStaffName.text = empName
            val dateStr = if (bonus.bonusDate > 0) dateFormat.format(Date(bonus.bonusDate)) else "Today"
            tvBonusMeta.text = "Staff ID #${bonus.employeeId} • $empRole • $dateStr"
            tvBonusAmount.text = "+${currency.format(bonus.amount)}"

            val desc = if (!bonus.description.isNullOrBlank()) bonus.description else "One-time bonus payment"
            tvBonusDescription.text = "Reason: $desc"

            val isPaid = bonus.payrollIdPaid != null && bonus.payrollIdPaid > 0
            if (isPaid) {
                tvStatusBadge.text = "PAID IN PAYROLL #${bonus.payrollIdPaid}"
                tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                tvStatusBadge.setTextColor(getColor(R.color.green_700))
                btnDeleteBonus.isVisible = false
            } else {
                tvStatusBadge.text = "PENDING PAYROLL INCLUSION"
                tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.amber_light))
                tvStatusBadge.setTextColor(getColor(R.color.amber_900))
                btnDeleteBonus.isVisible = true

                btnDeleteBonus.setOnClickListener {
                    HapticUtil.vibrateClick(it)
                    confirmDeleteBonus(bonus, empName)
                }
            }

            container.addView(card)
        }
    }

    private fun showIssueBonusDialog() {
        val dialogBinding = DialogAdminIssueBonusBinding.inflate(layoutInflater)
        val cal = Calendar.getInstance()

        dialogBinding.etDialogBonusDate.setText(dateFormat.format(cal.time))
        var selectedDateIso = isoDateFormat.format(cal.time)

        dialogBinding.etDialogBonusDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    cal.set(year, month, day)
                    dialogBinding.etDialogBonusDate.setText(dateFormat.format(cal.time))
                    selectedDateIso = isoDateFormat.format(cal.time)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val empOptions = mutableListOf("-- Select Employee --")
        empOptions.addAll(allEmployees.map { "${it.name} (#${it.employeeId})" })
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, empOptions)
        dialogBinding.spDialogEmployee.adapter = adapter

        MaterialAlertDialogBuilder(this)
            .setTitle("Issue New Bonus 🎁")
            .setView(dialogBinding.root)
            .setPositiveButton("Issue Bonus") { _, _ ->
                val selectedPos = dialogBinding.spDialogEmployee.selectedItemPosition
                if (selectedPos <= 0) {
                    Toast.makeText(this, "Please select an employee", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val targetEmployee = allEmployees[selectedPos - 1]
                val empId = targetEmployee.employeeId.toIntOrNull() ?: 0
                val amountStr = dialogBinding.etDialogBonusAmount.text.toString().trim()
                val amount = amountStr.toDoubleOrNull() ?: 0.0

                if (empId <= 0 || amount <= 0.0) {
                    Toast.makeText(this, "Valid employee and amount (> 0) required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val description = dialogBinding.etDialogBonusDescription.text.toString().trim()
                    .ifBlank { "Performance Bonus" }

                lifecycleScope.launch {
                    try {
                        firebaseFinance.createBonus(empId, amount, description, selectedDateIso)
                        Toast.makeText(this@AdminBonusActivity, "Bonus of ${currency.format(amount)} issued to ${targetEmployee.name} 🎁 ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminBonusActivity, "Failed to issue bonus: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBonus(bonus: LocalBonusRecord, empName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Bonus Record? 🗑️")
            .setMessage("Are you sure you want to delete the bonus of ${currency.format(bonus.amount)} for $empName? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        firebaseFinance.deleteBonus(bonus.bonusId, bonus.firebaseKey)
                        Toast.makeText(this@AdminBonusActivity, "Bonus record deleted 🗑️", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminBonusActivity, "Failed to delete: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
