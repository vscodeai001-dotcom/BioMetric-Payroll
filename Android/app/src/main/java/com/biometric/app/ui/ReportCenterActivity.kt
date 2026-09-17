package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.ConsolidatedAttendanceRow
import com.biometric.app.data.entity.FinancialRegisterRow
import com.biometric.app.data.entity.LocalDailySummary
import com.biometric.app.data.entity.PayrollVarianceRow
import com.biometric.app.databinding.ActivityReportCenterBinding
import com.biometric.app.ui.viewmodel.ReportViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class ReportCenterActivity : AppCompatActivity() {

    private var _binding: ActivityReportCenterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    private var selectedReportType = "ATTENDANCE_MONTHLY_SUMMARY"
    private var selectedEmployeeId: Int? = null
    
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var startDate = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var endDate = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityReportCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.etStartDate.setText(sdf.format(startDate.time))
        binding.etEndDate.setText(sdf.format(endDate.time))

        binding.etStartDate.setOnClickListener { showDatePicker { startDate = it; binding.etStartDate.setText(sdf.format(it.time)) } }
        binding.etEndDate.setOnClickListener { showDatePicker { endDate = it; binding.etEndDate.setText(sdf.format(it.time)) } }

        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { employees ->
                val names = listOf("All Employees") + employees.map { "${it.employeeId} • ${it.name}" }
                binding.spEmployee.setAdapter(ArrayAdapter(this@ReportCenterActivity, android.R.layout.simple_dropdown_item_1line, names))
                binding.spEmployee.setOnItemClickListener { _, _, position, _ ->
                    selectedEmployeeId = if (position == 0) null else employees.getOrNull(position - 1)?.employeeId?.toIntOrNull()
                }
            }
        }

        setupReportTypeTiles()

        binding.btnGenerate.setOnClickListener {
            viewModel.generateReport(
                selectedReportType,
                sdf.format(startDate.time),
                sdf.format(endDate.time),
                endDate.get(Calendar.YEAR),
                endDate.get(Calendar.MONTH) + 1,
                selectedEmployeeId
            )
        }

        binding.rvResults.layoutManager = LinearLayoutManager(this)
    }

    private fun setupReportTypeTiles() {
        val types = listOf(
            Triple("ATTENDANCE_MONTHLY_SUMMARY", "Monthly Attendance", "⏱️"),
            Triple("PAYROLL_VARIANCE", "Payroll Variance", "📈"),
            Triple("FINANCIAL_REGISTER", "Financial Register", "📋")
        )

        types.forEach { (type, name, emoji) ->
            val view = layoutInflater.inflate(R.layout.item_report_type, binding.llReportTypes, false)
            view.findViewById<TextView>(R.id.tvEmoji).text = emoji
            view.findViewById<TextView>(R.id.tvName).text = name
            
            view.setOnClickListener {
                selectedReportType = type
                binding.tilEmployee.visibility = if (type == "ATTENDANCE_MONTHLY_SUMMARY") View.VISIBLE else View.GONE
                updateTileSelection()
            }
            view.tag = type
            binding.llReportTypes.addView(view)
        }
        updateTileSelection()
    }

    private fun updateTileSelection() {
        for (i in 0 until binding.llReportTypes.childCount) {
            val child = binding.llReportTypes.getChildAt(i)
            child.alpha = if (child.tag == selectedReportType) 1f else 0.5f
            child.scaleX = if (child.tag == selectedReportType) 1.05f else 1.0f
            child.scaleY = if (child.tag == selectedReportType) 1.05f else 1.0f
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        _binding?.progressBar?.isVisible = loading
                    }
                }
                launch {
                    viewModel.reportData.collectLatest { data ->
                        _binding?.let { b ->
                            b.tvEmptyState.isVisible = data == null
                            if (data != null) {
                                b.rvResults.adapter = ReportAdapter(data)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    private fun showDatePicker(onDateSelected: (Calendar) -> Unit) {
        val current = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance()
            cal.set(y, m, d)
            onDateSelected(cal)
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
    }

    inner class ReportAdapter(private val items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)

            when (item) {
                is ConsolidatedAttendanceRow -> {
                    tvTitle.text = "👤 ${item.employeeName}"
                    tvDate.text = "⏱️ Worked: ${String.format("%.1f", item.totalWorkedHours)}h"
                    tvReason.text = "🔴 Absent: ${item.totalAbsentDays} days"
                    tvIcon.text = "📊"
                }
                is PayrollVarianceRow -> {
                    tvTitle.text = "👤 ${item.employeeName}"
                    tvDate.text = "💵 Curr: ₹${item.currentNet} | Prev: ₹${item.previousNet}"
                    tvReason.text = "📊 Diff: ₹${item.difference}"
                    tvIcon.text = if (item.difference >= 0) "📈" else "📉"
                }
                is FinancialRegisterRow -> {
                    tvTitle.text = "👤 ${item.employeeName}"
                    tvDate.text = "💎 Net: ₹${item.netPayable} | ⏱️ Hours: ${item.earnedHours}"
                    tvReason.text = "📉 Deductions: ₹${item.totalDeductions}"
                    tvIcon.text = "💰"
                }
                is LocalDailySummary -> {
                    tvTitle.text = "🗓️ ${item.shiftDate} • ${item.status}"
                    tvDate.text = "⏱️ Worked: ${String.format(Locale.US, "%.2f", item.earnedStandardHours)}h"
                    tvReason.text = "🟢 OT: ${item.totalOvertimeMs / 3600000.0}h | ⚠️ Penalty: ${item.totalPenaltyMs / 60000}m"
                    tvIcon.text = "📅"
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
