package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalDailySummaryDao
import com.biometric.app.data.dao.LocalPayrollHistoryDao
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalPayrollHistory
import com.biometric.app.data.repository.FirebaseAdminFinanceRepository
import com.biometric.app.ui.selfservice.PayslipListActivity
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminPayrollActivity : MotionBaseActivity() {

    @Inject lateinit var firebaseSync: com.biometric.app.sync.FirebaseSyncManager
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var mainRepository: MainRepository
    @Inject lateinit var localDailySummaryDao: LocalDailySummaryDao
    @Inject lateinit var localPayrollHistoryDao: LocalPayrollHistoryDao
    @Inject lateinit var financeRepository: FirebaseAdminFinanceRepository
    private lateinit var session: MobileSessionStore

    private lateinit var spMonth: Spinner
    private lateinit var spYear: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnPreview: MaterialButton
    private lateinit var btnRecalculate: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var btnFinalize: MaterialButton
    private lateinit var btnExportCsv: MaterialButton

    private lateinit var llKpiContainer: View
    private lateinit var tvKpiEmployees: TextView
    private lateinit var tvKpiGross: TextView
    private lateinit var tvKpiDeductions: TextView
    private lateinit var tvKpiNetPayable: TextView

    private lateinit var llEmptyState: View
    private lateinit var llRows: LinearLayout

    private var previewList = mutableListOf<AdminPayrollRowDto>()
    private var historyList = emptyList<AdminPayrollHistoryRowDto>()
    private var isViewingHistory = false

    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_payroll)

        applyWindowInsets(
            findViewById(R.id.clAdminPayrollRoot),
            findViewById(R.id.appBar),
            findViewById(R.id.nestedScrollView)
        )
        session = MobileSessionStore(this)

        initViews()
        setupPeriodSpinners()
        setupClickListeners()
        observePayrollHistoryRealtime()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        spMonth = findViewById(R.id.spMonth)
        spYear = findViewById(R.id.spYear)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        btnPreview = findViewById(R.id.btnPreview)
        btnRecalculate = findViewById(R.id.btnRecalculate)
        btnHistory = findViewById(R.id.btnHistory)
        btnFinalize = findViewById(R.id.btnFinalize)
        btnExportCsv = findViewById(R.id.btnExportCsv)

        llKpiContainer = findViewById(R.id.llKpiContainer)
        tvKpiEmployees = findViewById(R.id.tvKpiEmployees)
        tvKpiGross = findViewById(R.id.tvKpiGross)
        tvKpiDeductions = findViewById(R.id.tvKpiDeductions)
        tvKpiNetPayable = findViewById(R.id.tvKpiNetPayable)

        llEmptyState = findViewById(R.id.llEmptyState)
        llRows = findViewById(R.id.llRows)
    }

    private fun setupPeriodSpinners() {
        val now = Calendar.getInstance()
        val months = (1..12).map { java.text.DateFormatSymbols().months[it - 1] }
        val currentYear = now.get(Calendar.YEAR)
        val years = listOf(currentYear - 1, currentYear, currentYear + 1)

        spMonth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, months)
        spMonth.setSelection(now.get(Calendar.MONTH))

        spYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)
        spYear.setSelection(1) // Current year
    }

    private fun setupClickListeners() {
        btnPreview.setOnClickListener {
            HapticUtil.vibrateClick(it)
            loadPreview()
        }

        btnRecalculate.setOnClickListener {
            HapticUtil.vibrateClick(it)
            recalculatePreview()
        }

        btnHistory.setOnClickListener {
            HapticUtil.vibrateClick(it)
            isViewingHistory = true
            btnFinalize.isEnabled = false
            btnRecalculate.isEnabled = false
            renderHistoryRows()
        }

        btnFinalize.setOnClickListener {
            HapticUtil.vibrateClick(it)
            confirmFinalize()
        }

        btnExportCsv.setOnClickListener {
            HapticUtil.vibrateClick(it)
            exportPayrollToCsv()
        }
    }

    private fun auth() = "Bearer ${session.token().orEmpty()}"
    private fun period(): Pair<Int, Int> {
        val y = spYear.selectedItem as? Int ?: Calendar.getInstance().get(Calendar.YEAR)
        val m = spMonth.selectedItemPosition + 1
        return Pair(y, m)
    }

    private fun setBusy(busy: Boolean, statusMessage: String? = null) {
        progressBar.isVisible = busy
        btnPreview.isEnabled = !busy
        btnHistory.isEnabled = !busy
        if (busy) {
            btnFinalize.isEnabled = false
            btnRecalculate.isEnabled = false
        }
        if (statusMessage != null) {
            tvStatus.text = statusMessage
        }
    }

    private fun loadPreview() = lifecycleScope.launch {
        setBusy(true, "Calculating payroll preview… ⚡")
        isViewingHistory = false
        previewList.clear()
        llRows.removeAllViews()

        try {
            val (y, m) = period()

            // 1. Fetch active employees from SharedViewModel or Room or Firebase
            var employees = sharedViewModel.allEmployees.value.filter { it.isActive }
            if (employees.isEmpty()) {
                employees = runCatching { mainRepository.allEmployeesFlow.first() }.getOrDefault(emptyList()).filter { it.isActive }
            }
            if (employees.isEmpty()) {
                val snapshot = runCatching { firebaseSync.getOwnerRef()?.child("employees")?.get()?.await() }.getOrNull()
                employees = snapshot?.children?.mapNotNull { it.getValue(Employee::class.java) }?.filter { it.isActive } ?: emptyList()
            }

            if (employees.isEmpty()) {
                throw Exception("No active staff found to calculate payroll.")
            }

            // 2. Fetch daily attendance summaries for the selected period
            val monthStr = if (m < 10) "0$m" else "$m"
            val periodPrefix = "$y-$monthStr"
            val allSummaries = runCatching { localDailySummaryDao.getAllFlow().first() }.getOrDefault(emptyList())
            val monthSummaries = allSummaries.filter { it.shiftDate.startsWith(periodPrefix) }

            // 3. Fetch unpaid advances and bonuses
            val unpaidAdvances = runCatching { financeRepository.advances(unpaidOnly = true) }.getOrDefault(emptyList())
            val allBonuses = runCatching { financeRepository.bonuses() }.getOrDefault(emptyList())
            val monthBonuses = allBonuses.filter { !it.paid && it.date.startsWith(periodPrefix) }

            // 4. Calculate for each employee (1:1 with Web RunPayroll.razor & PayrollProcessorService)
            val generatedRows = mutableListOf<AdminPayrollRowDto>()

            for (emp in employees) {
                val empIdInt = emp.employeeId.toIntOrNull() ?: 0
                val empSummaries = monthSummaries.filter { it.employeeId == empIdInt }

                val baseSalary = if (emp.salaryRate > 0) emp.salaryRate else if (emp.basicSalaryComponent > 0) emp.basicSalaryComponent else 15000.0
                val dailyRate = baseSalary / 26.0
                val standardHrs = if (emp.standardHours > 0) emp.standardHours else 8
                val hourlyRate = dailyRate / standardHrs

                var earnedHours = 0.0
                var overtimeMinutes = 0.0
                var penaltyMinutes = 0.0
                var totalShiftAllowance = 0.0
                var presentDays = 0.0
                var leaveDays = 0
                var absentDays = 0

                if (empSummaries.isNotEmpty()) {
                    for (ds in empSummaries) {
                        earnedHours += ds.earnedStandardHours
                        overtimeMinutes += (ds.totalOvertimeMs / 60000.0)
                        penaltyMinutes += (ds.totalPenaltyMs / 60000.0)
                        totalShiftAllowance += ds.shiftAllowanceEarned

                        when {
                            ds.status.equals("Present", ignoreCase = true) -> presentDays += 1.0
                            ds.status.equals("Half Day", ignoreCase = true) -> presentDays += 0.5
                            ds.status.contains("Leave", ignoreCase = true) -> leaveDays += 1
                            ds.status.contains("Absent", ignoreCase = true) || ds.status.contains("Loss of Pay", ignoreCase = true) -> absentDays += 1
                        }
                    }
                } else {
                    // Fallback for full active month when daily summaries not yet closed
                    presentDays = 26.0
                    earnedHours = 26.0 * standardHrs
                }

                // Earned Pay
                val earnedPay = if (emp.salaryCalculationMethod.contains("Pro-Rata", ignoreCase = true)) {
                    earnedHours * hourlyRate
                } else {
                    (presentDays + leaveDays) * dailyRate
                }

                // Overtime pay
                val otHours = overtimeMinutes / 60.0
                val overtimePay = when (emp.otRule.trim().lowercase()) {
                    "1.5x" -> otHours * hourlyRate * 1.5
                    "2.0x" -> otHours * hourlyRate * 2.0
                    "flat" -> otHours * (if (emp.otFlatRate > 0) emp.otFlatRate else hourlyRate)
                    "no overtime" -> 0.0
                    else -> otHours * hourlyRate
                }

                // Penalty deduction
                val penaltyHours = penaltyMinutes / 60.0
                val penaltyDeduction = penaltyHours * hourlyRate

                // Advance deduction
                val empAdvances = unpaidAdvances.filter { it.employeeId == empIdInt }
                val advanceDeduction = empAdvances.sumOf { it.amount }

                // Bonus
                val empBonuses = monthBonuses.filter { it.employeeId == empIdInt }
                val bonus = empBonuses.sumOf { it.amount }

                // Statutory (PF, ESI, PT, TDS)
                val basicSalary = if (emp.basicSalaryComponent > 0) emp.basicSalaryComponent else (baseSalary * 0.5)
                val isPfEnabled = emp.enablePf
                val pfDeduction = if (isPfEnabled) (basicSalary * 0.12).coerceAtMost(1800.0) else 0.0
                val employerPf = if (isPfEnabled) (basicSalary * 0.12).coerceAtMost(1800.0) else 0.0

                val gross = earnedPay + overtimePay + bonus + totalShiftAllowance
                val isEsiEnabled = emp.enableEsi && (gross <= 21000.0)
                val esiDeduction = if (isEsiEnabled) (gross * 0.0075) else 0.0
                val employerEsi = if (isEsiEnabled) (gross * 0.0325) else 0.0

                val ptDeduction = when {
                    gross > 15000.0 -> 200.0
                    gross > 10000.0 -> 150.0
                    else -> 0.0
                }

                val tdsDeduction = if (emp.tdsRatePercent > 0) (gross * (emp.tdsRatePercent / 100.0)) else 0.0

                val totalDeductions = pfDeduction + esiDeduction + ptDeduction + tdsDeduction + advanceDeduction + penaltyDeduction
                val netPayable = (gross - totalDeductions).coerceAtLeast(0.0)

                generatedRows.add(
                    AdminPayrollRowDto(
                        employeeID = empIdInt,
                        employeeName = emp.name,
                        baseSalary = baseSalary,
                        hourlyRate = hourlyRate,
                        earnedStandardHours = earnedHours,
                        earnedPay = earnedPay,
                        overtimeMinutes = overtimeMinutes,
                        overtimePay = overtimePay,
                        penaltyMinutes = penaltyMinutes,
                        penaltyDeduction = penaltyDeduction,
                        advanceDeduction = advanceDeduction,
                        bonus = bonus,
                        totalShiftAllowance = totalShiftAllowance,
                        tdsDeduction = tdsDeduction,
                        basicSalary = basicSalary,
                        pfDeduction = pfDeduction,
                        esiDeduction = esiDeduction,
                        ptDeduction = ptDeduction,
                        employerPfContribution = employerPf,
                        employerEsiContribution = employerEsi,
                        isPfEnabled = isPfEnabled,
                        isEsiEnabled = isEsiEnabled,
                        netPayable = netPayable,
                        leaveDays = leaveDays,
                        absentDays = absentDays
                    )
                )
            }

            previewList = generatedRows.sortedBy { it.employeeName }.toMutableList()
            renderPreviewRows()
            tvStatus.text = "Preview generated • ${previewList.size} staff members (Standalone ⚡)"
            btnFinalize.isEnabled = previewList.isNotEmpty()
            btnRecalculate.isEnabled = false
        } catch (e: Exception) {
            tvStatus.text = "Payroll preview failed: ${e.message}"
            Toast.makeText(this@AdminPayrollActivity, e.message ?: "Calculation error", Toast.LENGTH_LONG).show()
        } finally {
            setBusy(false)
        }
    }

    private fun recalculatePreview() {
        previewList = previewList.map { row ->
            val gross = row.earnedPay + row.overtimePay + row.bonus + row.totalShiftAllowance
            val ded = row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.advanceDeduction + row.penaltyDeduction
            val net = (gross - ded).coerceAtLeast(0.0)
            row.copy(netPayable = net)
        }.toMutableList()
        renderPreviewRows()
        btnRecalculate.isEnabled = false
        tvStatus.text = "Preview recalculated • Adjusted net pay updated"
        Toast.makeText(this, "Net pay recalculated! 🔄", Toast.LENGTH_SHORT).show()
    }

    private fun renderPreviewRows() {
        llRows.removeAllViews()
        val hasRows = previewList.isNotEmpty()
        llEmptyState.isVisible = !hasRows
        llKpiContainer.isVisible = hasRows

        if (!hasRows) return

        var totalGross = 0.0
        var totalDeductions = 0.0
        var totalNet = 0.0

        val inflater = LayoutInflater.from(this)

        previewList.forEachIndexed { index, row ->
            val gross = row.earnedPay + row.overtimePay + row.bonus + row.totalShiftAllowance
            val deductions = row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.advanceDeduction + row.penaltyDeduction
            val net = (gross - deductions).coerceAtLeast(0.0)

            totalGross += gross
            totalDeductions += deductions
            totalNet += net

            val card = inflater.inflate(R.layout.item_admin_payroll_row, llRows, false)

            card.findViewById<TextView>(R.id.tvEmployeeName).text = row.employeeName ?: "Staff #${row.employeeID}"
            card.findViewById<TextView>(R.id.tvEmployeeId).text = "EMP #${row.employeeID} • Base: ${currency.format(row.baseSalary ?: (row.hourlyRate * 160))}"
            card.findViewById<TextView>(R.id.tvNetPay).text = currency.format(net)
            card.findViewById<TextView>(R.id.tvRowStatusBadge).text = "PREVIEW"

            card.findViewById<TextView>(R.id.tvEarnedPay).text = currency.format(row.earnedPay)
            card.findViewById<TextView>(R.id.tvHoursWorked).text = "⏱️ ${"%.1f".format(Locale.US, row.earnedStandardHours)}h"

            val otHours = row.overtimeMinutes / 60.0
            val tvOvertime = card.findViewById<TextView>(R.id.tvOvertimePay)
            if (row.overtimePay > 0) {
                tvOvertime.text = "+${currency.format(row.overtimePay)} (${"%.1f".format(Locale.US, otHours)}h)"
                tvOvertime.isVisible = true
            } else {
                tvOvertime.text = "+₹0 OT"
            }

            val penHours = row.penaltyMinutes / 60.0
            val tvPen = card.findViewById<TextView>(R.id.tvPenaltyDed)
            if (row.penaltyDeduction > 0) {
                tvPen.text = "-${currency.format(row.penaltyDeduction)} (${"%.1f".format(Locale.US, penHours)}h)"
                tvPen.isVisible = true
            } else {
                tvPen.text = "-₹0 Ded"
            }

            card.findViewById<TextView>(R.id.tvLeavesAbsent).text = "M: ${row.leaveDays} • A: ${row.absentDays}"
            card.findViewById<TextView>(R.id.tvGrossPay).text = "Gross: ${currency.format(gross)}"

            // Breakdown Chips
            val chipPf = card.findViewById<TextView>(R.id.chipPf)
            chipPf.isVisible = row.pfDeduction > 0
            chipPf.text = "🛡️ PF: ${currency.format(row.pfDeduction)}"

            val chipEsi = card.findViewById<TextView>(R.id.chipEsi)
            chipEsi.isVisible = row.esiDeduction > 0
            chipEsi.text = "🏥 ESI: ${currency.format(row.esiDeduction)}"

            val chipPt = card.findViewById<TextView>(R.id.chipPt)
            chipPt.isVisible = row.ptDeduction > 0
            chipPt.text = "🏛️ PT: ${currency.format(row.ptDeduction)}"

            val chipTds = card.findViewById<TextView>(R.id.chipTds)
            chipTds.isVisible = row.tdsDeduction > 0
            chipTds.text = "🏷️ TDS: ${currency.format(row.tdsDeduction)}"

            val chipAdv = card.findViewById<TextView>(R.id.chipAdvanceDed)
            chipAdv.isVisible = row.advanceDeduction > 0
            chipAdv.text = "💳 Adv: -${currency.format(row.advanceDeduction)}"

            val chipShift = card.findViewById<TextView>(R.id.chipShiftAllowance)
            chipShift.isVisible = row.totalShiftAllowance > 0
            chipShift.text = "🌙 Shift: +${currency.format(row.totalShiftAllowance)}"

            val chipBonus = card.findViewById<TextView>(R.id.chipBonus)
            chipBonus.text = "🎁 Bonus: +${currency.format(row.bonus)} ✏️"
            chipBonus.setOnClickListener {
                HapticUtil.vibrateClick(it)
                showBonusAdjustmentDialog(index, row)
            }

            llRows.addView(card)
        }

        // Update KPI Header Cards
        tvKpiEmployees.text = previewList.size.toString()
        tvKpiGross.text = currency.format(totalGross)
        tvKpiDeductions.text = "-${currency.format(totalDeductions)}"
        tvKpiNetPayable.text = currency.format(totalNet)
    }

    private fun showBonusAdjustmentDialog(index: Int, row: AdminPayrollRowDto) {
        val input = EditText(this).apply {
            hint = "Enter Bonus Amount (₹)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (row.bonus > 0) row.bonus.toString() else "")
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🎁 Adjust Bonus for ${row.employeeName}")
            .setMessage("Set festive, performance, or custom bonus for this payroll cycle:")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val newBonus = input.text.toString().toDoubleOrNull() ?: 0.0
                val updatedRow = row.copy(bonus = newBonus)
                previewList[index] = updatedRow
                btnRecalculate.isEnabled = true
                renderPreviewRows()
                Toast.makeText(this, "Bonus updated. Tap 'Recalculate' if needed 🔄", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observePayrollHistoryRealtime() {
        lifecycleScope.launch {
            val payrollFlow = firebaseSync.getDataFlow<RealtimePayrollRow>("payroll_history")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")

            combine(payrollFlow, employeeFlow) { payroll, employees ->
                val (y, m) = period()
                val names = employees.associateBy { it.employeeId.toIntOrNull() ?: -1 }.mapValues { it.value.name }

                payroll.filter { it.payYear == y && it.payMonth == m } to names
            }.collectLatest { (rows, names) ->
                historyList = rows.sortedBy { it.employeeId }.map { row ->
                    AdminPayrollHistoryRowDto(
                        payrollID = row.payrollId,
                        employeeID = row.employeeId,
                        employeeName = names[row.employeeId] ?: "Staff #${row.employeeId}",
                        payMonth = row.payMonth,
                        payYear = row.payYear,
                        baseSalary = row.baseSalary,
                        totalHoursWorked = row.totalHoursWorked,
                        totalOvertimeMinutes = row.totalOvertimeMs / 60000.0,
                        totalPenaltyMinutes = row.totalPenaltyMs / 60000.0,
                        deductionsHours = row.deductionsHours,
                        deductionsAdvance = row.deductionsAdvance,
                        bonus = row.bonus,
                        tdsDeduction = row.tdsDeduction,
                        totalShiftAllowance = row.totalShiftAllowance,
                        basicComponent = row.basicComponent,
                        pfDeduction = row.pfDeduction,
                        esiDeduction = row.esiDeduction,
                        ptDeduction = row.ptDeduction,
                        absentDays = row.absentDays,
                        manualLeaveDays = row.manualLeaveDays,
                        netSalary = row.netSalary
                    )
                }

                if (isViewingHistory) {
                    renderHistoryRows()
                }
            }
        }
    }

    private fun renderHistoryRows() {
        llRows.removeAllViews()
        val hasRows = historyList.isNotEmpty()
        llEmptyState.isVisible = !hasRows
        llKpiContainer.isVisible = hasRows

        if (!hasRows) {
            tvStatus.text = "No saved payroll found for selected period."
            return
        }

        var totalGross = 0.0
        var totalDeductions = 0.0
        var totalNet = 0.0

        val inflater = LayoutInflater.from(this)

        historyList.forEach { row ->
            val net = row.netSalary ?: 0.0
            val gross = (row.baseSalary ?: 0.0) + (row.totalOvertimeMinutes / 60.0 * 200.0) + row.bonus + row.totalShiftAllowance
            val deductions = row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.deductionsAdvance

            totalGross += gross
            totalDeductions += deductions
            totalNet += net

            val card = inflater.inflate(R.layout.item_admin_payroll_row, llRows, false)

            card.findViewById<TextView>(R.id.tvEmployeeName).text = row.employeeName ?: "Staff #${row.employeeID}"
            card.findViewById<TextView>(R.id.tvEmployeeId).text = "EMP #${row.employeeID} • Base: ${currency.format(row.baseSalary ?: 0.0)}"
            card.findViewById<TextView>(R.id.tvNetPay).text = currency.format(net)

            val badge = card.findViewById<TextView>(R.id.tvRowStatusBadge)
            badge.text = "FINALIZED"
            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_700))
            badge.setTextColor(getColor(R.color.white))

            card.findViewById<TextView>(R.id.tvEarnedPay).text = currency.format(row.baseSalary ?: 0.0)
            card.findViewById<TextView>(R.id.tvHoursWorked).text = "⏱️ ${"%.1f".format(Locale.US, row.totalHoursWorked)}h"

            val otHours = row.totalOvertimeMinutes / 60.0
            val tvOvertime = card.findViewById<TextView>(R.id.tvOvertimePay)
            tvOvertime.text = if (otHours > 0) "+${"%.1f".format(Locale.US, otHours)}h OT" else "+0h OT"

            val penHours = row.totalPenaltyMinutes / 60.0
            val tvPen = card.findViewById<TextView>(R.id.tvPenaltyDed)
            tvPen.text = if (penHours > 0) "-${"%.1f".format(Locale.US, penHours)}h Ded" else "-0h Ded"

            card.findViewById<TextView>(R.id.tvLeavesAbsent).text = "M: ${row.manualLeaveDays} • A: ${row.absentDays}"
            card.findViewById<TextView>(R.id.tvGrossPay).text = "Gross: ${currency.format(gross)}"

            // Breakdown Chips
            val chipPf = card.findViewById<TextView>(R.id.chipPf)
            chipPf.isVisible = row.pfDeduction > 0
            chipPf.text = "🛡️ PF: ${currency.format(row.pfDeduction)}"

            val chipEsi = card.findViewById<TextView>(R.id.chipEsi)
            chipEsi.isVisible = row.esiDeduction > 0
            chipEsi.text = "🏥 ESI: ${currency.format(row.esiDeduction)}"

            val chipPt = card.findViewById<TextView>(R.id.chipPt)
            chipPt.isVisible = row.ptDeduction > 0
            chipPt.text = "🏛️ PT: ${currency.format(row.ptDeduction)}"

            val chipTds = card.findViewById<TextView>(R.id.chipTds)
            chipTds.isVisible = row.tdsDeduction > 0
            chipTds.text = "🏷️ TDS: ${currency.format(row.tdsDeduction)}"

            val chipAdv = card.findViewById<TextView>(R.id.chipAdvanceDed)
            chipAdv.isVisible = row.deductionsAdvance > 0
            chipAdv.text = "💳 Adv: -${currency.format(row.deductionsAdvance)}"

            val chipShift = card.findViewById<TextView>(R.id.chipShiftAllowance)
            chipShift.isVisible = row.totalShiftAllowance > 0
            chipShift.text = "🌙 Shift: +${currency.format(row.totalShiftAllowance)}"

            val chipBonus = card.findViewById<TextView>(R.id.chipBonus)
            chipBonus.isVisible = row.bonus > 0
            chipBonus.text = "🎁 Bonus: +${currency.format(row.bonus)}"
            chipBonus.isClickable = false

            // Action row: View Payslip
            val llActions = card.findViewById<LinearLayout>(R.id.llRowActions)
            llActions.isVisible = true
            card.findViewById<MaterialButton>(R.id.btnRowViewPayslip).setOnClickListener {
                HapticUtil.vibrateClick(it)
                startActivity(Intent(this, PayslipListActivity::class.java))
            }

            llRows.addView(card)
        }

        tvKpiEmployees.text = historyList.size.toString()
        tvKpiGross.text = currency.format(totalGross)
        tvKpiDeductions.text = "-${currency.format(totalDeductions)}"
        tvKpiNetPayable.text = currency.format(totalNet)
        tvStatus.text = "History loaded • ${historyList.size} finalized staff records"
    }

    private fun confirmFinalize() {
        if (previewList.isEmpty()) {
            Toast.makeText(this, "Generate a preview first! ⚡", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("💰 Finalize Payroll")
            .setMessage("Save ${previewList.size} payroll entries to the database? This finalizes the payroll run and records all deductions.")
            .setPositiveButton("Finalize & Disburse ✅") { _, _ ->
                finalizePayroll()
            }
            .setNegativeButton("Cancel ❌", null)
            .show()
    }

    private fun finalizePayroll() = lifecycleScope.launch {
        setBusy(true, "Finalizing & saving payroll to database… 💾")
        try {
            val (y, m) = period()
            val ownerRef = firebaseSync.getOwnerRef() ?: throw Exception("Firebase session not initialized")

            val timestamp = System.currentTimeMillis()
            previewList.forEachIndexed { index, row ->
                val pid = ((timestamp % 100000000).toInt()) + index
                val gross = row.earnedPay + row.overtimePay + row.bonus + row.totalShiftAllowance
                val deductions = row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.advanceDeduction + row.penaltyDeduction
                val net = (gross - deductions).coerceAtLeast(0.0)

                val realtimeRow = RealtimePayrollRow(
                    payrollId = pid,
                    employeeId = row.employeeID,
                    payMonth = m,
                    payYear = y,
                    baseSalary = row.baseSalary ?: 0.0,
                    totalHoursWorked = row.earnedStandardHours,
                    overtimePay = row.overtimePay,
                    deductionsHours = row.penaltyDeduction,
                    deductionsAdvance = row.advanceDeduction,
                    bonus = row.bonus,
                    netSalary = net,
                    manualLeaveDays = row.leaveDays,
                    absentDays = row.absentDays,
                    totalPenaltyMs = (row.penaltyMinutes * 60000).toLong(),
                    totalOvertimeMs = (row.overtimeMinutes * 60000).toLong(),
                    hourlyRate = row.hourlyRate,
                    basicComponent = row.basicSalary,
                    pfDeduction = row.pfDeduction,
                    esiDeduction = row.esiDeduction,
                    employerPfContribution = row.employerPfContribution,
                    employerEsiContribution = row.employerEsiContribution,
                    ptDeduction = row.ptDeduction,
                    tdsDeduction = row.tdsDeduction,
                    totalShiftAllowance = row.totalShiftAllowance
                )

                // Push to Firebase Realtime Database
                ownerRef.child("payroll_history").child(pid.toString()).setValue(realtimeRow).await()

                // Upsert to local Room SQLite
                val localRow = LocalPayrollHistory(
                    payrollId = pid,
                    employeeId = row.employeeID,
                    payMonth = m,
                    payYear = y,
                    baseSalary = row.baseSalary ?: 0.0,
                    totalHoursWorked = row.earnedStandardHours,
                    overtimePay = row.overtimePay,
                    deductionsHours = row.penaltyDeduction,
                    deductionsAdvance = row.advanceDeduction,
                    bonus = row.bonus,
                    netSalary = net,
                    manualLeaveDays = row.leaveDays,
                    absentDays = row.absentDays,
                    totalPenaltyMs = (row.penaltyMinutes * 60000).toLong(),
                    totalOvertimeMs = (row.overtimeMinutes * 60000).toLong(),
                    hourlyRate = row.hourlyRate,
                    basicComponent = row.basicSalary,
                    pfDeduction = row.pfDeduction,
                    esiDeduction = row.esiDeduction,
                    employerPfContribution = row.employerPfContribution,
                    employerEsiContribution = row.employerEsiContribution,
                    ptDeduction = row.ptDeduction,
                    tdsDeduction = row.tdsDeduction,
                    totalShiftAllowance = row.totalShiftAllowance,
                    syncState = 1,
                    lastModified = System.currentTimeMillis()
                )
                localPayrollHistoryDao.upsert(localRow)

                // If advance was deducted, mark advance as recovered in Firebase
                if (row.advanceDeduction > 0) {
                    runCatching {
                        val advSnapshot = ownerRef.child("advance_payments").get().await()
                        advSnapshot.children.forEach { child ->
                            val empId = child.child("employeeId").value?.toString()?.toIntOrNull()
                            val isRec = child.child("isRecovered").value as? Boolean ?: false
                            if (empId == row.employeeID && !isRec) {
                                child.ref.child("isRecovered").setValue(true)
                                child.ref.child("recoveryPaymentId").setValue(pid.toString())
                            }
                        }
                    }
                }
            }

            // Record Audit Log
            runCatching {
                firebaseSync.pushAuditLog(
                    AuditLog(
                        action = "FINALIZE",
                        module = "Payroll",
                        newValue = "Finalized ${previewList.size} payslips for period $m/$y (Standalone Android)",
                        userDisplayName = session.employeeName().ifBlank { "Admin" },
                        userId = session.employeeId().toString()
                    )
                )
            }

            Toast.makeText(this@AdminPayrollActivity, "Payroll Finalized Successfully! 💎 ✅", Toast.LENGTH_LONG).show()
            tvStatus.text = "Payroll finalized for period $m/$y! ✅"
            btnFinalize.isEnabled = false
            btnRecalculate.isEnabled = false
            isViewingHistory = true
        } catch (e: Exception) {
            tvStatus.text = "Finalization failed: ${e.message}"
            Toast.makeText(this@AdminPayrollActivity, e.message ?: "Request failed", Toast.LENGTH_LONG).show()
        } finally {
            setBusy(false)
        }
    }

    private fun exportPayrollToCsv() {
        val (y, m) = period()
        val monthName = java.text.DateFormatSymbols().months[m - 1]

        val csvBuilder = StringBuilder()
        csvBuilder.append("Employee ID,Employee Name,Base Salary,Earned Hours,Earned Pay,Overtime Pay,Penalty Ded,PF Ded,ESI Ded,PT Ded,TDS Ded,Adv Ded,Bonus,Shift Allowance,Net Payable\n")

        if (!isViewingHistory && previewList.isNotEmpty()) {
            previewList.forEach { row ->
                val gross = row.earnedPay + row.overtimePay + row.bonus + row.totalShiftAllowance
                val ded = row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.advanceDeduction + row.penaltyDeduction
                val net = (gross - ded).coerceAtLeast(0.0)

                csvBuilder.append("${row.employeeID},\"${row.employeeName}\",${row.baseSalary ?: 0.0},${row.earnedStandardHours},${row.earnedPay},${row.overtimePay},${row.penaltyDeduction},${row.pfDeduction},${row.esiDeduction},${row.ptDeduction},${row.tdsDeduction},${row.advanceDeduction},${row.bonus},${row.totalShiftAllowance},$net\n")
            }
        } else if (historyList.isNotEmpty()) {
            historyList.forEach { row ->
                csvBuilder.append("${row.employeeID},\"${row.employeeName}\",${row.baseSalary ?: 0.0},${row.totalHoursWorked},${row.baseSalary ?: 0.0},${row.totalOvertimeMinutes / 60.0 * 200},${row.totalPenaltyMinutes},${row.pfDeduction},${row.esiDeduction},${row.ptDeduction},${row.tdsDeduction},${row.deductionsAdvance},${row.bonus},${row.totalShiftAllowance},${row.netSalary ?: 0.0}\n")
            }
        } else {
            Toast.makeText(this, "No data available to export. Generate Preview or load History first.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "Payroll_${monthName}_$y.csv"
            val file = File(cacheDir, fileName)
            val writer = FileWriter(file)
            writer.write(csvBuilder.toString())
            writer.flush()
            writer.close()

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Payroll Report - $monthName $y")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Payroll CSV 📊"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private data class RealtimePayrollRow(
        var payrollId: Int = 0,
        var employeeId: Int = 0,
        var payMonth: Int = 0,
        var payYear: Int = 0,
        var baseSalary: Double = 0.0,
        var totalHoursWorked: Double = 0.0,
        var overtimePay: Double = 0.0,
        var deductionsHours: Double = 0.0,
        var deductionsAdvance: Double = 0.0,
        var bonus: Double = 0.0,
        var netSalary: Double = 0.0,
        var manualLeaveDays: Int = 0,
        var absentDays: Int = 0,
        var totalPenaltyMs: Long = 0L,
        var totalOvertimeMs: Long = 0L,
        var hourlyRate: Double = 0.0,
        var basicComponent: Double = 0.0,
        var pfDeduction: Double = 0.0,
        var esiDeduction: Double = 0.0,
        var employerPfContribution: Double = 0.0,
        var employerEsiContribution: Double = 0.0,
        var ptDeduction: Double = 0.0,
        var tdsDeduction: Double = 0.0,
        var totalShiftAllowance: Double = 0.0
    )
}
