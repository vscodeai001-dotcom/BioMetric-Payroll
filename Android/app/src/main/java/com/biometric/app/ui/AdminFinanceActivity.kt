package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.repository.FirebaseAdminFinanceRepository
import com.biometric.app.databinding.ActivityAdminFinanceBinding
import com.biometric.app.databinding.DialogAdminMoneyEntryBinding
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminFinanceActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminFinanceBinding

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var firebaseFinance: FirebaseAdminFinanceRepository
    @Inject lateinit var firebaseSync: com.biometric.app.sync.FirebaseSyncManager

    private var allAdvances = listOf<AdvancePayment>()
    private var allEmployees = listOf<Employee>()

    private var currentTab = 0 // 0: Pending Recovery (Unpaid), 1: Full History
    private var selectedEmployeeId = 0 // 0: All

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminFinanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clAdminFinanceRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        setupTabs()
        setupListeners()
        observeAdvancesRealtime()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("⚠️ Pending Deduction"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("📜 Full History"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                HapticUtil.vibrateClick(binding.tabLayout)
                currentTab = tab?.position ?: 0
                renderAdvances()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupListeners() {
        binding.fabAddEntry.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showIssueAdvanceDialog()
        }
    }

    private fun observeAdvancesRealtime() {
        lifecycleScope.launch {
            val advanceFlow = firebaseSync.getDataFlow<AdvancePayment>("advance_payments")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")

            combine(advanceFlow, employeeFlow) { advances, employees ->
                advances to employees
            }.collectLatest { (advances, employees) ->
                allAdvances = advances.sortedByDescending { it.date }
                allEmployees = employees.sortedBy { it.name }

                setupEmployeeSpinner()
                updateKpiDashboard()
                renderAdvances()
            }
        }
    }

    private fun setupEmployeeSpinner() {
        val options = mutableListOf("👤 All Staff Members")
        options.addAll(allEmployees.map { "👤 ${it.name} (#${it.employeeId})" })

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spEmployeeFilter.adapter = adapter

        binding.spEmployeeFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedEmployeeId = if (position == 0) {
                    0
                } else {
                    allEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull() ?: 0
                }
                renderAdvances()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateKpiDashboard() {
        val unpaidAdvances = allAdvances.filter { !it.isRecovered }
        val totalUnpaid = unpaidAdvances.sumOf { it.amount }
        val distinctStaffWithAdvances = unpaidAdvances.map { it.employeeId }.distinct().size

        binding.tvKpiPendingAmount.text = currency.format(totalUnpaid)
        binding.tvKpiStaffCount.text = "$distinctStaffWithAdvances Staff"

        // Update tab titles with live count badges
        binding.tabLayout.getTabAt(0)?.text = "⚠️ Pending (${unpaidAdvances.size})"
        binding.tabLayout.getTabAt(1)?.text = "📜 Full History (${allAdvances.size})"
    }

    private fun renderAdvances() {
        val container = binding.llAdvancesContainer
        container.removeAllViews()

        var filtered = allAdvances

        // 1. Tab filter
        if (currentTab == 0) {
            filtered = filtered.filter { !it.isRecovered }
        }

        // 2. Employee filter
        if (selectedEmployeeId > 0) {
            filtered = filtered.filter { it.employeeId == selectedEmployeeId.toString() }
        }

        val hasData = filtered.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }.mapValues { it.value.name }
        val inflater = LayoutInflater.from(this)

        filtered.forEach { adv ->
            val card = inflater.inflate(R.layout.item_salary_advance_row, container, false)

            val empIdInt = adv.employeeId.toIntOrNull() ?: 0
            val staffName = namesMap[empIdInt] ?: "Staff #${adv.employeeId}"

            card.findViewById<TextView>(R.id.tvStaffName).text = staffName
            card.findViewById<TextView>(R.id.tvAdvanceMeta).text = "Staff ID #${adv.employeeId} • ${dateFormat.format(Date(adv.date))}"
            card.findViewById<TextView>(R.id.tvAdvanceAmount).text = currency.format(adv.amount)

            val tvBadge = card.findViewById<TextView>(R.id.tvStatusBadge)
            val btnDelete = card.findViewById<MaterialButton>(R.id.btnDeleteAdvance)

            if (adv.isRecovered) {
                tvBadge.text = "RECOVERED IN PAYROLL"
                tvBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                tvBadge.setTextColor(getColor(R.color.green_700))
                btnDelete.isVisible = false
            } else {
                tvBadge.text = "PENDING DEDUCTION"
                tvBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                tvBadge.setTextColor(getColor(R.color.red_700))
                btnDelete.isVisible = true

                btnDelete.setOnClickListener {
                    HapticUtil.vibrateClick(it)
                    confirmVoidAdvance(adv, staffName)
                }
            }

            val reasonText = if (!adv.recoveryPaymentId.isNullOrBlank()) {
                "Auto-recovered in Payroll Run: ${adv.recoveryPaymentId}"
            } else {
                "Reason: Advance wage issuance"
            }
            card.findViewById<TextView>(R.id.tvAdvanceReason).text = reasonText

            container.addView(card)
        }
    }

    private fun confirmVoidAdvance(adv: AdvancePayment, staffName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Void Salary Advance 💳")
            .setMessage("Are you sure you want to void the advance of ${currency.format(adv.amount)} for $staffName?\n\nThis removes the advance from pending deduction in upcoming payroll.")
            .setPositiveButton("Void Advance 🗑️") { _, _ ->
                voidAdvance(adv.advanceId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun voidAdvance(advanceId: String) {
        lifecycleScope.launch {
            try {
                firebaseFinance.deleteAdvance(advanceId)
                Toast.makeText(this@AdminFinanceActivity, "Advance voided successfully! 🗑️", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AdminFinanceActivity, "Failed to void advance: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showIssueAdvanceDialog() {
        val dialogBinding = DialogAdminMoneyEntryBinding.inflate(layoutInflater)
        dialogBinding.tvTitle.text = "Issue Salary Advance 💳"

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allEmployees.map { it.name })
        dialogBinding.actvEmployee.setAdapter(adapter)

        val selectedCalendar = Calendar.getInstance()
        dialogBinding.btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedCalendar.set(y, m, d)
                dialogBinding.btnDate.text = "Advance Date: ${dateFormat.format(selectedCalendar.time)}"
            }, selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH), selectedCalendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Issue Advance 💸") { _, _ ->
                val empIdx = allEmployees.indexOfFirst { it.name == dialogBinding.actvEmployee.text.toString() }
                if (empIdx == -1) {
                    Toast.makeText(this, "Please select an employee", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val empId = allEmployees[empIdx].employeeId.toIntOrNull() ?: 0
                val amount = dialogBinding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
                if (amount <= 0.0) {
                    Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val type = dialogBinding.etDescription.text.toString().ifBlank { "Salary Advance" }
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedCalendar.time)

                submitNewAdvance(empId, amount, type, dateStr)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitNewAdvance(empId: Int, amount: Double, type: String, dateStr: String) {
        lifecycleScope.launch {
            try {
                firebaseFinance.createAdvance(empId, amount, type, dateStr)
                Toast.makeText(this@AdminFinanceActivity, "Salary Advance issued successfully! 💳 ✅", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@AdminFinanceActivity, "Error issuing advance: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
