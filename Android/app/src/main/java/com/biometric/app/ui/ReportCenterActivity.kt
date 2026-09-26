package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.CompanyCumulativeSummary
import com.biometric.app.data.entity.ConsolidatedAttendanceRow
import com.biometric.app.data.entity.FinancialRegisterRow
import com.biometric.app.data.entity.LocalDailySummary
import com.biometric.app.data.entity.PayrollVarianceRow
import com.biometric.app.databinding.ActivityReportCenterBinding
import com.biometric.app.databinding.ItemReportResultCardBinding
import com.biometric.app.ui.viewmodel.ReportViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ReportCenterActivity : MotionBaseActivity() {

    private var _binding: ActivityReportCenterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel

    private var selectedReportType = "ATTENDANCE_MONTHLY_SUMMARY"
    private var selectedEmployeeId: Int? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDf = SimpleDateFormat("dd-MMM-yyyy", Locale.US)
    private var startDate = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var endDate = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityReportCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clReportCenterRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.etStartDate.setText(sdf.format(startDate.time))
        binding.etEndDate.setText(sdf.format(endDate.time))

        binding.etStartDate.setOnClickListener {
            showDatePicker {
                startDate = it
                binding.etStartDate.setText(sdf.format(it.time))
                triggerGenerate()
            }
        }
        binding.etEndDate.setOnClickListener {
            showDatePicker {
                endDate = it
                binding.etEndDate.setText(sdf.format(it.time))
                triggerGenerate()
            }
        }

        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { employees ->
                val names = listOf("All Employees") + employees.map { "${it.employeeId} • ${it.name}" }
                binding.spEmployee.setAdapter(ArrayAdapter(this@ReportCenterActivity, android.R.layout.simple_dropdown_item_1line, names))
                binding.spEmployee.setOnItemClickListener { _, _, position, _ ->
                    selectedEmployeeId = if (position == 0) null else employees.getOrNull(position - 1)?.employeeId?.toIntOrNull()
                    triggerGenerate()
                }
            }
        }

        setupReportTypeTiles()

        binding.btnGenerate.setOnClickListener {
            HapticUtil.vibrateClick(it)
            triggerGenerate()
        }

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.btnExportCsv.setOnClickListener {
            HapticUtil.vibrateClick(it)
            exportReportCsv()
        }

        triggerGenerate()
    }

    private fun triggerGenerate() {
        viewModel.generateReport(
            selectedReportType,
            sdf.format(startDate.time),
            sdf.format(endDate.time),
            endDate.get(Calendar.YEAR),
            endDate.get(Calendar.MONTH) + 1,
            selectedEmployeeId
        )
    }

    private fun setupReportTypeTiles() {
        val types = listOf(
            Triple("ATTENDANCE_MONTHLY_SUMMARY", "Company Attendance", "📊"),
            Triple("PAYROLL_VARIANCE", "Payroll Variance", "📈"),
            Triple("FINANCIAL_REGISTER", "Financial Register", "📋")
        )

        binding.llReportTypes.removeAllViews()
        types.forEach { (type, name, emoji) ->
            val view = layoutInflater.inflate(R.layout.item_report_type, binding.llReportTypes, false)
            view.findViewById<TextView>(R.id.tvEmoji).text = emoji
            view.findViewById<TextView>(R.id.tvName).text = name

            view.setOnClickListener {
                HapticUtil.vibrateClick(it)
                selectedReportType = type
                binding.tilEmployee.visibility = if (type == "ATTENDANCE_MONTHLY_SUMMARY") View.VISIBLE else View.GONE
                updateTileSelection()
                triggerGenerate()
            }
            view.tag = type
            binding.llReportTypes.addView(view)
        }
        updateTileSelection()
    }

    private fun updateTileSelection() {
        for (i in 0 until binding.llReportTypes.childCount) {
            val child = binding.llReportTypes.getChildAt(i)
            val isSelected = child.tag == selectedReportType
            child.alpha = if (isSelected) 1f else 0.55f
            child.scaleX = if (isSelected) 1.04f else 1.0f
            child.scaleY = if (isSelected) 1.04f else 1.0f
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.progressBar.isVisible = loading
                        binding.btnGenerate.isEnabled = !loading
                    }
                }
                launch {
                    viewModel.cumulativeSummary.collectLatest { summary ->
                        bindCumulativeSummary(summary)
                    }
                }
                launch {
                    viewModel.reportData.collectLatest { data ->
                        if (data == null) {
                            binding.tvEmptyState.isVisible = true
                            binding.tvEmptyStateText.text = "Select date range and click Generate Report"
                            binding.rvResults.adapter = null
                            binding.btnExportCsv.isVisible = false
                        } else if (data.isEmpty()) {
                            binding.tvEmptyState.isVisible = true
                            binding.tvEmptyStateText.text = "No records found for the selected period"
                            binding.rvResults.adapter = null
                            binding.btnExportCsv.isVisible = false
                        } else {
                            binding.tvEmptyState.isVisible = false
                            binding.rvResults.adapter = ReportAdapter(data)
                            binding.btnExportCsv.isVisible = true
                        }
                    }
                }
            }
        }
    }

    private fun bindCumulativeSummary(summary: CompanyCumulativeSummary?) {
        if (summary != null && selectedReportType == "ATTENDANCE_MONTHLY_SUMMARY" && summary.totalEmployeesProcessed > 0) {
            binding.cardCompanySummary.isVisible = true
            binding.tvSummaryDateRange.text = "📅 ${displayDf.format(startDate.time)} — ${displayDf.format(endDate.time)}"
            binding.tvProcessedCount.text = summary.totalEmployeesProcessed.toString()
            binding.tvScheduledHours.text = formatHoursMinutes(summary.totalScheduledDurationMs)
            binding.tvWorkedHours.text = formatHoursMinutes(summary.totalWorkedHours)
            binding.tvOvertimeHours.text = formatHoursMinutes(summary.totalOvertimeMs)
            binding.tvPenaltyHours.text = formatHoursMinutes(summary.totalOverallPenaltyMs)
            binding.tvLatenessHours.text = formatHoursMinutes(summary.totalLatenessMs)
        } else {
            binding.cardCompanySummary.isVisible = false
        }
    }

    private fun formatHoursMinutes(durationMs: Long): String {
        val totalMinutes = (durationMs + 30000) / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    private fun formatHoursMinutes(hoursDouble: Double): String {
        val totalMinutes = Math.round(hoursDouble * 60)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    private fun exportReportCsv() {
        val items = viewModel.reportData.value ?: return
        if (items.isEmpty()) return

        val summary = viewModel.cumulativeSummary.value
        val startFormatted = sdf.format(startDate.time)
        val endFormatted = sdf.format(endDate.time)

        val csv = buildString {
            when (items.first()) {
                is ConsolidatedAttendanceRow -> {
                    // 1:1 with Web CsvExportService.GenerateCompanyReportCsv + GenerateConsolidatedAttendanceCsv
                    if (summary != null) {
                        appendLine("Report Period,$startFormatted to $endFormatted")
                        appendLine("Total Employees Processed,${summary.totalEmployeesProcessed}")
                        appendLine()
                        appendLine("Metric,Total (HH:MM),Total (Decimal Hours)")
                        appendLine("Total Scheduled,${formatHoursMinutes(summary.totalScheduledDurationMs)},${String.format(Locale.US, "%.2f", summary.totalScheduledDurationMs / 3600000.0)}")
                        appendLine("Total Worked,${formatHoursMinutes(summary.totalWorkedHours)},${String.format(Locale.US, "%.2f", summary.totalWorkedHours)}")
                        appendLine("Total Overtime,${formatHoursMinutes(summary.totalOvertimeMs)},${String.format(Locale.US, "%.2f", summary.totalOvertimeMs / 3600000.0)}")
                        appendLine("Total Penalty,${formatHoursMinutes(summary.totalOverallPenaltyMs)},${String.format(Locale.US, "%.2f", summary.totalOverallPenaltyMs / 3600000.0)}")
                        appendLine("Total Lateness,${formatHoursMinutes(summary.totalLatenessMs)},${String.format(Locale.US, "%.2f", summary.totalLatenessMs / 3600000.0)}")
                        appendLine("Total Break Penalty,${formatHoursMinutes(summary.totalBreakPenaltyMs)},${String.format(Locale.US, "%.2f", summary.totalBreakPenaltyMs / 3600000.0)}")
                        appendLine()
                    }
                    appendLine("EmployeeID,EmployeeName,WorkedHours,Overtime_HHMM,Penalty_HHMM,Lateness_HHMM,AbsentDays")
                    items.filterIsInstance<ConsolidatedAttendanceRow>().forEach {
                        appendLine(
                            listOf(
                                it.employeeId,
                                csvEscape(it.employeeName),
                                "%.2f".format(Locale.US, it.totalWorkedHours),
                                formatHoursMinutes(it.totalOvertimeMs),
                                formatHoursMinutes(it.totalPenaltyMs),
                                formatHoursMinutes(it.totalLatenessMs),
                                it.totalAbsentDays
                            ).joinToString(",")
                        )
                    }
                }
                is PayrollVarianceRow -> {
                    appendLine("EmployeeName,CurrentNet,PreviousNet,Difference")
                    items.filterIsInstance<PayrollVarianceRow>().forEach {
                        appendLine(
                            listOf(
                                csvEscape(it.employeeName),
                                "%.2f".format(Locale.US, it.currentNet),
                                "%.2f".format(Locale.US, it.previousNet),
                                "%.2f".format(Locale.US, it.difference)
                            ).joinToString(",")
                        )
                    }
                }
                is FinancialRegisterRow -> {
                    appendLine("EmployeeID,EmployeeName,BiometricID,Email,MonthlySalary,HourlyRate,EarnedHours,OvertimeHours,OvertimePay,ShiftAllowance,Bonus,GrossPayable,AbsentDays,LeaveDays,TotalDeductions,NetPayable,PayrollStatus")
                    items.filterIsInstance<FinancialRegisterRow>().forEach {
                        appendLine(
                            listOf(
                                it.employeeId,
                                csvEscape(it.employeeName),
                                csvEscape(it.biometricId),
                                csvEscape(it.email ?: ""),
                                "%.2f".format(Locale.US, it.monthlySalary),
                                "%.2f".format(Locale.US, it.hourlyRate),
                                "%.2f".format(Locale.US, it.earnedHours),
                                "%.2f".format(Locale.US, it.totalOvertimeMs / 3600000.0),
                                "%.2f".format(Locale.US, it.totalOvertimePay),
                                "%.2f".format(Locale.US, it.shiftAllowance),
                                "%.2f".format(Locale.US, it.bonusPaid),
                                "%.2f".format(Locale.US, it.grossPayable),
                                it.absentDays,
                                it.leaveDays,
                                "%.2f".format(Locale.US, it.totalDeductions),
                                "%.2f".format(Locale.US, it.netPayable),
                                csvEscape(it.payrollStatus)
                            ).joinToString(",")
                        )
                    }
                }
                is LocalDailySummary -> {
                    appendLine("Date,Status,WorkedHours,OvertimeHours,PenaltyMinutes")
                    items.filterIsInstance<LocalDailySummary>().forEach {
                        appendLine(
                            listOf(
                                it.shiftDate,
                                csvEscape(it.status),
                                "%.2f".format(Locale.US, it.earnedStandardHours),
                                "%.2f".format(Locale.US, it.totalOvertimeMs / 3600000.0),
                                it.totalPenaltyMs / 60000
                            ).joinToString(",")
                        )
                    }
                }
            }
        }

        try {
            val file = File(cacheDir, "CompanyReport_${startFormatted}_to_${endFormatted}.csv")
            file.writeText(csv, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export Company Report"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\""
        else value
    }

    private fun showDatePicker(onDateSelected: (Calendar) -> Unit) {
        val current = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance()
            cal.set(y, m, d)
            onDateSelected(cal)
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
    }

    inner class ReportAdapter(private val items: List<Any>) : RecyclerView.Adapter<ReportAdapter.Holder>() {

        inner class Holder(val cardBinding: ItemReportResultCardBinding) : RecyclerView.ViewHolder(cardBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemReportResultCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val b = holder.cardBinding

            when (item) {
                is ConsolidatedAttendanceRow -> {
                    b.tvCardIcon.text = "👤"
                    b.tvCardTitle.text = item.employeeName
                    b.tvCardSubtitle.text = "Emp ID: ${item.employeeId}"

                    if (item.totalAbsentDays == 0) {
                        b.tvCardStatusBadge.text = "🟢 Present"
                        b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                        b.tvCardStatusBadge.setTextColor(getColor(R.color.green_900))
                    } else {
                        b.tvCardStatusBadge.text = "🔴 ${item.totalAbsentDays} Absent"
                        b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                        b.tvCardStatusBadge.setTextColor(getColor(R.color.red_900))
                    }

                    b.tvMetric1Label.text = "Worked"
                    b.tvMetric1Value.text = "${String.format(Locale.US, "%.1f", item.totalWorkedHours)}h"

                    b.tvMetric2Label.text = "Overtime"
                    b.tvMetric2Value.text = formatHoursMinutes(item.totalOvertimeMs)

                    b.tvMetric3Label.text = "Penalty"
                    b.tvMetric3Value.text = formatHoursMinutes(item.totalPenaltyMs)

                    b.tvMetric4Label.text = "Lateness"
                    b.tvMetric4Value.text = formatHoursMinutes(item.totalLatenessMs)
                }
                is PayrollVarianceRow -> {
                    b.tvCardIcon.text = if (item.difference >= 0) "📈" else "📉"
                    b.tvCardTitle.text = item.employeeName
                    b.tvCardSubtitle.text = "Payroll Variance"

                    if (item.difference >= 0) {
                        b.tvCardStatusBadge.text = "+₹${String.format(Locale.US, "%.0f", item.difference)}"
                        b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                        b.tvCardStatusBadge.setTextColor(getColor(R.color.green_900))
                    } else {
                        b.tvCardStatusBadge.text = "-₹${String.format(Locale.US, "%.0f", Math.abs(item.difference))}"
                        b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                        b.tvCardStatusBadge.setTextColor(getColor(R.color.red_900))
                    }

                    b.tvMetric1Label.text = "Current"
                    b.tvMetric1Value.text = "₹${String.format(Locale.US, "%.0f", item.currentNet)}"

                    b.tvMetric2Label.text = "Previous"
                    b.tvMetric2Value.text = "₹${String.format(Locale.US, "%.0f", item.previousNet)}"

                    b.tvMetric3Label.text = "Difference"
                    b.tvMetric3Value.text = "₹${String.format(Locale.US, "%.0f", item.difference)}"

                    b.tvMetric4Label.text = "Status"
                    b.tvMetric4Value.text = if (item.difference >= 0) "Increased" else "Decreased"
                }
                is FinancialRegisterRow -> {
                    b.tvCardIcon.text = "💰"
                    b.tvCardTitle.text = item.employeeName
                    b.tvCardSubtitle.text = "ID: ${item.employeeId} • ${item.biometricId}"

                    b.tvCardStatusBadge.text = item.payrollStatus
                    b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue_light))
                    b.tvCardStatusBadge.setTextColor(getColor(R.color.blue_900))

                    b.tvMetric1Label.text = "Net Payable"
                    b.tvMetric1Value.text = "₹${String.format(Locale.US, "%.0f", item.netPayable)}"

                    b.tvMetric2Label.text = "Gross"
                    b.tvMetric2Value.text = "₹${String.format(Locale.US, "%.0f", item.grossPayable)}"

                    b.tvMetric3Label.text = "Deductions"
                    b.tvMetric3Value.text = "₹${String.format(Locale.US, "%.0f", item.totalDeductions)}"

                    b.tvMetric4Label.text = "Hours"
                    b.tvMetric4Value.text = "${String.format(Locale.US, "%.1f", item.earnedHours)}h"
                }
                is LocalDailySummary -> {
                    b.tvCardIcon.text = "🗓️"
                    b.tvCardTitle.text = item.shiftDate
                    b.tvCardSubtitle.text = "Employee ID: ${item.employeeId}"

                    b.tvCardStatusBadge.text = item.status
                    b.tvCardStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                    b.tvCardStatusBadge.setTextColor(getColor(R.color.green_900))

                    b.tvMetric1Label.text = "Worked"
                    b.tvMetric1Value.text = "${String.format(Locale.US, "%.2f", item.earnedStandardHours)}h"

                    b.tvMetric2Label.text = "Overtime"
                    b.tvMetric2Value.text = formatHoursMinutes(item.totalOvertimeMs)

                    b.tvMetric3Label.text = "Penalty"
                    b.tvMetric3Value.text = formatHoursMinutes(item.totalPenaltyMs)

                    b.tvMetric4Label.text = "Allowance"
                    b.tvMetric4Value.text = "₹${String.format(Locale.US, "%.0f", item.shiftAllowanceEarned)}"
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
