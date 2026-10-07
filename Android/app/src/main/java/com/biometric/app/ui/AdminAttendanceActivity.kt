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
import com.biometric.app.domain.attendance.AttendancePunchProcessor
import com.biometric.app.domain.attendance.ProcessedPunchItem
import com.biometric.app.domain.attendance.PunchSourceTier
import com.biometric.app.ui.viewmodel.SharedViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@AndroidEntryPoint
class AdminAttendanceActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminAttendanceBinding

    @Inject lateinit var sharedViewModel: SharedViewModel

    private lateinit var adapter: AttendanceAdapter
    companion object {
        private val istTimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istTimeZone }
    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).apply { timeZone = istTimeZone }
    private val punchTimeFormat = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = istTimeZone }
    private val shiftDateFormat = SimpleDateFormat("dd-MMM (EEE)", Locale.getDefault()).apply { timeZone = istTimeZone }

    /**
     * Deduplicates a sorted list of (timestampMs, type) punch pairs for display.
     *
     * Rules:
     * 1. Same direction (type) within 60 seconds → keep first, drop duplicate.
     * 2. Opposite direction within 60 seconds (geofence/Android sync race) →
     *    keep whichever matches the expected alternating position
     *    (even index = IN, odd index = OUT). Discard the other.
     *
     * This mirrors the C# AttendancePunchProcessor logic on Android.
     */
    private fun deduplicatePunchesForDisplay(
        sorted: List<Pair<Long, String>>
    ): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        for (p in sorted) {
            val (tsMs, type) = p
            val isOutNew = type.equals("OUT", ignoreCase = true)

            val matchIdx = result.indexOfFirst { (existTs, _) ->
                Math.abs(existTs - tsMs) < 60_000L
            }

            if (matchIdx < 0) {
                result.add(p)
                continue
            }

            val (_, existType) = result[matchIdx]
            val isOutExist = existType.equals("OUT", ignoreCase = true)

            if (isOutExist == isOutNew) {
                // Same direction within 60 s — clear duplicate, keep existing.
                continue
            } else {
                // Opposite direction within 60 s — geofence sync race.
                // Keep the punch that fits the expected alternating position.
                val expectedOutAtIdx = (matchIdx % 2 != 0) // even = IN, odd = OUT
                if (expectedOutAtIdx == isOutExist) {
                    // Existing is correctly positioned — drop incoming.
                    continue
                } else {
                    // Incoming fits better — replace existing.
                    result[matchIdx] = p
                }
            }
        }
        return result
    }

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

        render()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(0, 1001, 0, "Raw Punches 🔍").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                startActivity(android.content.Intent(this@AdminAttendanceActivity, RawPunchInspectorActivity::class.java))
                true
            }
        }
        binding.toolbar.menu.add(0, 1002, 0, "Punch Correction ✏️").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                startActivity(android.content.Intent(this@AdminAttendanceActivity, AdminManualPunchCorrectionActivity::class.java))
                true
            }
        }
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
            // On correction requested, find employee and open manual attendance correction dialog for that specific date
            val employee = sharedViewModel.allEmployees.value.find { it.employeeId.toIntOrNull() == row.employeeID }
            if (employee != null) {
                val rowDateMillis = runCatching {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    }.parse(row.date)?.time
                }.getOrNull()

                ManualAttendanceDialogFragment.newInstance(employee, null, rowDateMillis).show(
                    supportFragmentManager,
                    ManualAttendanceDialogFragment.TAG
                )
            }
        }
        adapter.setHasStableIds(true)
        binding.rvAttendance.layoutManager = LinearLayoutManager(this)
        binding.rvAttendance.adapter = adapter
        binding.rvAttendance.setItemViewCacheSize(20)
        binding.rvAttendance.recycledViewPool.setMaxRecycledViews(0, 20)
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
            render()
        }

        binding.swipeRefresh.setOnRefreshListener {
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
                val prevSelectedId = employeeFilterId
                val sorted = employees.sortedBy { it.name }
                val items = mutableListOf("All Employees")
                items += sorted.map { "${it.name} (#${it.employeeId})" }
                val spinnerAdapter = ArrayAdapter(this@AdminAttendanceActivity, android.R.layout.simple_spinner_dropdown_item, items)
                binding.spEmployee.adapter = spinnerAdapter
                val selectedIndex = if (prevSelectedId != null) {
                    val idx = sorted.indexOfFirst { it.employeeId.toIntOrNull() == prevSelectedId }
                    if (idx >= 0) idx + 1 else 0
                } else {
                    0
                }
                binding.spEmployee.setSelection(selectedIndex)
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

    private var renderJob: kotlinx.coroutines.Job? = null

    private fun render() {
        if (!::adapter.isInitialized) return
        // Debounce: cancel any pending render within 80ms and re-schedule.
        // This merges multiple rapid Flow emissions (employees, punches, summaries) into
        // a single render pass, preventing main-thread overload on startup/wipe.
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(80)
            doRender()
        }
    }

    private suspend fun doRender() {
        binding.progress.isVisible = false

        val f = isoDateFormat.format(fromCalendar.time)
        val t = isoDateFormat.format(toCalendar.time)

        // Snapshot all StateFlow data on Main, then crunch off Main
        val employees = sharedViewModel.allEmployees.value.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        // Deduplicate by (employeeId, shiftDate): Firebase may have synced multiple entries
        // for the same employee+date. Keep the one with the most complete data (highest
        // earnedStandardHours, then latest summaryId as tiebreaker). Application-level fix only.
        val summariesInRange = sharedViewModel.allDailySummaries.value
            .filter { it.shiftDate in f..t && (employeeFilterId == null || it.employeeId == employeeFilterId) }
            .groupBy { Pair(it.employeeId, it.shiftDate) }
            .map { (_, group) ->
                group.maxByOrNull { it.earnedStandardHours * 1_000_000 + it.summaryId.toLong() }!!
            }
        val allPunches = sharedViewModel.allAttendancePunches.value
        val allAttendance = sharedViewModel.allAttendance.value

        // ─── Heavy computation on background thread ───────────────────────────
        data class RenderResult(
            val filteredRows: List<AdminAttendanceRow>,
            val employeesProcessed: Int,
            val scheduledMs: Long,
            val workedHours: Double,
            val otMs: Long,
            val penaltyMs: Long,
            val latenessMs: Long,
            val breakPenaltyMs: Long,
            val isEmpty: Boolean,
            val summaryText: String
        )

        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {

        // Map existing summary row objects
        val allRows = summariesInRange.map { s ->
            val emp = employees[s.employeeId]
            val dateParsed = runCatching { isoDateFormat.parse(s.shiftDate) }.getOrNull()
            val formattedDate = if (dateParsed != null) shiftDateFormat.format(dateParsed) else s.shiftDate

            // 1. Direct punches from attendance_punches (SSOT ledger matching Web)
            val matchingPunches = allPunches.filter { p ->
                val idMatches = p.staffId == s.employeeId.toString() ||
                    p.staffId.toIntOrNull() == s.employeeId ||
                    (emp != null && emp.biometricId.isNotBlank() && p.staffId == emp.biometricId)
                val dateMatches = p.date == s.shiftDate ||
                    (p.timestamp > 0 && isoDateFormat.format(Date(p.timestamp)) == s.shiftDate)
                idMatches && dateMatches
            }

            val punchItems = if (matchingPunches.isNotEmpty()) {
                matchingPunches.map { p ->
                    ProcessedPunchItem(
                        id = p.punchId,
                        staffId = p.staffId,
                        timestamp = p.timestamp,
                        type = p.type.ifBlank { "IN" },
                        deviceId = p.deviceId,
                        biometricId = p.punchId,
                        source = p.source
                    )
                }
            } else {
                allAttendance.filter { a ->
                    val idMatches = a.employeeId == s.employeeId.toString() ||
                        a.employeeId.toIntOrNull() == s.employeeId ||
                        (emp != null && emp.biometricId.isNotBlank() && a.employeeId == emp.biometricId)
                    val dateMatches = a.checkInTime > 0 && isoDateFormat.format(Date(a.checkInTime)) == s.shiftDate
                    idMatches && dateMatches
                }.flatMap { a ->
                    val list = mutableListOf<ProcessedPunchItem>()
                    if (a.checkInTime > 0) list.add(ProcessedPunchItem(timestamp = a.checkInTime, type = "IN", deviceId = "ManualCorrection"))
                    val outTime = a.checkOutTime ?: 0L
                    if (outTime > 0) list.add(ProcessedPunchItem(timestamp = outTime, type = "OUT", deviceId = "ManualCorrection"))
                    list
                }
            }

            // Run 3-Tier Hybrid Processor (exact mirror of Web AttendancePunchProcessor)
            val processed = AttendancePunchProcessor.processPunches(punchItems)
            val effectivePunches = processed.ordered

            val rawPunchesText = if (effectivePunches.isNotEmpty()) {
                effectivePunches.joinToString("  •  ") { p ->
                    val time = punchTimeFormat.format(Date(p.timestamp))
                    val type = p.type.uppercase()
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

            // FIX: If daily_summaries still shows "Present" but actual punches are gone (post-wipe
            // stale data scenario), override the status so UI matches reality.
            val effectiveStatus = when {
                effectivePunches.isNotEmpty() -> s.status.ifBlank { "Present" }
                s.status.equals("Weekly Off", ignoreCase = true) ||
                s.status.equals("WeeklyOff", ignoreCase = true) ||
                s.status.equals("Week Off", ignoreCase = true) -> s.status
                s.status.equals("Holiday", ignoreCase = true) -> s.status
                s.status.equals("Leave", ignoreCase = true) -> s.status
                else -> "Absent" // no punches → absent (overrides stale "Present")
            }

            AdminAttendanceRow(
                employeeID = s.employeeId,
                employeeName = emp?.name ?: "Employee #${s.employeeId}",
                date = s.shiftDate,
                formattedDate = formattedDate,
                status = effectiveStatus,
                workedHours = if (effectivePunches.isEmpty()) 0.0 else s.earnedStandardHours,
                overtimeMinutes = s.totalOvertimeMs / 60000.0,
                penaltyMinutes = s.totalPenaltyMs / 60000.0,
                latenessMinutes = s.totalLatenessMs / 60000.0,
                breakPenaltyMinutes = s.totalBreakPenaltyMs / 60000.0,
                scheduledMinutes = s.scheduledShiftDurationMs / 60000.0,
                scheduledShiftText = scheduledShiftText,
                punches = rawPunchesText
            )
        }.toMutableList()

        // Synthesize missing active employee records for all calendar dates in the requested range
        val distinctDates = mutableSetOf<String>()
        val cal = (fromCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (toCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        while (!cal.after(endCal)) {
            distinctDates.add(isoDateFormat.format(cal.time))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        for (s in summariesInRange) {
            distinctDates.add(s.shiftDate)
        }
        for (p in allPunches) {
            val d = p.date.ifBlank { if (p.timestamp > 0) isoDateFormat.format(Date(p.timestamp)) else "" }
            if (d.isNotBlank() && d in f..t) {
                distinctDates.add(d)
            }
        }
        for (a in allAttendance) {
            if (a.checkInTime > 0) {
                val d = isoDateFormat.format(Date(a.checkInTime))
                if (d in f..t) {
                    distinctDates.add(d)
                }
            }
        }

        val existingKeys = summariesInRange.map { "${it.employeeId}:${it.shiftDate}" }.toHashSet()
        val activeEmps = sharedViewModel.allEmployees.value.filter {
            (it.isActive) && (employeeFilterId == null || it.employeeId.toIntOrNull() == employeeFilterId)
        }

        val synthesizedRows = mutableListOf<AdminAttendanceRow>()
        for (dateStr in distinctDates) {
            val dateParsed = runCatching { isoDateFormat.parse(dateStr) }.getOrNull()
            val formattedDate = if (dateParsed != null) shiftDateFormat.format(dateParsed) else dateStr

            for (emp in activeEmps) {
                val empIdInt = emp.employeeId.toIntOrNull() ?: continue
                if (existingKeys.contains("$empIdInt:$dateStr")) continue

                // Check for direct punches
                val matchingPunches = allPunches.filter { p ->
                    val idMatches = p.staffId == emp.employeeId ||
                        p.staffId.toIntOrNull() == empIdInt ||
                        (emp.biometricId.isNotBlank() && p.staffId == emp.biometricId)
                    val dateMatches = p.date == dateStr ||
                        (p.timestamp > 0 && isoDateFormat.format(Date(p.timestamp)) == dateStr)
                    idMatches && dateMatches
                }

                val punchItems = if (matchingPunches.isNotEmpty()) {
                    matchingPunches.map { p ->
                        ProcessedPunchItem(
                            id = p.punchId,
                            staffId = p.staffId,
                            timestamp = p.timestamp,
                            type = p.type.ifBlank { "IN" },
                            deviceId = p.deviceId,
                            biometricId = p.punchId,
                            source = p.source
                        )
                    }
                } else {
                    allAttendance.filter { a ->
                        val idMatches = a.employeeId == emp.employeeId ||
                            a.employeeId.toIntOrNull() == empIdInt ||
                            (emp.biometricId.isNotBlank() && a.employeeId == emp.biometricId)
                        val dateMatches = a.checkInTime > 0 && isoDateFormat.format(Date(a.checkInTime)) == dateStr
                        idMatches && dateMatches
                    }.flatMap { a ->
                        val list = mutableListOf<ProcessedPunchItem>()
                        if (a.checkInTime > 0) list.add(ProcessedPunchItem(timestamp = a.checkInTime, type = "IN", deviceId = "ManualCorrection"))
                        val outTime = a.checkOutTime ?: 0L
                        if (outTime > 0) list.add(ProcessedPunchItem(timestamp = outTime, type = "OUT", deviceId = "ManualCorrection"))
                        list
                    }
                }

                val processed = AttendancePunchProcessor.processPunches(punchItems)
                val effectivePunches = processed.ordered

                val rawPunchesText = if (effectivePunches.isNotEmpty()) {
                    effectivePunches.joinToString("  •  ") { p ->
                        val time = punchTimeFormat.format(Date(p.timestamp))
                        val type = p.type.uppercase()
                        "$time $type"
                    }
                } else {
                    "No punches recorded"
                }

                val status = if (effectivePunches.isNotEmpty()) {
                    if (effectivePunches.size % 2 == 1) "Missing Punch" else "Present"
                } else {
                    val dayOfWeek = runCatching {
                        val c = Calendar.getInstance().apply { time = isoDateFormat.parse(dateStr)!! }
                        c.get(Calendar.DAY_OF_WEEK)
                    }.getOrDefault(Calendar.MONDAY)
                    val isSunday = dayOfWeek == Calendar.SUNDAY
                    val isCompOff = (emp.compOffDayOfWeek != null && emp.compOffDayOfWeek == (dayOfWeek - 1))
                    if (isSunday || isCompOff) "Weekly Off" else "Absent"
                }

                val schedMs = if (!emp.shiftStart.isNullOrBlank() && !emp.shiftEnd.isNullOrBlank()) {
                    runCatching {
                        val s = punchTimeFormat.parse(emp.shiftStart)
                        val e = punchTimeFormat.parse(emp.shiftEnd)
                        if (s != null && e != null) {
                            var diff = e.time - s.time
                            if (diff < 0) diff += 24 * 3600 * 1000
                            diff
                        } else 0L
                    }.getOrDefault(0L)
                } else 0L

                val scheduledDuration = formatDurationMs(schedMs)
                val scheduledShiftText = if (schedMs > 0) {
                    "⏰ Scheduled Shift: $scheduledDuration"
                } else {
                    "⏰ Shift: Flexible / Unscheduled"
                }

                var calculatedWorkedMs = 0L
                var inTime: Long? = null
                for (p in effectivePunches) {
                    if (p.type.equals("IN", ignoreCase = true)) {
                        inTime = p.timestamp
                    } else if (p.type.equals("OUT", ignoreCase = true) && inTime != null) {
                        calculatedWorkedMs += (p.timestamp - inTime).coerceAtLeast(0L)
                        inTime = null
                    }
                }
                val calculatedWorkedHours = calculatedWorkedMs / 3600000.0

                synthesizedRows.add(
                    AdminAttendanceRow(
                        employeeID = empIdInt,
                        employeeName = emp.name,
                        date = dateStr,
                        formattedDate = formattedDate,
                        status = status,
                        workedHours = calculatedWorkedHours,
                        overtimeMinutes = 0.0,
                        penaltyMinutes = 0.0,
                        latenessMinutes = 0.0,
                        breakPenaltyMinutes = 0.0,
                        scheduledMinutes = schedMs / 60000.0,
                        scheduledShiftText = scheduledShiftText,
                        punches = rawPunchesText
                    )
                )
            }
        }

        val combinedRows = (allRows + synthesizedRows).distinctBy { Pair(it.employeeID, it.date) }

        // Compute KPI Cumulative Metrics across all filtered summaries and synthesized rows
        val employeesProcessed = combinedRows.map { it.employeeID }.distinct().count()
        val scheduledMs = summariesInRange.sumOf { it.scheduledShiftDurationMs } + synthesizedRows.sumOf { (it.scheduledMinutes * 60000).toLong() }
        val workedHours = summariesInRange.sumOf { it.earnedStandardHours } + synthesizedRows.sumOf { it.workedHours }
        val otMs = summariesInRange.sumOf { it.totalOvertimeMs }
        val penaltyMs = summariesInRange.sumOf { it.totalPenaltyMs }
        val latenessMs = summariesInRange.sumOf { it.totalLatenessMs }
        val breakPenaltyMs = summariesInRange.sumOf { it.totalBreakPenaltyMs }

        val summaryText = if (combinedRows.isEmpty()) {
            "No attendance logs recorded for selected period"
        } else {
            "👥 $employeesProcessed employees • ⏱️ Worked ${formatWorkedHours(workedHours)} • 📅 Scheduled ${formatDurationMs(scheduledMs)}"
        }

        // Apply quick status chip filter
        val filteredRows = when (statusFilter) {
            "PRESENT" -> combinedRows.filter { it.status.equals("Present", ignoreCase = true) }
            "ABSENT" -> combinedRows.filter { it.status.equals("Absent", ignoreCase = true) }
            "HALFDAY" -> combinedRows.filter { it.status.contains("Half", ignoreCase = true) }
            "LATE" -> combinedRows.filter { it.latenessMinutes > 0 }
            else -> combinedRows
        }.sortedWith(compareByDescending<AdminAttendanceRow> { it.date }.thenBy { it.employeeName })

        RenderResult(filteredRows, employeesProcessed, scheduledMs, workedHours, otMs, penaltyMs, latenessMs, breakPenaltyMs, filteredRows.isEmpty(), summaryText)
        } // end withContext(Default)

        // ─── UI updates on Main thread only ────────────────────────────────────
        binding.tvScheduled.text = formatDurationMs(result.scheduledMs)
        binding.tvWorked.text = formatWorkedHours(result.workedHours)
        binding.tvOvertime.text = formatDurationMs(result.otMs)
        binding.tvPenalty.text = formatDurationMs(result.penaltyMs)
        binding.tvLateness.text = formatDurationMs(result.latenessMs)
        binding.tvBreakPenalty.text = formatDurationMs(result.breakPenaltyMs)
        binding.tvSummary.text = result.summaryText

        adapter.submit(result.filteredRows)
        binding.llEmptyState.isVisible = result.isEmpty
        binding.rvAttendance.isVisible = !result.isEmpty
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
            val oldData = data.toList()
            val newData = rows.toList()
            val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size
                override fun getNewListSize() = newData.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val o = oldData[oldItemPosition]; val n = newData[newItemPosition]
                    return o.employeeID == n.employeeID && o.date == n.date
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    oldData[oldItemPosition] == newData[newItemPosition]
            })
            data.clear()
            data.addAll(newData)
            diffResult.dispatchUpdatesTo(this)
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

        override fun getItemId(position: Int): Long {
            val row = data[position]
            return (row.employeeID.toLong() shl 32) or (row.date.hashCode().toLong() and 0xFFFFFFFFL)
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
