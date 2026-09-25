package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.ActivityAdminAttendanceBinding
import com.biometric.app.databinding.ItemAdminAttendanceBinding
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.ui.viewmodel.SharedViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AdminAttendanceActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminAttendanceBinding

    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator

    private lateinit var adapter: AttendanceAdapter
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
    private val punchTimeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val shiftDateFormat = SimpleDateFormat("dd-MMM (EEE)", Locale.getDefault())

    private var fromCalendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var toCalendar = Calendar.getInstance()
    private var employeeFilterId: Int? = null
    private var statusFilter: String = "ALL" // ALL, PRESENT, ABSENT, HALFDAY, LATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.clAdminAttendanceRoot)

        setupToolbar()
        setupDatePickers()
        setupRecyclerView()
        setupStatusFilterChips()
        setupListeners()
        observeData()

        realtimeCoordinator.start {
            if (!isFinishing && !isDestroyed) {
                sharedViewModel.warmUpDashboard()
                render()
            }
        }
        sharedViewModel.warmUpDashboard()
        render()
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDatePickers() {
        binding.tvFrom.text = displayDateFormat.format(fromCalendar.time)
        binding.tvTo.text = displayDateFormat.format(toCalendar.time)

        binding.cardFromDate.setOnClickListener {
            showDatePicker(fromCalendar) { picked ->
                fromCalendar = picked
                binding.tvFrom.text = displayDateFormat.format(fromCalendar.time)
                render()
            }
        }

        binding.cardToDate.setOnClickListener {
            showDatePicker(toCalendar) { picked ->
                toCalendar = picked
                binding.tvTo.text = displayDateFormat.format(toCalendar.time)
                render()
            }
        }
    }

    private fun showDatePicker(base: Calendar, onDateSelected: (Calendar) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val chosen = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                onDateSelected(chosen)
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupRecyclerView() {
        adapter = AttendanceAdapter { row ->
            // On correction requested, find employee and open manual attendance correction dialog
            val employee = sharedViewModel.allEmployees.value.find { it.employeeId.toIntOrNull() == row.employeeID }
            if (employee != null) {
                ManualAttendanceDialogFragment.newInstance(employee).show(
                    supportFragmentManager,
                    ManualAttendanceDialogFragment.TAG
                )
            }
        }
        binding.rvAttendance.layoutManager = LinearLayoutManager(this)
        binding.rvAttendance.adapter = adapter
    }

    private fun setupStatusFilterChips() {
        binding.chipGroupAttendanceFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chipPresent -> "PRESENT"
                R.id.chipAbsent -> "ABSENT"
                R.id.chipHalfDay -> "HALFDAY"
                R.id.chipLate -> "LATE"
                else -> "ALL"
            }
            render()
        }
    }

    private fun setupListeners() {
        binding.btnGenerate.setOnClickListener {
            binding.progress.isVisible = true
            sharedViewModel.warmUpDashboard()
            render()
        }

        binding.swipeRefresh.setOnRefreshListener {
            sharedViewModel.warmUpDashboard()
            render()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            sharedViewModel.allDailySummaries.collectLatest { render() }
        }
        lifecycleScope.launch {
            sharedViewModel.allAttendancePunches.collectLatest { render() }
        }
        lifecycleScope.launch {
            sharedViewModel.allAttendance.collectLatest { render() }
        }
        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { employees ->
                val items = mutableListOf("All Employees")
                items += employees.sortedBy { it.name }.map { "${it.name} (#${it.employeeId})" }
                val spinnerAdapter = ArrayAdapter(this@AdminAttendanceActivity, android.R.layout.simple_spinner_dropdown_item, items)
                binding.spEmployee.adapter = spinnerAdapter
                binding.spEmployee.setSelection(0)
                render()
            }
        }

        binding.spEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                employeeFilterId = null
                render()
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                employeeFilterId = sharedViewModel.allEmployees.value.sortedBy { it.name }.getOrNull(position - 1)?.employeeId?.toIntOrNull()
                render()
            }
        }
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        binding.progress.isVisible = false

        val f = isoDateFormat.format(fromCalendar.time)
        val t = isoDateFormat.format(toCalendar.time)

        val employees = sharedViewModel.allEmployees.value.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        val summariesInRange = sharedViewModel.allDailySummaries.value.filter {
            it.shiftDate in f..t && (employeeFilterId == null || it.employeeId == employeeFilterId)
        }
        val allPunches = sharedViewModel.allAttendancePunches.value
        val allAttendance = sharedViewModel.allAttendance.value

        // Compute KPI Cumulative Metrics across all filtered summaries
        val employeesProcessed = summariesInRange.map { it.employeeId }.distinct().count()
        val scheduledMs = summariesInRange.sumOf { it.scheduledShiftDurationMs }
        val workedHours = summariesInRange.sumOf { it.earnedStandardHours }
        val otMs = summariesInRange.sumOf { it.totalOvertimeMs }
        val penaltyMs = summariesInRange.sumOf { it.totalPenaltyMs }
        val latenessMs = summariesInRange.sumOf { it.totalLatenessMs }
        val breakPenaltyMs = summariesInRange.sumOf { it.totalBreakPenaltyMs }

        binding.tvScheduled.text = formatDurationMs(scheduledMs)
        binding.tvWorked.text = formatWorkedHours(workedHours)
        binding.tvOvertime.text = formatDurationMs(otMs)
        binding.tvPenalty.text = formatDurationMs(penaltyMs)
        binding.tvLateness.text = formatDurationMs(latenessMs)
        binding.tvBreakPenalty.text = formatDurationMs(breakPenaltyMs)

        binding.tvSummary.text = if (summariesInRange.isEmpty()) {
            "No attendance logs recorded for selected period"
        } else {
            "👥 $employeesProcessed employees • ⏱️ Worked ${formatWorkedHours(workedHours)} • 📅 Scheduled ${formatDurationMs(scheduledMs)}"
        }

        // Map to display row objects
        val allRows = summariesInRange.map { s ->
            val emp = employees[s.employeeId]
            val dateParsed = runCatching { isoDateFormat.parse(s.shiftDate) }.getOrNull()
            val formattedDate = if (dateParsed != null) shiftDateFormat.format(dateParsed) else s.shiftDate

            // 1. Direct punches from attendance_punches (SSOT ledger matching Web)
            val directPunches = allPunches.filter { p ->
                val idMatches = p.staffId == s.employeeId.toString() ||
                    p.staffId.toIntOrNull() == s.employeeId ||
                    (emp != null && emp.biometricId.isNotBlank() && p.staffId == emp.biometricId)
                val dateMatches = p.date == s.shiftDate ||
                    (p.timestamp > 0 && isoDateFormat.format(Date(p.timestamp)) == s.shiftDate)
                idMatches && dateMatches
            }.map { p ->
                Pair(p.timestamp, p.type.ifBlank { "IN" })
            }

            // 2. Only fall back to attendance session table if no direct punches exist
            val resolvedPunches = if (directPunches.isNotEmpty()) {
                directPunches
            } else {
                allAttendance.filter { a ->
                    val idMatches = a.employeeId == s.employeeId.toString() ||
                        a.employeeId.toIntOrNull() == s.employeeId ||
                        (emp != null && emp.biometricId.isNotBlank() && a.employeeId == emp.biometricId)
                    val dateMatches = a.checkInTime > 0 && isoDateFormat.format(Date(a.checkInTime)) == s.shiftDate
                    idMatches && dateMatches
                }.flatMap { a ->
                    val list = mutableListOf<Pair<Long, String>>()
                    if (a.checkInTime > 0) list.add(Pair(a.checkInTime, "IN"))
                    val outTime = a.checkOutTime ?: 0L
                    if (outTime > 0) list.add(Pair(outTime, "OUT"))
                    list
                }
            }.distinctBy { Pair(it.first / 60000, it.second) }
             .sortedBy { it.first }

            val rawPunchesText = if (resolvedPunches.isNotEmpty()) {
                resolvedPunches.joinToString("  •  ") { (timeMs, type) ->
                    val time = punchTimeFormat.format(Date(timeMs))
                    "$time $type"
                }
            } else {
                "No punches recorded"
            }

            val scheduledDuration = formatDurationMs(s.scheduledShiftDurationMs)
            val scheduledShiftText = if (s.scheduledShiftDurationMs > 0) {
                "⏰ Scheduled Shift: $scheduledDuration"
            } else {
                "⏰ Shift: Flexible / Unscheduled"
            }

            AdminAttendanceRow(
                employeeID = s.employeeId,
                employeeName = emp?.name ?: "Employee #${s.employeeId}",
                date = s.shiftDate,
                formattedDate = formattedDate,
                status = s.status.ifBlank { "Absent" },
                workedHours = s.earnedStandardHours,
                overtimeMinutes = s.totalOvertimeMs / 60000.0,
                penaltyMinutes = s.totalPenaltyMs / 60000.0,
                latenessMinutes = s.totalLatenessMs / 60000.0,
                breakPenaltyMinutes = s.totalBreakPenaltyMs / 60000.0,
                scheduledMinutes = s.scheduledShiftDurationMs / 60000.0,
                scheduledShiftText = scheduledShiftText,
                punches = rawPunchesText
            )
        }

        // Apply quick status chip filter
        val filteredRows = when (statusFilter) {
            "PRESENT" -> allRows.filter { it.status.equals("Present", ignoreCase = true) }
            "ABSENT" -> allRows.filter { it.status.equals("Absent", ignoreCase = true) }
            "HALFDAY" -> allRows.filter { it.status.contains("Half", ignoreCase = true) }
            "LATE" -> allRows.filter { it.latenessMinutes > 0 }
            else -> allRows
        }.sortedWith(compareByDescending<AdminAttendanceRow> { it.date }.thenBy { it.employeeName })

        adapter.submit(filteredRows)
        binding.llEmptyState.isVisible = filteredRows.isEmpty()
        binding.rvAttendance.isVisible = filteredRows.isNotEmpty()
    }

    private fun formatDurationMs(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.US, "%dh %02dm", hours, minutes)
    }

    private fun formatMinutesDuration(totalMinutes: Double, isOt: Boolean = false): String {
        val totalMins = kotlin.math.round(totalMinutes).toInt()
        if (totalMins <= 0) return "0m"
        val prefix = if (isOt) "+" else ""
        return if (totalMins >= 60) {
            val hours = totalMins / 60
            val mins = totalMins % 60
            if (mins == 0) "$prefix${hours}h" else String.format(Locale.US, "%s%dh %02dm", prefix, hours, mins)
        } else {
            "$prefix${totalMins}m"
        }
    }

    private fun formatWorkedHours(hoursDecimal: Double): String {
        val totalMins = kotlin.math.round(hoursDecimal * 60.0).toInt()
        val hours = totalMins / 60
        val mins = totalMins % 60
        return String.format(Locale.US, "%dh %02dm", hours, mins)
    }

    data class AdminAttendanceRow(
        val employeeID: Int,
        val employeeName: String,
        val date: String,
        val formattedDate: String,
        val status: String,
        val workedHours: Double,
        val overtimeMinutes: Double,
        val penaltyMinutes: Double,
        val latenessMinutes: Double,
        val breakPenaltyMinutes: Double,
        val scheduledMinutes: Double,
        val scheduledShiftText: String,
        val punches: String
    )

    private inner class AttendanceAdapter(
        private val onCorrectionClick: (AdminAttendanceRow) -> Unit
    ) : RecyclerView.Adapter<AttendanceAdapter.Holder>() {

        private val data = mutableListOf<AdminAttendanceRow>()

        fun submit(rows: List<AdminAttendanceRow>) {
            data.clear()
            data.addAll(rows)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemAdminAttendanceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return Holder(itemBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount() = data.size

        inner class Holder(private val itemBinding: ItemAdminAttendanceBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: AdminAttendanceRow) {
                itemBinding.tvTitle.text = item.employeeName
                itemBinding.tvDateSubtitle.text = "${item.formattedDate} • Staff #${item.employeeID}"
                itemBinding.tvStatus.text = item.status.uppercase(Locale.US)
                itemBinding.tvScheduledShift.text = item.scheduledShiftText

                // Status Badge Color
                when {
                    item.status.contains("Present", ignoreCase = true) -> {
                        itemBinding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        itemBinding.tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    item.status.contains("Absent", ignoreCase = true) -> {
                        itemBinding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                        itemBinding.tvStatus.setTextColor(Color.parseColor("#C62828"))
                    }
                    item.status.contains("Half", ignoreCase = true) -> {
                        itemBinding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        itemBinding.tvStatus.setTextColor(Color.parseColor("#EF6C00"))
                    }
                    item.status.contains("Leave", ignoreCase = true) -> {
                        itemBinding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ECEFF1"))
                        itemBinding.tvStatus.setTextColor(Color.parseColor("#546E7A"))
                    }
                    else -> {
                        itemBinding.tvStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EDE7F6"))
                        itemBinding.tvStatus.setTextColor(Color.parseColor("#5E35B1"))
                    }
                }

                // Metrics (Hours & Minutes format matching Web SSOT)
                itemBinding.tvMetricWorked.text = formatWorkedHours(item.workedHours)
                itemBinding.tvMetricOt.text = formatMinutesDuration(item.overtimeMinutes, isOt = true)
                itemBinding.tvMetricLate.text = formatMinutesDuration(item.latenessMinutes)
                itemBinding.tvMetricPenalty.text = formatMinutesDuration(item.penaltyMinutes)
                itemBinding.tvMetricBreak.text = formatMinutesDuration(item.breakPenaltyMinutes)

                // Raw Punches
                itemBinding.tvDetails.text = item.punches

                // Manual Punch Correction button
                itemBinding.btnCorrectPunch.setOnClickListener {
                    onCorrectionClick(item)
                }
            }
        }
    }
}
