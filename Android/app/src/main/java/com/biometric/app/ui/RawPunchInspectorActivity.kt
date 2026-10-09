package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.biometric.app.R
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.Employee
import com.biometric.app.domain.attendance.AttendancePunchProcessor
import com.biometric.app.domain.attendance.EvaluatedPunchRecord
import com.biometric.app.domain.attendance.PunchSourceTier
import com.biometric.app.domain.attendance.ProcessedPunchItem
import com.biometric.app.ui.adapter.RawPunchAdapter
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class RawPunchInspectorActivity : MotionBaseActivity() {

    @Inject lateinit var sharedViewModel: SharedViewModel

    // ------- Views -------
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var rvPunches: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResultsCount: TextView

    // KPI Views & Clickable containers
    private lateinit var tvKpiTotal: TextView
    private lateinit var tvKpiAccepted: TextView
    private lateinit var tvKpiSuppressed: TextView
    private lateinit var tvKpiPhysical: TextView
    private lateinit var tvKpiManual: TextView
    private lateinit var tvKpiGeo: TextView
    private lateinit var tvKpiAcceptedPct: TextView
    private lateinit var tvKpiSuppressedPct: TextView

    private lateinit var cardKpiTotal: View
    private lateinit var cardKpiAccepted: View
    private lateinit var cardKpiSuppressed: View
    private lateinit var cardKpiPhysical: View
    private lateinit var cardKpiManual: View
    private lateinit var cardKpiGeo: View

    // Filter Views
    private lateinit var etStartDate: EditText
    private lateinit var etEndDate: EditText
    private lateinit var spEmployee: Spinner
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnToggleFilters: MaterialButton
    private lateinit var btnResetFilters: TextView
    private lateinit var llAdvancedFilters: LinearLayout
    private lateinit var chipGroupMode: ChipGroup
    private lateinit var chipGroupStatus: ChipGroup
    private lateinit var chipGroupDirection: ChipGroup
    private lateinit var chipGroupQuick: ChipGroup

    // Action Buttons
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var btnOctMatrix: MaterialButton

    // ------- State -------
    private val adapter = RawPunchAdapter { punch -> showInspectionBottomSheet(punch) }

    private var allEvaluated: List<EvaluatedPunchRecord> = emptyList()
    private var employees: List<Employee> = emptyList()
    private var sortedEmployees: List<Employee> = emptyList()

    private val istTz = TimeZone.getTimeZone("Asia/Kolkata")
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istTz }
    private val displayFmt = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).apply { timeZone = istTz }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = istTz }

    private var startCal: Calendar = Calendar.getInstance(istTz).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private var endCal: Calendar = Calendar.getInstance(istTz).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }

    private var selectedEmployee: Employee? = null
    private var sourceTierFilter: PunchSourceTier? = null
    private var statusFilter: Boolean? = null   // null=all, true=accepted, false=suppressed
    private var directionFilter: String? = null  // null=all, "IN", "OUT"
    private var searchQuery: String = ""

    // ------- Lifecycle -------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_raw_punch_inspector)

        bindViews()
        setupToolbar()
        setupWindowInsets()
        setupRecyclerView()
        setupDatePickers()
        setupFilterChips()
        setupSearchBox()
        setupActionButtons()
        setupKpiClickFilters()
        observeData()
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.llRawPunchRoot)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarRawPunch)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val navBars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            toolbar.updatePadding(top = statusBarTop)
            nestedScroll.updatePadding(bottom = navBars.bottom + (32 * resources.displayMetrics.density).toInt())
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ------- Binding -------
    private fun bindViews() {
        swipeRefresh = findViewById(R.id.swipeRefreshRawPunch)
        nestedScroll = findViewById(R.id.nestedScrollRawPunch)
        rvPunches = findViewById(R.id.rvRawPunches)
        tvEmpty = findViewById(R.id.tvRawPunchEmpty)
        progressBar = findViewById(R.id.progressRawPunch)
        tvResultsCount = findViewById(R.id.tvResultsCount)

        tvKpiTotal = findViewById(R.id.tvKpiTotalCount)
        tvKpiAccepted = findViewById(R.id.tvKpiAcceptedCount)
        tvKpiSuppressed = findViewById(R.id.tvKpiSuppressedCount)
        tvKpiPhysical = findViewById(R.id.tvKpiPhysicalCount)
        tvKpiManual = findViewById(R.id.tvKpiManualCount)
        tvKpiGeo = findViewById(R.id.tvKpiGeoCount)
        tvKpiAcceptedPct = findViewById(R.id.tvKpiAcceptedPct)
        tvKpiSuppressedPct = findViewById(R.id.tvKpiSuppressedPct)

        cardKpiTotal = findViewById(R.id.cardKpiTotal)
        cardKpiAccepted = findViewById(R.id.cardKpiAccepted)
        cardKpiSuppressed = findViewById(R.id.cardKpiSuppressed)
        cardKpiPhysical = findViewById(R.id.cardKpiPhysical)
        cardKpiManual = findViewById(R.id.cardKpiManual)
        cardKpiGeo = findViewById(R.id.cardKpiGeo)

        etStartDate = findViewById(R.id.etStartDate)
        etEndDate = findViewById(R.id.etEndDate)
        spEmployee = findViewById(R.id.spEmployee)
        etSearch = findViewById(R.id.etSearchTelemetry)
        btnToggleFilters = findViewById(R.id.btnToggleFilters)
        btnResetFilters = findViewById(R.id.btnResetFilters)
        llAdvancedFilters = findViewById(R.id.llAdvancedFilters)
        chipGroupMode = findViewById(R.id.chipGroupMode)
        chipGroupStatus = findViewById(R.id.chipGroupStatus)
        chipGroupDirection = findViewById(R.id.chipGroupDirection)
        chipGroupQuick = findViewById(R.id.chipGroupQuickRange)

        btnRefresh = findViewById(R.id.btnRefreshTelemetry)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnOctMatrix = findViewById(R.id.btnOctMatrix)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarRawPunch)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        rvPunches.layoutManager = LinearLayoutManager(this)
        rvPunches.adapter = adapter
    }

    // ------- Date Pickers -------
    private fun setupDatePickers() {
        etStartDate.setText(displayFmt.format(startCal.time))
        etEndDate.setText(displayFmt.format(endCal.time))

        etStartDate.setOnClickListener {
            pickDate(startCal) { cal ->
                startCal = cal.apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                etStartDate.setText(displayFmt.format(startCal.time))
                chipGroupQuick.clearCheck()
                reloadData()
            }
        }
        etEndDate.setOnClickListener {
            pickDate(endCal) { cal ->
                endCal = cal.apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                etEndDate.setText(displayFmt.format(endCal.time))
                chipGroupQuick.clearCheck()
                reloadData()
            }
        }
    }

    private fun pickDate(base: Calendar, onPicked: (Calendar) -> Unit) {
        android.app.DatePickerDialog(this, { _, y, m, d ->
            onPicked(Calendar.getInstance(istTz).apply {
                set(y, m, d)
            })
        }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ------- Filter Chips & Controls -------
    private fun setupFilterChips() {
        // Toggle advanced filters
        btnToggleFilters.setOnClickListener {
            val isExpanded = llAdvancedFilters.isVisible
            llAdvancedFilters.isVisible = !isExpanded
            btnToggleFilters.text = if (!isExpanded) "Less ▴" else "More ▾"
        }

        btnResetFilters.setOnClickListener {
            resetAllFilters()
        }

        // Mode chips
        chipGroupMode.setOnCheckedStateChangeListener { group, _ ->
            sourceTierFilter = when (group.checkedChipId) {
                R.id.chipModePhysical -> PunchSourceTier.PhysicalMachine
                R.id.chipModeManual -> PunchSourceTier.ManualAdmin
                R.id.chipModeGeo -> PunchSourceTier.GeofenceAuto
                else -> null
            }
            applyFilter()
        }

        // Status chips
        chipGroupStatus.setOnCheckedStateChangeListener { group, _ ->
            statusFilter = when (group.checkedChipId) {
                R.id.chipStatusAccepted -> true
                R.id.chipStatusSuppressed -> false
                else -> null
            }
            applyFilter()
        }

        // Direction chips
        chipGroupDirection.setOnCheckedStateChangeListener { group, _ ->
            directionFilter = when (group.checkedChipId) {
                R.id.chipDirectionIn -> "IN"
                R.id.chipDirectionOut -> "OUT"
                else -> null
            }
            applyFilter()
        }

        // Quick range chips - wire direct click listeners and checked state listeners
        findViewById<View>(R.id.chipQuickToday)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickToday) }
        findViewById<View>(R.id.chipQuickYesterday)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickYesterday) }
        findViewById<View>(R.id.chipQuickLast7)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickLast7) }
        findViewById<View>(R.id.chipQuickLast30)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickLast30) }
        findViewById<View>(R.id.chipQuickThisMonth)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickThisMonth) }
        findViewById<View>(R.id.chipQuickOctMatrix)?.setOnClickListener { applyQuickDateRange(R.id.chipQuickOctMatrix) }

        chipGroupQuick.setOnCheckedStateChangeListener { group, _ ->
            if (group.checkedChipId != View.NO_ID) {
                applyQuickDateRange(group.checkedChipId)
            }
        }
    }

    private fun applyQuickDateRange(chipId: Int) {
        val now = Calendar.getInstance(istTz)
        var label = ""
        when (chipId) {
            R.id.chipQuickToday -> {
                startCal = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "Today"
            }
            R.id.chipQuickYesterday -> {
                startCal = (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "Yesterday"
            }
            R.id.chipQuickLast7 -> {
                startCal = (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, -6)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "Last 7 Days"
            }
            R.id.chipQuickLast30 -> {
                startCal = (now.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, -29)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "Last 30 Days"
            }
            R.id.chipQuickThisMonth -> {
                startCal = (now.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = (now.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "This Month"
            }
            R.id.chipQuickOctMatrix -> {
                startCal = Calendar.getInstance(istTz).apply {
                    set(2026, Calendar.OCTOBER, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                }
                endCal = Calendar.getInstance(istTz).apply {
                    set(2026, Calendar.OCTOBER, 31, 23, 59, 59); set(Calendar.MILLISECOND, 999)
                }
                label = "Oct 2026"
            }
            else -> return
        }

        chipGroupQuick.check(chipId)
        etStartDate.setText(displayFmt.format(startCal.time))
        etEndDate.setText(displayFmt.format(endCal.time))
        reloadData()
        if (label.isNotBlank()) {
            Toast.makeText(this, "📅 Range: $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupKpiClickFilters() {
        cardKpiTotal.setOnClickListener {
            sourceTierFilter = null
            statusFilter = null
            chipGroupMode.check(R.id.chipModeAll)
            chipGroupStatus.check(R.id.chipStatusAll)
            applyFilter()
        }
        cardKpiAccepted.setOnClickListener {
            statusFilter = true
            chipGroupStatus.check(R.id.chipStatusAccepted)
            applyFilter()
        }
        cardKpiSuppressed.setOnClickListener {
            statusFilter = false
            chipGroupStatus.check(R.id.chipStatusSuppressed)
            applyFilter()
        }
        cardKpiPhysical.setOnClickListener {
            sourceTierFilter = PunchSourceTier.PhysicalMachine
            chipGroupMode.check(R.id.chipModePhysical)
            applyFilter()
        }
        cardKpiManual.setOnClickListener {
            sourceTierFilter = PunchSourceTier.ManualAdmin
            chipGroupMode.check(R.id.chipModeManual)
            applyFilter()
        }
        cardKpiGeo.setOnClickListener {
            sourceTierFilter = PunchSourceTier.GeofenceAuto
            chipGroupMode.check(R.id.chipModeGeo)
            applyFilter()
        }
    }

    private fun setupSearchBox() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupActionButtons() {
        btnRefresh.setOnClickListener { reloadData() }
        swipeRefresh.setOnRefreshListener { reloadData() }
        btnExportCsv.setOnClickListener { exportCsv() }
        btnOctMatrix.setOnClickListener {
            selectedEmployee = null
            spEmployee.setSelection(0)
            sourceTierFilter = null
            statusFilter = null
            directionFilter = null
            searchQuery = ""
            etSearch.text?.clear()
            chipGroupMode.check(R.id.chipModeAll)
            chipGroupStatus.check(R.id.chipStatusAll)
            chipGroupDirection.check(R.id.chipDirectionAll)
            applyQuickDateRange(R.id.chipQuickOctMatrix)
        }
    }

    private fun resetAllFilters() {
        applyQuickDateRange(R.id.chipQuickThisMonth)
        selectedEmployee = null
        spEmployee.setSelection(0)
        sourceTierFilter = null
        statusFilter = null
        directionFilter = null
        searchQuery = ""
        etSearch.text?.clear()
        chipGroupMode.check(R.id.chipModeAll)
        chipGroupStatus.check(R.id.chipStatusAll)
        chipGroupDirection.check(R.id.chipDirectionAll)
        chipGroupQuick.clearCheck()
    }

    // ------- Data Loading -------
    private fun observeData() {
        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { empList ->
                employees = empList
                sortedEmployees = empList.sortedBy { it.name }
                setupEmployeeSpinner(sortedEmployees)
                reloadData()
            }
        }
        lifecycleScope.launch {
            sharedViewModel.allAttendancePunches.collectLatest { _ -> reloadData() }
        }
    }

    private fun setupEmployeeSpinner(sortedList: List<Employee>) {
        val items = mutableListOf("👤 All Staff (${sortedList.size})")
        items += sortedList.map { "👤 ${it.name} (#${it.employeeId})" }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spEmployee.adapter = spinnerAdapter
        spEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedEmployee = null
                reloadData()
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedEmployee = if (position == 0) null else sortedList.getOrNull(position - 1)
                reloadData()
            }
        }
    }

    // Flexible employee matching (employeeId, numeric ID, biometricId)
    private fun findEmployeeForStaffId(staffId: String): Employee? {
        val s = staffId.trim()
        if (s.isEmpty()) return null
        val sInt = s.toIntOrNull()
        return employees.firstOrNull { emp ->
            val empId = emp.employeeId.trim()
            val empIdInt = empId.toIntOrNull()
            val bioId = emp.biometricId.trim()
            val bioIdInt = bioId.toIntOrNull()

            s.equals(empId, ignoreCase = true) ||
            (sInt != null && empIdInt != null && sInt == empIdInt) ||
            (bioId.isNotEmpty() && s.equals(bioId, ignoreCase = true)) ||
            (sInt != null && bioIdInt != null && sInt == bioIdInt)
        }
    }

    private fun isPunchForEmployee(punchStaffId: String, emp: Employee?): Boolean {
        if (emp == null) return true
        val s = punchStaffId.trim()
        if (s.isEmpty()) return false
        val sInt = s.toIntOrNull()
        val empId = emp.employeeId.trim()
        val empIdInt = empId.toIntOrNull()
        val bioId = emp.biometricId.trim()
        val bioIdInt = bioId.toIntOrNull()

        return s.equals(empId, ignoreCase = true) ||
               (sInt != null && empIdInt != null && sInt == empIdInt) ||
               (bioId.isNotEmpty() && s.equals(bioId, ignoreCase = true)) ||
               (sInt != null && bioIdInt != null && sInt == bioIdInt)
    }

    private fun reloadData() {
        progressBar.isVisible = true
        swipeRefresh.isRefreshing = false

        lifecycleScope.launch {
            val startStr = isoFmt.format(startCal.time)
            val endStr = isoFmt.format(endCal.time)
            val startMs = startCal.timeInMillis
            val endMs = endCal.timeInMillis

            val result = withContext(Dispatchers.Default) {
                val rawPunches = sharedViewModel.allAttendancePunches.value
                    .filter { p ->
                        val punchDate = if (p.date.isNotBlank()) p.date else (if (p.timestamp > 0) isoFmt.format(Date(p.timestamp)) else "")
                        val dateOk = (punchDate.isNotBlank() && punchDate in startStr..endStr) ||
                            (p.timestamp in startMs..endMs)
                        val empOk = selectedEmployee == null || isPunchForEmployee(p.staffId, selectedEmployee)
                        dateOk && empOk
                    }
                evaluateAllPunches(rawPunches)
            }

            allEvaluated = result
            progressBar.isVisible = false
            applyFilter()
        }
    }

    private fun evaluateAllPunches(rawPunches: List<AttendancePunch>): List<EvaluatedPunchRecord> {
        val result = mutableListOf<EvaluatedPunchRecord>()

        // Group by (staffId, date)
        val grouped = rawPunches
            .groupBy { Pair(it.staffId, it.date.ifBlank { isoFmt.format(Date(it.timestamp)) }) }

        for ((key, dayPunches) in grouped) {
            val (staffId, dateStr) = key
            val emp = findEmployeeForStaffId(staffId)
            val empName = emp?.name ?: "Employee #$staffId"
            val role = emp?.role

            // Build ProcessedPunchItems passing punchId as biometricId and original source
            val punchItems = dayPunches.map { p ->
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

            // Run deterministic processor
            val processResult = AttendancePunchProcessor.processPunches(punchItems)
            val acceptedList = processResult.ordered

            val sortedDay = dayPunches.sortedBy { it.timestamp }

            sortedDay.forEachIndexed { idx, raw ->
                // Pass punchId and source for 100% accurate tier identification
                val tier = AttendancePunchProcessor.getPunchTier(raw.deviceId, raw.punchId, raw.type, raw.source)

                // Check accepted
                val isRawOut = AttendancePunchProcessor.isExplicitOutPunch(raw.type)
                val acceptedIndex = acceptedList.indexOfFirst { acc ->
                    acc.id == raw.punchId ||
                        (acc.timestamp == raw.timestamp && acc.staffId == raw.staffId &&
                         AttendancePunchProcessor.isExplicitOutPunch(acc.type) == isRawOut)
                }
                val isAccepted = acceptedIndex >= 0

                // Compute reason
                val (reason, category) = computeReason(
                    raw, isAccepted, acceptedIndex, acceptedList.size,
                    sortedDay, punchItems, tier
                )

                val punchDate = if (dateStr.isNotBlank()) dateStr
                    else isoFmt.format(Date(raw.timestamp))
                val timeStr = timeFmt.format(Date(raw.timestamp))
                val isIn = AttendancePunchProcessor.isExplicitInPunch(raw.type)
                val isOut = AttendancePunchProcessor.isExplicitOutPunch(raw.type)

                result.add(
                    EvaluatedPunchRecord(
                        punchId = raw.punchId,
                        staffId = raw.staffId,
                        employeeName = empName,
                        role = role,
                        timestamp = raw.timestamp,
                        dateStr = punchDate,
                        timeStr = timeStr,
                        rawDirection = raw.type,
                        isIn = isIn,
                        isOut = isOut,
                        sourceTier = tier,
                        deviceId = raw.deviceId,
                        biometricId = raw.punchId,
                        latitude = raw.latitude,
                        longitude = raw.longitude,
                        accuracy = raw.accuracy,
                        distanceFromGeofence = raw.distanceFromGeofence,
                        isAccepted = isAccepted,
                        evaluationStatus = if (isAccepted) (if (acceptedIndex % 2 == 0) "ACCEPTED_IN" else "ACCEPTED_OUT") else "SUPPRESSED",
                        reason = reason,
                        ruleCategory = category,
                        sequenceOrder = idx + 1
                    )
                )
            }
        }

        return result.sortedWith(compareBy({ it.timestamp }, { it.staffId }))
    }

    private fun computeReason(
        raw: AttendancePunch,
        isAccepted: Boolean,
        acceptedIndex: Int,
        acceptedCount: Int,
        dayPunches: List<AttendancePunch>,
        punchItems: List<ProcessedPunchItem>,
        tier: PunchSourceTier
    ): Pair<String, String> {
        if (isAccepted) {
            return if (acceptedIndex % 2 == 0) {
                val reason = if (acceptedIndex == 0) "✅ Accepted: Primary shift clock-in" else "✅ Accepted: Resumed work after break"
                reason to "Valid Check-IN"
            } else {
                val reason = if (acceptedIndex == acceptedCount - 1) "✅ Accepted: Final shift clock-out" else "✅ Accepted: Departure for break / transit"
                reason to "Valid Check-OUT"
            }
        }

        // Same-minute priority collision
        val collision = dayPunches.firstOrNull { o ->
            o.punchId != raw.punchId &&
                Math.abs(o.timestamp - raw.timestamp) < 60_000L &&
                AttendancePunchProcessor.getPunchTier(o.deviceId, o.punchId, o.type, o.source).priority < tier.priority
        }
        if (collision != null) {
            val colliderTier = AttendancePunchProcessor.getPunchTier(collision.deviceId, collision.punchId, collision.type, collision.source)
            return "🚫 Suppressed: Collided at ${timeFmt.format(Date(raw.timestamp))} with higher priority $colliderTier (Physical > Manual > Geofence)" to "Priority Collision"
        }

        // Manual admin override window
        val mIns = punchItems.filter { AttendancePunchProcessor.getPunchTier(it.deviceId, it.biometricId, it.type, it.source) == PunchSourceTier.ManualAdmin && AttendancePunchProcessor.isExplicitInPunch(it.type) }.sortedBy { it.timestamp }
        val mOuts = punchItems.filter { AttendancePunchProcessor.getPunchTier(it.deviceId, it.biometricId, it.type, it.source) == PunchSourceTier.ManualAdmin && AttendancePunchProcessor.isExplicitOutPunch(it.type) }.sortedBy { it.timestamp }
        if (mIns.isNotEmpty() && mOuts.isNotEmpty() && raw.timestamp > mIns.first().timestamp && raw.timestamp < mOuts.last().timestamp) {
            val mStart = timeFmt.format(Date(mIns.first().timestamp))
            val mEnd = timeFmt.format(Date(mOuts.last().timestamp))
            return "🚫 Suppressed: Covered by authoritative Manual Correction window ($mStart–$mEnd); intermediate punch ignored" to "Manual Shift Override"
        }

        if (tier == PunchSourceTier.GeofenceAuto) {
            val rapidFlip = dayPunches.firstOrNull { o ->
                o.punchId != raw.punchId &&
                    kotlin.math.abs(o.timestamp - raw.timestamp) < 180_000L &&
                    AttendancePunchProcessor.getPunchTier(o.deviceId, o.punchId, o.type, o.source) == PunchSourceTier.GeofenceAuto &&
                    AttendancePunchProcessor.isExplicitOutPunch(o.type) != AttendancePunchProcessor.isExplicitOutPunch(raw.type)
            }
            if (rapidFlip != null) {
                val priorTime = timeFmt.format(Date(rapidFlip.timestamp))
                return "🚫 Suppressed: GPS perimeter jitter / rapid flip within 3m of $priorTime ${rapidFlip.type}" to "GPS Perimeter Jitter"
            }

            val authIn = punchItems.firstOrNull { AttendancePunchProcessor.getPunchTier(it.deviceId, it.biometricId, it.type, it.source).priority <= PunchSourceTier.ManualAdmin.priority && AttendancePunchProcessor.isExplicitInPunch(it.type) }
            val authOut = punchItems.lastOrNull { AttendancePunchProcessor.getPunchTier(it.deviceId, it.biometricId, it.type, it.source).priority <= PunchSourceTier.ManualAdmin.priority && AttendancePunchProcessor.isExplicitOutPunch(it.type) }
            if (authIn != null && authOut != null && raw.timestamp > authIn.timestamp && raw.timestamp < authOut.timestamp) {
                return "🚫 Suppressed: GPS geofence drift suppressed during active authoritative shift" to "GPS Drift Suppression"
            }
            if (AttendancePunchProcessor.isExplicitOutPunch(raw.type)) {
                return "🚫 Suppressed: Redundant geofence OUT — employee already clocked out" to "Redundant Exit"
            }
            return "🚫 Suppressed: Redundant consecutive geofence IN — employee already clocked in" to "Consecutive Entry"
        }

        if (AttendancePunchProcessor.isExplicitInPunch(raw.type)) {
            return "🚫 Suppressed: Redundant consecutive IN punch — prior clock-in maintained" to "Consecutive Entry"
        }
        if (AttendancePunchProcessor.isExplicitOutPunch(raw.type)) {
            return "🚫 Suppressed: Redundant OUT punch — employee already clocked out" to "Redundant Exit"
        }
        return "🚫 Suppressed: Discarded by alternating state machine (IN ↔ OUT)" to "Sequence Discard"
    }

    // ------- Filter / Display -------
    private fun applyFilter() {
        var filtered = allEvaluated.asSequence()

        if (selectedEmployee != null) {
            filtered = filtered.filter { isPunchForEmployee(it.staffId, selectedEmployee) }
        }
        if (sourceTierFilter != null) {
            filtered = filtered.filter { it.sourceTier == sourceTierFilter }
        }
        if (statusFilter != null) {
            filtered = filtered.filter { it.isAccepted == statusFilter }
        }
        if (directionFilter != null) {
            filtered = when (directionFilter) {
                "IN" -> filtered.filter { it.isIn }
                "OUT" -> filtered.filter { it.isOut }
                else -> filtered
            }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            filtered = filtered.filter { p ->
                p.employeeName.lowercase().contains(q) ||
                    p.staffId.lowercase().contains(q) ||
                    p.deviceId.lowercase().contains(q) ||
                    p.biometricId.lowercase().contains(q) ||
                    p.reason.lowercase().contains(q) ||
                    p.ruleCategory.lowercase().contains(q) ||
                    p.timeStr.lowercase().contains(q) ||
                    p.dateStr.lowercase().contains(q) ||
                    p.evaluationStatus.lowercase().contains(q)
            }
        }

        val list = filtered.sortedWith(compareBy({ it.timestamp }, { it.employeeName })).toList()
        adapter.submitList(list)

        val total = list.size
        val accepted = list.count { it.isAccepted }
        val suppressed = total - accepted
        val physical = list.count { it.sourceTier == PunchSourceTier.PhysicalMachine }
        val manual = list.count { it.sourceTier == PunchSourceTier.ManualAdmin }
        val geo = list.count { it.sourceTier == PunchSourceTier.GeofenceAuto }

        tvKpiTotal.text = total.toString()
        tvKpiAccepted.text = accepted.toString()
        tvKpiSuppressed.text = suppressed.toString()
        tvKpiPhysical.text = physical.toString()
        tvKpiManual.text = manual.toString()
        tvKpiGeo.text = geo.toString()
        tvKpiAcceptedPct.text = if (total > 0) "${(accepted * 100.0 / total).toInt()}% acceptance" else "0%"
        tvKpiSuppressedPct.text = if (total > 0) "${(suppressed * 100.0 / total).toInt()}% suppressed" else "0%"

        tvResultsCount.text = "Telemetry Records ($total) • $accepted Accepted, $suppressed Suppressed"

        tvEmpty.isVisible = list.isEmpty()
        rvPunches.isVisible = list.isNotEmpty()
        btnExportCsv.isEnabled = list.isNotEmpty()
    }

    // ------- Bottom Sheet Inspection -------
    private fun showInspectionBottomSheet(punch: EvaluatedPunchRecord) {
        val sheet = BottomSheetDialog(this, R.style.PremiumBottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_punch_inspection, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.tvInspectEmployee).text = "👤 ${punch.employeeName}"
        view.findViewById<TextView>(R.id.tvInspectRole).text = punch.role ?: "Staff"
        view.findViewById<TextView>(R.id.tvInspectDate).text = punch.dateStr
        view.findViewById<TextView>(R.id.tvInspectTime).text = "⏱ ${punch.timeStr}"
        view.findViewById<TextView>(R.id.tvInspectDirection).text = when {
            punch.isIn -> "📥 Check-IN"
            punch.isOut -> "📤 Check-OUT"
            else -> "🔄 ${punch.rawDirection}"
        }
        val tierText = when (punch.sourceTier) {
            PunchSourceTier.PhysicalMachine -> "📟 Physical Machine (Tier 1)"
            PunchSourceTier.ManualAdmin -> "✍️ Manual Correction (Tier 2)"
            PunchSourceTier.GeofenceAuto -> "📍 Android Auto-Geofence (Tier 3)"
        }
        view.findViewById<TextView>(R.id.tvInspectTier).text = tierText
        view.findViewById<TextView>(R.id.tvInspectResult).apply {
            text = if (punch.isAccepted) "✅ VALID" else "🚫 SUPPRESSED"
            setTextColor(
                if (punch.isAccepted) getColor(android.R.color.holo_green_dark)
                else getColor(android.R.color.holo_red_dark)
            )
        }
        view.findViewById<TextView>(R.id.tvInspectReason).text = punch.reason
        view.findViewById<TextView>(R.id.tvInspectCategory).text = punch.ruleCategory
        view.findViewById<TextView>(R.id.tvInspectDeviceId).text = punch.deviceId.ifBlank { "—" }
        view.findViewById<TextView>(R.id.tvInspectBioId).text = punch.biometricId.ifBlank { "—" }
        view.findViewById<TextView>(R.id.tvInspectSequence).text = "#${punch.sequenceOrder}"

        val coordStr = if (punch.latitude != 0.0 || punch.longitude != 0.0)
            "📌 ${punch.latitude}°, ${punch.longitude}°  (acc: ${punch.accuracy.toInt()}m)"
        else "—"
        view.findViewById<TextView>(R.id.tvInspectCoords).text = coordStr
        view.findViewById<TextView>(R.id.tvInspectDistance).text =
            if (punch.distanceFromGeofence > 0) "${punch.distanceFromGeofence.toInt()}m from geofence" else "—"

        view.findViewById<Button>(R.id.btnInspectClose).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    // ------- CSV Export -------
    private fun exportCsv() {
        val filtered = adapter.currentList
        if (filtered.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.appendLine("PunchID,Date,Time,EmployeeID,EmployeeName,Direction,SourceTier,DeviceID,BiometricID,IsAccepted,Status,RuleCategory,Reason,Latitude,Longitude,Accuracy")
            filtered.forEach { p ->
                val dir = when { p.isIn -> "IN"; p.isOut -> "OUT"; else -> p.rawDirection }
                sb.appendLine(listOf(
                    p.punchId, p.dateStr, p.timeStr, p.staffId, "\"${p.employeeName}\"",
                    dir, p.sourceTier.name, "\"${p.deviceId}\"", "\"${p.biometricId}\"",
                    p.isAccepted, p.evaluationStatus, "\"${p.ruleCategory}\"", "\"${p.reason}\"",
                    p.latitude, p.longitude, p.accuracy
                ).joinToString(","))
            }

            val startStr = isoFmt.format(startCal.time)
            val endStr = isoFmt.format(endCal.time)
            val file = File(cacheDir, "PunchAudit_${startStr}_$endStr.csv")
            file.writeText(sb.toString())

            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(this@RawPunchInspectorActivity, "${packageName}.provider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Punch Audit Export $startStr to $endStr")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Punch Audit CSV"))
                Toast.makeText(this@RawPunchInspectorActivity, "📊 CSV ready – ${filtered.size} records", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
