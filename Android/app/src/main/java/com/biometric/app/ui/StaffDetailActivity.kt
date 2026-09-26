package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.EmployeeHistory
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.EmployeeStats
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.data.entity.SalaryRules
import com.biometric.app.databinding.ActivityStaffDetailBinding
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.DateRangeUtil
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.PickerHelper
import com.biometric.app.util.PremiumLoader
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class StaffDetailActivity : MotionBaseActivity() {

    private var _binding: ActivityStaffDetailBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: StaffViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var repository: MainRepository

    private lateinit var attendanceLogAdapter: AttendanceLogAdapter
    private var employeeId: String = ""
    private var allShopEmployees: List<Employee> = emptyList()
    private val calFrom = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val calTo = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }
    private val filterRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var shopId: String = ""
    private var filteredAttendance: List<Attendance> = emptyList()
    private var allLogItems: List<ActivityLogItem> = emptyList()
    private var currentLogFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityStaffDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clStaffDetailRoot, binding.appBar)

        binding.nsvData.visibility = View.INVISIBLE

        employeeId = intent.getStringExtra("EMPLOYEE_ID") ?: ""
        shopId = intent.getStringExtra("SHOP_ID") ?: ""
        viewModel.setShop(shopId)

        setupToolbar()
        setupUI()
        setupRecyclerViews()
        observeViewModel()
        startLiveStatusListener()
        observeAttendanceLog()

        setupMotionFeedback(
            binding.btnDateFrom,
            binding.btnDateTo,
            binding.cardLate,
            binding.cardEarly,
            binding.cardGap,
            binding.cardLeave,
            binding.cardBonus,
            binding.cardPaidLeave,
            binding.cardAllowance,
            binding.cardOT,
            binding.cardAdvance,
            binding.tilStaffSelection,
            binding.btnPrevStaff,
            binding.btnNextStaff,
        )
        refreshData()
    }

    private var liveStatusRef: DatabaseReference? = null
    private var liveStatusListener: ValueEventListener? = null

    private fun startLiveStatusListener() {
        val blink = AlphaAnimation(1.0f, 0.4f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (employeeId.isBlank()) return

        val ref = FirebaseDatabase.getInstance().reference
            .child("owners")
            .child(uid)
            .child("realtime_tracking")
            .child(employeeId)

        liveStatusListener?.let { liveStatusRef?.removeEventListener(it) }
        liveStatusRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                val isOnline = snapshot.exists()
                if (isOnline) {
                    binding.actvStaffName.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_trending_up, 0)
                    binding.actvStaffName.compoundDrawables[2]?.setTint(ContextCompat.getColor(this@StaffDetailActivity, R.color.green))
                    binding.actvStaffName.startAnimation(blink)
                } else {
                    binding.actvStaffName.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                    binding.actvStaffName.clearAnimation()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        liveStatusListener = listener
        ref.addValueEventListener(listener)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val intentEmployeeName = intent.getStringExtra("EMPLOYEE_NAME")
        if (!intentEmployeeName.isNullOrBlank()) {
            updateToolbarTitle("$intentEmployeeName 👤", "Employee Insights 📊")
        } else {
            updateToolbarTitle("Employee Insights 📊", "Staff Performance & Payroll 🛡️")
        }
    }

    private fun updateToolbarTitle(title: String, subtitle: String = "Employee Insights 📊") {
        setupDualHeader(binding.toolbar, title, subtitle)
    }

    private fun setupUI() {
        binding.rvStaffHistory.layoutManager = LinearLayoutManager(this)

        updateDateButtons()

        binding.btnDateFrom.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calFrom.set(Calendar.YEAR, year)
                    calFrom.set(Calendar.MONTH, month)
                    calFrom.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    calFrom.set(Calendar.HOUR_OF_DAY, 0)
                    calFrom.set(Calendar.MINUTE, 0)
                    calFrom.set(Calendar.SECOND, 0)
                    calFrom.set(Calendar.MILLISECOND, 0)

                    if (calFrom.timeInMillis > calTo.timeInMillis) {
                        calTo.timeInMillis = calFrom.timeInMillis
                        calTo.set(Calendar.HOUR_OF_DAY, 23)
                        calTo.set(Calendar.MINUTE, 59)
                        calTo.set(Calendar.SECOND, 59)
                        calTo.set(Calendar.MILLISECOND, 999)
                    }
                    updateDateButtons()
                    refreshData()
                },
                calFrom.get(Calendar.YEAR),
                calFrom.get(Calendar.MONTH),
                calFrom.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        binding.btnDateTo.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calTo.set(Calendar.YEAR, year)
                    calTo.set(Calendar.MONTH, month)
                    calTo.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    calTo.set(Calendar.HOUR_OF_DAY, 23)
                    calTo.set(Calendar.MINUTE, 59)
                    calTo.set(Calendar.SECOND, 59)
                    calTo.set(Calendar.MILLISECOND, 999)

                    if (calTo.timeInMillis < calFrom.timeInMillis) {
                        calFrom.timeInMillis = calTo.timeInMillis
                        calFrom.set(Calendar.HOUR_OF_DAY, 0)
                        calFrom.set(Calendar.MINUTE, 0)
                        calFrom.set(Calendar.SECOND, 0)
                        calFrom.set(Calendar.MILLISECOND, 0)
                    }
                    updateDateButtons()
                    refreshData()
                },
                calTo.get(Calendar.YEAR),
                calTo.get(Calendar.MONTH),
                calTo.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        binding.spEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in allShopEmployees.indices) {
                    val selected = allShopEmployees[position]
                    if (selected.employeeId != employeeId) {
                        employeeId = selected.employeeId
                        updateProfileHeader(selected)
                        binding.actvStaffName.setText(selected.name, false)
                        refreshSelectedEmployeeHistory()
                        refreshData()
                        HapticUtil.vibrateClick(binding.spEmployee)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.cardLate.setOnClickListener { toggleLogFilter("LATE") }
        binding.cardEarly.setOnClickListener { toggleLogFilter("EARLY") }
        binding.cardLeave.setOnClickListener { toggleLogFilter("LEAVE") }
        binding.cardGap.setOnClickListener { toggleLogFilter("GAP") }
        binding.cardBonus.setOnClickListener { toggleLogFilter("BONUS") }
        binding.cardPaidLeave.setOnClickListener { toggleLogFilter("PAID_LEAVE") }
        binding.cardAllowance.setOnClickListener { toggleLogFilter("ALLOWANCE") }
        binding.cardAdvance.setOnClickListener { toggleLogFilter("ADVANCE") }
        binding.cardOT.setOnClickListener { toggleLogFilter("OT") }

        binding.btnPrevStaff.setOnClickListener { navigateStaff(-1) }
        binding.btnNextStaff.setOnClickListener { navigateStaff(1) }
    }

    private fun updateDateButtons() {
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        binding.btnDateFrom.text = df.format(calFrom.time)
        binding.btnDateTo.text = df.format(calTo.time)
    }

    private fun navigateStaff(direction: Int) {
        if (allShopEmployees.isEmpty()) return
        val currentIndex = allShopEmployees.indexOfFirst { it.employeeId == employeeId }
        var nextIndex = currentIndex + direction

        if (nextIndex < 0) nextIndex = allShopEmployees.size - 1
        if (nextIndex >= allShopEmployees.size) nextIndex = 0

        val nextEmp = allShopEmployees[nextIndex]
        employeeId = nextEmp.employeeId

        binding.spEmployee.setSelection(nextIndex)
        updateProfileHeader(nextEmp)
        binding.actvStaffName.setText(nextEmp.name, false)

        refreshSelectedEmployeeHistory()
        refreshData()

        HapticUtil.vibrateClick(if (direction > 0) binding.btnNextStaff else binding.btnPrevStaff)
    }

    private fun refreshData() {
        val startTs = getMidnight(calFrom.timeInMillis)
        val endTs = getEndOfDay(calTo.timeInMillis)

        lifecycleScope.launch {
            if (filteredAttendance.isEmpty()) {
                PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.STAFF, lifecycleScope, immediate = true)
                binding.nsvData.visibility = View.INVISIBLE
            }

            if (employeeId.isEmpty()) {
                if (allShopEmployees.isNotEmpty()) {
                    val nextEmp = allShopEmployees.find { it.isActive } ?: allShopEmployees.first()
                    employeeId = nextEmp.employeeId
                    updateProfileHeader(nextEmp)
                    binding.actvStaffName.setText(nextEmp.name, false)
                    val idx = allShopEmployees.indexOfFirst { it.employeeId == employeeId }
                    if (idx >= 0) binding.spEmployee.setSelection(idx)
                    refreshSelectedEmployeeHistory()
                } else {
                    PremiumLoader.hide(binding.brewingLoader)
                    binding.nsvData.visibility = View.GONE
                    binding.tvNotEmployedMessage.visibility = View.VISIBLE
                    binding.tvNotEmployedMessage.setText(R.string.label_no_staff_in_range)
                    return@launch
                }
            }

            val employee = repository.getEmployee(employeeId).first()
            if (employee == null) {
                PremiumLoader.hide(binding.brewingLoader)
                binding.nsvData.visibility = View.GONE
                binding.tvNotEmployedMessage.visibility = View.VISIBLE
                binding.tvNotEmployedMessage.text = "🔍 Staff profile missing"
                return@launch
            }

            val hireTs = getMidnight(employee.hireDate)
            val termTs = employee.terminateDate?.let { getMidnight(it) } ?: Long.MAX_VALUE
            val isEmployedInPeriod = (hireTs <= endTs) && (termTs >= startTs)

            if (isEmployedInPeriod) {
                binding.tvNotEmployedMessage.visibility = View.GONE
                updateMetricsForRange(startTs, endTs)
            } else {
                updateHistoryList(emptyList())
                PremiumLoader.hide(binding.brewingLoader)
                binding.nsvData.visibility = View.GONE
                binding.tvNotEmployedMessage.visibility = View.VISIBLE
                binding.tvNotEmployedMessage.text = "⚠️ Staff not employed during selected date range"
            }
            filterRefreshTrigger.value = System.currentTimeMillis()
        }
    }

    private fun updateStaffSpinner() {
        if (allShopEmployees.isEmpty()) {
            binding.spEmployee.adapter = null
            return
        }

        val options = allShopEmployees.map { "${it.name} (#${it.employeeId})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spEmployee.adapter = adapter

        val currentIndex = allShopEmployees.indexOfFirst { it.employeeId == employeeId }
        if (currentIndex >= 0) {
            binding.spEmployee.setSelection(currentIndex)
        }
    }

    private fun updateStaffDropdown() {
        if (allShopEmployees.isEmpty()) {
            binding.actvStaffName.setAdapter(null)
            binding.actvStaffName.setText("", false)
            return
        }

        binding.actvStaffName.textSize = 14f
        val names = allShopEmployees.map { it.name }
        val adapter = ArrayAdapter(this, R.layout.item_simple_dropdown, names)
        binding.actvStaffName.setAdapter(adapter)

        val currentEmp = allShopEmployees.find { it.employeeId == employeeId }
        currentEmp?.let { binding.actvStaffName.setText(it.name, false) }

        binding.actvStaffName.setOnItemClickListener { _, _, position, _ ->
            val selected = allShopEmployees[position]
            employeeId = selected.employeeId
            updateProfileHeader(selected)
            val idx = allShopEmployees.indexOfFirst { it.employeeId == employeeId }
            if (idx >= 0) binding.spEmployee.setSelection(idx)
            refreshSelectedEmployeeHistory()
            refreshData()
            HapticUtil.vibrateClick(binding.actvStaffName)
        }
    }

    private fun getMidnight(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun getEndOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private fun toggleLogFilter(type: String) {
        currentLogFilter = if (currentLogFilter == type) null else type
        updateLogRecyclerView()
        val message = if (currentLogFilter == null) "Showing all activity" else "Filtering by $type"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupRecyclerViews() {
        binding.rvStaffHistory.layoutManager = LinearLayoutManager(this)
        binding.rvActivityLog.layoutManager = LinearLayoutManager(this)
        
        attendanceLogAdapter = AttendanceLogAdapter()
        binding.rvAttendanceLog.layoutManager = LinearLayoutManager(this)
        binding.rvAttendanceLog.adapter = attendanceLogAdapter
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, activity = this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) {
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(sharedViewModel.selectedShop, repository.allEmployeesFlow) { shop, rawList ->
                        shop to rawList
                    }.collectLatest { (shop, rawList) ->
                        shop?.let {
                            updateToolbarTitle(it.name)
                            if (shopId != it.shopId) {
                                shopId = it.shopId

                                // A staff selection belongs to the previous shop.
                                // Clear both the selected employee and the visible
                                // history before the new shop data is loaded.
                                employeeId = ""
                                updateHistoryList(emptyList())

                                viewModel.setShop(it.shopId)
                            }
                            allShopEmployees = rawList.filter { emp ->
                                val isDeleted = !emp.isActive && emp.terminateDate == null
                                !isDeleted && (emp.shopId == it.shopId)
                            }.sortedBy { it.name }

                            // AUTO-SELECT first employee if none selected
                            if (employeeId.isEmpty() && allShopEmployees.isNotEmpty()) {
                                val firstEmp = allShopEmployees.find { emp -> emp.isActive } ?: allShopEmployees.first()
                                employeeId = firstEmp.employeeId
                                updateProfileHeader(firstEmp)
                                refreshSelectedEmployeeHistory()
                            }
                            updateStaffSpinner()
                            updateStaffDropdown()
                            refreshData()
                        }
                    }
                }
                launch {
                    viewModel.employeeHistory.collectLatest {
                        // The flow can emit because another employee/shop changed.
                        // Always resolve the history using the CURRENT employeeId.
                        refreshSelectedEmployeeHistory()
                    }
                }
            }
        }
    }

    /**
     * Refresh the employment-history section for the currently selected
     * employee only. Employee history is grouped by employeeId in the
     * ViewModel, so the current employeeId must always be used as the key.
     *
     * This is intentionally called when:
     * - Previous/Next staff is pressed
     * - A staff member is selected from the dropdown
     * - The shop changes
     * - Employee-history data emits from Firebase
     * - The first employee is auto-selected
     */
    private fun refreshSelectedEmployeeHistory() {
        val selectedEmployeeId = employeeId

        if (selectedEmployeeId.isBlank()) {
            updateHistoryList(emptyList())
            return
        }

        val history = viewModel.employeeHistory.value[ selectedEmployeeId ]
            .orEmpty()
            .sortedByDescending { it.effectiveDate }

        updateHistoryList(history)
    }

    private fun updateProfileHeader(emp: Employee) {
        binding.actvStaffName.setText(emp.name, false)
        val empTitle = if (emp.name.isNotBlank()) "${emp.name} 👤" else "Employee #${emp.employeeId} 👤"
        val subtitle = if (emp.role.isNotBlank()) "${emp.role} • #${emp.employeeId} • Insights 📊" else "Employee Insights • #${emp.employeeId} 📊"
        updateToolbarTitle(empTitle, subtitle)
    }

    private fun updateMetricsForRange(startTs: Long, endTs: Long) {
        lifecycleScope.launch {
            val employee = repository.getEmployee(employeeId).first() ?: return@launch
            
            val cal = Calendar.getInstance().apply { timeInMillis = startTs }
            val allMonthStats = mutableListOf<EmployeeStats>()
            
            while (cal.timeInMillis <= endTs) {
                val monthStart = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                val monthEnd = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
                
                val intersectStart = maxOf(startTs, monthStart)
                val intersectEnd = minOf(endTs, monthEnd)
                
                val hireTs = getMidnight(employee.hireDate)
                val termTs = employee.terminateDate?.let { getMidnight(it) } ?: Long.MAX_VALUE
                val effectiveStart = maxOf(intersectStart, hireTs)
                val effectiveEnd = minOf(intersectEnd, termTs)
                
                if (effectiveStart <= effectiveEnd) {
                    val monthStats = repository.getEmployeeStatsFlow(employee, monthStart, monthEnd, effectiveStart, effectiveEnd).first()
                    allMonthStats.add(monthStats)
                }
                cal.timeInMillis = monthEnd
                cal.add(Calendar.MILLISECOND, 1)
            }

            if (allMonthStats.isEmpty()) {
                PremiumLoader.hide(binding.brewingLoader); binding.nsvData.visibility = View.GONE; binding.tvNotEmployedMessage.visibility = View.VISIBLE; binding.tvNotEmployedMessage.text = "⚠️ No work history for this period"; return@launch
            }

            // UNIFIED CALCULATION: Components summation for [startTs, endTs]
            var tWorkedSalary = java.math.BigDecimal.ZERO
            var tOTSalary = java.math.BigDecimal.ZERO
            var tPLSalary = java.math.BigDecimal.ZERO
            var tAllowance = java.math.BigDecimal.ZERO
            var tBonus = java.math.BigDecimal.ZERO
            
            var tLateCount = 0; var tEarlyCount = 0; var tGapCount = 0; var tLeaveCount = 0
            var tOTCount = 0; var tBonusCount = 0; var tPLCount = 0; var tAllowanceCount = 0; var tAdvanceCount = 0
            
            var tLateHrs = 0.0; var tEarlyHrs = 0.0; var tGapHrs = 0.0; var tLeaveHrs = 0.0
            var tPLUsedHrs = 0.0
            
            var tOTHrs = 0.0
            var totalDeductible = java.math.BigDecimal.ZERO
            
            var tLateAmt = java.math.BigDecimal.ZERO; var tEarlyAmt = java.math.BigDecimal.ZERO
            var tGapAmt = java.math.BigDecimal.ZERO; var tLeaveAmt = java.math.BigDecimal.ZERO

            val locale = Locale.getDefault()

            allMonthStats.forEach { stats ->
                val inRange: (Long) -> Boolean = { it in startTs..endTs }
                
                tWorkedSalary = tWorkedSalary.add(stats.dayWiseEarnings.filterKeys(inRange).values.fold(java.math.BigDecimal.ZERO) { acc, b -> acc.add(b) })
                tOTSalary = tOTSalary.add(stats.dayWiseOTEarnings.filterKeys(inRange).values.fold(java.math.BigDecimal.ZERO) { acc, b -> acc.add(b) })
                tPLSalary = tPLSalary.add(stats.dayWisePaidLeave.filterKeys(inRange).values.fold(java.math.BigDecimal.ZERO) { acc, b -> acc.add(b) })
                tAllowance = tAllowance.add(stats.dayWiseAllowances.filterKeys(inRange).values.fold(java.math.BigDecimal.ZERO) { acc, b -> acc.add(b) })
                tBonus = tBonus.add(stats.dayWiseBonus.filterKeys(inRange).values.fold(java.math.BigDecimal.ZERO) { acc, b -> acc.add(b) })
                
                tOTHrs += stats.dayWiseOTHours.filterKeys(inRange).values.sum()
                tPLUsedHrs += stats.dayWisePaidLeaveHours.filterKeys(inRange).values.sum()

                // Deductible amount = Sum of gross deductions
                tLateAmt = tLateAmt.add(stats.totalLateAmount)
                tEarlyAmt = tEarlyAmt.add(stats.totalEarlyAmount)
                tGapAmt = tGapAmt.add(stats.totalGapAmount)
                tLeaveAmt = tLeaveAmt.add(stats.totalLeaveAmount)
                
                totalDeductible = totalDeductible.add(stats.totalLateAmount).add(stats.totalEarlyAmount).add(stats.totalGapAmount).add(stats.totalLeaveAmount)

                // Counts & Hours (Centralized Engine outputs)
                tLateCount += stats.lateCount
                tEarlyCount += stats.earlyCount
                tGapCount += stats.gapCount
                tLeaveCount += stats.leaveCount
                
                tOTCount += stats.otCount
                tBonusCount += stats.bonusCount
                tPLCount += stats.paidLeaveCount
                tAllowanceCount += stats.allowanceCount
                
                tLateHrs += stats.totalLateHours
                tEarlyHrs += stats.totalEarlyHours
                tGapHrs += stats.totalGapHours
                tLeaveHrs += stats.totalLeaveHours
            }

            // Unified Net Total
            val netSalaryRounded = tWorkedSalary.add(tOTSalary).add(tBonus).add(tPLSalary).setScale(0, java.math.RoundingMode.HALF_UP)
            val finalNetTotal = netSalaryRounded.add(tAllowance).toDouble()

            // Summary Section
            val advances = repository.getAdvanceRecords(employeeId, startTs, endTs).first()
            val tAdvance = advances.sumOf { it.amount }
            tAdvanceCount = advances.size
            val tSalaryDue = finalNetTotal - tAllowance.toDouble() - tAdvance

            // Update Labels with Counts
            binding.lblLate.text = getString(R.string.label_late_format, tLateCount)
            binding.lblEarly.text = getString(R.string.label_early_format, tEarlyCount)
            binding.lblGap.text = getString(R.string.label_gap_format, tGapCount)
            binding.lblLeave.text = getString(R.string.label_leave_format, tLeaveCount)
            binding.lblOT.text = getString(R.string.label_ot_format, tOTCount)
            binding.lblBonus.text = getString(R.string.label_bonus_format, tBonusCount)
            binding.lblPaidLeave.text = getString(R.string.label_paid_lv_format, tPLCount)
            binding.lblAllowance.text = getString(R.string.label_allowance_format, tAllowanceCount)
            binding.lblAdvance.text = getString(R.string.label_advance_format, tAdvanceCount)

            // Card Visibilities
            binding.cardLate.visibility = if (tLateCount > 0) View.VISIBLE else View.GONE
            binding.tvLateCount.text = formatDeductionText(tLateHrs, tLateAmt)

            binding.cardEarly.visibility = if (tEarlyCount > 0) View.VISIBLE else View.GONE
            binding.tvEarlyLeaveCount.text = formatDeductionText(tEarlyHrs, tEarlyAmt)

            binding.cardGap.visibility = if (tGapCount > 0) View.VISIBLE else View.GONE
            binding.tvGapCount.text = formatDeductionText(tGapHrs, tGapAmt)

            binding.cardLeave.visibility = if (tLeaveCount > 0) View.VISIBLE else View.GONE
            // Leave Breakdown: Net Hours | Net Amount (PL + Unpaid)
            val unpaidLeaves = (tLeaveCount - tPLCount).coerceAtLeast(0)
            val nLeaveHrs = (tLeaveHrs - tPLUsedHrs).coerceAtLeast(0.0)
            val nLeaveAmt = allMonthStats.fold(java.math.BigDecimal.ZERO) { acc, stats -> acc.add(stats.netLeaveAmount) }
            binding.tvLeaveCount.text = getString(R.string.leave_breakdown_format, nLeaveHrs, nLeaveAmt.toDouble(), tPLCount, unpaidLeaves)

            val showRules = true
            binding.cardBonus.visibility = if (showRules && tBonus > java.math.BigDecimal.ZERO) View.VISIBLE else View.GONE
            binding.tvBonusRecv.text = String.format(locale, "₹%,.2f", tBonus.toDouble())
            
            binding.cardPaidLeave.visibility = if (showRules && (tPLSalary > java.math.BigDecimal.ZERO || tPLCount > 0)) View.VISIBLE else View.GONE
            
            // Paid Leave Balance Calculation
            val shop = sharedViewModel.selectedShop.value
            val rules = shop?.salaryRules ?: SalaryRules()
            val netShiftHrs = allMonthStats.lastOrNull()?.netShiftHours ?: 0.0
            val totalPLPoolHrs = rules.paidLeaveDaysPool.toDouble() * netShiftHrs * allMonthStats.size
            val plBalanceHrs = (totalPLPoolHrs - tPLUsedHrs).coerceAtLeast(0.0)
            binding.tvPaidLeaveUsed.text = if (plBalanceHrs > 0.01) {
                getString(R.string.paid_leave_balance_format, tPLUsedHrs, plBalanceHrs, tPLSalary.toDouble())
            } else {
                getString(R.string.paid_leave_used_format, tPLUsedHrs, tPLSalary.toDouble())
            }
            
            binding.cardOT.visibility = if (tOTHrs > 0.01) View.VISIBLE else View.GONE
            binding.tvOTDetails.text = String.format(locale, "%.1f h | ₹%,.2f", tOTHrs, tOTSalary.toDouble())
            
            binding.cardAllowance.visibility = if (tAllowance > java.math.BigDecimal.ZERO) View.VISIBLE else View.GONE
            binding.tvAllowanceRecv.text = String.format(locale, "₹%,.2f", tAllowance.toDouble())

            binding.cardAdvance.visibility = if (tAdvanceCount > 0) View.VISIBLE else View.GONE
            binding.tvAdvanceRecv.text = String.format(locale, "₹%,.2f", tAdvance)

            // Summary Section
            val displaySalaryRate = allMonthStats.lastOrNull()?.fullMonthSalary ?: employee.salaryRate
            val tCredits = tOTSalary.add(tAllowance).add(tBonus).add(tPLSalary).toDouble()
            val tDebits = totalDeductible.toDouble()

            binding.tvBaseSalary.text = String.format(locale, "₹%,.2f", displaySalaryRate)
            binding.tvTotalDebits.text = String.format(locale, "₹%,.2f", tDebits)
            binding.tvOTTotal.text = String.format(locale, "₹%,.2f", tCredits)
            binding.tvNetTotal.text = String.format(locale, "₹%,.2f", finalNetTotal)
            binding.tvReceivedTotal.text = String.format(locale, "₹%,.2f", tAllowance.toDouble())
            binding.tvAdvanceReceived.text = String.format(locale, "₹%,.2f", tAdvance)
            binding.tvSalaryDue.text = String.format(locale, "₹%,.2f", tSalaryDue)

            // Dynamic Visibility: Hide Debits/Credits if they are effectively zero (< 0.10 to filter rounding noise)
            val showDebits = tDebits > 0.09
            val showCredits = tCredits > 0.09

            binding.lblTotalDebits.visibility = if (showDebits) View.VISIBLE else View.GONE
            binding.tvTotalDebits.visibility = if (showDebits) View.VISIBLE else View.GONE
            binding.lblTotalCredits.visibility = if (showCredits) View.VISIBLE else View.GONE
            binding.tvOTTotal.visibility = if (showCredits) View.VISIBLE else View.GONE

            // Dynamic Visibility: Hide Net Total and Received rows if they are 0.00
            val hasAllowance = tAllowance.toDouble() > 0.01
            val hasAdvance = tAdvance > 0.01

            binding.llReceivedAllowance.visibility = if (hasAllowance) View.VISIBLE else View.GONE
            binding.llReceivedAdvance.visibility = if (hasAdvance) View.VISIBLE else View.GONE

            if (!hasAllowance && !hasAdvance) {
                binding.llNetTotal.visibility = View.GONE
                binding.divReceived.visibility = View.GONE
                binding.divSalaryDue.visibility = View.GONE
            } else {
                binding.llNetTotal.visibility = View.VISIBLE
                binding.divReceived.visibility = View.VISIBLE
                binding.divSalaryDue.visibility = View.VISIBLE
            }

            updateActivityLog(allMonthStats, advances, startTs, endTs)
            
            PremiumLoader.hide(binding.brewingLoader); binding.nsvData.visibility = View.VISIBLE; binding.nsvData.alpha = 1f; animateContentEntry(binding.nsvData)
        }
    }

    private fun formatDeductionText(hours: Double, total: java.math.BigDecimal): String {
        return String.format(Locale.getDefault(), "%.1f h | ₹%,.2f", hours, total.toDouble())
    }

    private fun animateContentEntry(view: View) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(400).start()
    }

    private fun updateActivityLog(allMonthStats: List<EmployeeStats>, advances: List<AdvancePayment>, startTs: Long, endTs: Long) {
        val logItems = mutableListOf<ActivityLogItem>()

        allMonthStats.forEach { stats ->
            stats.activityLog.forEach { item ->
                // Filter items by range
                if (item.timestamp < startTs || item.timestamp > endTs) return@forEach
                
                logItems.add(ActivityLogItem(item.type, item.title, item.desc, item.timestamp))
            }
        }

        advances.forEach { adv ->
            logItems.add(ActivityLogItem("ADVANCE", "Advance Payment", "₹%,.2f taken".format(adv.amount), adv.date))
        }

        allLogItems = logItems.sortedByDescending { it.timestamp }
        updateLogRecyclerView()
    }

    private fun updateLogRecyclerView() {
        val filtered = if (currentLogFilter == null) {
            allLogItems
        } else allLogItems.filter { it.type == currentLogFilter }
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()); binding.rvActivityLog.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() { override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder { val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false); return object : RecyclerView.ViewHolder(view) {} }; override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) { val item = filtered[position]; val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon); val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle); val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate); val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason); val btnDelete = holder.itemView.findViewById<View>(R.id.btnDeleteHistory); btnDelete.visibility = View.GONE; tvReason.visibility = View.VISIBLE; tvIcon.text = when(item.type) { "LATE" -> "⏰"; "EARLY" -> "🏃"; "GAP" -> "🚫"; "LEAVE" -> "🌴"; "BONUS" -> "🌟"; "PAID_LEAVE" -> "💎"; "ALLOWANCE" -> "🎁"; "ADVANCE" -> "💳"; "OT" -> "⚡"; else -> "📝" }; tvTitle.text = item.title; tvReason.text = item.desc; tvDate.text = sdf.format(Date(item.timestamp)) }; override fun getItemCount() = filtered.size }
    }

    data class ActivityLogItem(val type: String, val title: String, val desc: String, val timestamp: Long)

    private fun updateHistoryList(history: List<EmployeeHistory>) {
        binding.rvStaffHistory.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = history[position]
                val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
                val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
                val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
                val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
                val btnDelete = holder.itemView.findViewById<View>(R.id.btnDeleteHistory)

                btnDelete.visibility = View.GONE
                tvIcon.text = when(item.type) { "SALARY" -> "💰"; "SHIFT" -> "🕒"; "ALLOWANCE" -> "🎁"; else -> "📝" }

        tvReason.visibility = if (item.changeReason.isNullOrEmpty()) View.GONE else View.VISIBLE
        tvReason.text = item.changeReason

        tvTitle.text = when(item.type) {
            "SHIFT" -> {
                val start = item.shiftStart; val end = item.shiftEnd; val breakHrs = item.breakHours
                if (start.isEmpty() || end.isEmpty()) "Shift Timing Updated 🕒"
                else {
                    val sParts = start.split(":"); val eParts = end.split(":")
                    val sDecimal = (sParts.getOrNull(0)?.toInt() ?: 0) + (sParts.getOrNull(1)?.toInt() ?: 0) / 60.0
                    var eDecimal = (eParts.getOrNull(0)?.toInt() ?: 0) + (eParts.getOrNull(1)?.toInt() ?: 0) / 60.0
                    if (eDecimal < sDecimal) eDecimal += 24.0
                    val duration = (eDecimal - sDecimal - breakHrs).coerceAtLeast(0.0)
                    val modeLabel = if (item.shiftMode == "CONTINUOUS") "Continuous" else "Single Day"
                    "Executive Shift: $start - $end (%.1f h) [$modeLabel] 🏢".format(duration)
                }
            }
            "SALARY" -> {
                if (item.oldValue > 0 && item.oldValue != item.newValue) "Salary Grade: ₹%.0f ➔ ₹%.0f 📈".format(item.oldValue, item.newValue)
                else if (item.oldValue <= 0) "Initial Salary: ₹%.0f 💰".format(item.newValue)
                else "Salary: ₹%.0f 💰".format(item.newValue)
            }
            "ALLOWANCE" -> {
                if (item.oldValue > 0 && item.oldValue != item.newValue) "Benefit Update: ₹%.0f ➔ ₹%.0f 💎".format(item.oldValue, item.newValue)
                else if (item.oldValue <= 0) "Benefit: ₹%.0f 🎁".format(item.newValue)
                else "Benefit: ₹%.0f 🎁".format(item.newValue)
            }
            else -> "System Log: ${item.type} • ${item.newValue} 📝"
        }
        tvDate.text = "Effective: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(item.effectiveDate))}"

                holder.itemView.setOnClickListener {
                    showEditHistoryDialog(item)
                }
                holder.itemView.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@StaffDetailActivity)
                        .setTitle("Delete Change Record?")
                        .setMessage("This will remove this record from history. This might affect salary calculations.")
                        .setPositiveButton("Delete") { _, _ ->
                            viewModel.deleteEmployeeHistory(item)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            override fun getItemCount() = history.size
        }
    }

    private fun showEditHistoryDialog(history: EmployeeHistory) {
        val dialogBinding = com.biometric.app.databinding.DialogHikeUpdateBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Edit Change Record"
        dialogBinding.tvCurrentValue.text = "Prev Value: ₹${history.oldValue}"
        dialogBinding.etNewValue.setText(history.newValue.toString())
        dialogBinding.etReason.setText(history.changeReason)

        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        var effectiveDateMillis = history.effectiveDate
        var endDateMillis = history.endDate

        dialogBinding.etEffectiveDate.setText(sdf.format(Date(effectiveDateMillis)))
        dialogBinding.etEndDate.setText(endDateMillis?.let { sdf.format(Date(it)) } ?: getString(R.string.label_permanent))

        if (history.type == "SHIFT") {
            dialogBinding.etNewValue.visibility = View.GONE
            dialogBinding.tvCurrentValue.text = "Shift Record: ${history.shiftStart} - ${history.shiftEnd}"
        }

        dialogBinding.etEffectiveDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = effectiveDateMillis }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal[y, m] = d
                    cal[Calendar.HOUR_OF_DAY] = 0
                    cal[Calendar.MINUTE] = 0
                    effectiveDateMillis = cal.timeInMillis
                    dialogBinding.etEffectiveDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH]
            ).show()
        }

        dialogBinding.etEndDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = endDateMillis ?: System.currentTimeMillis() }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal[y, m] = d
                    cal[Calendar.HOUR_OF_DAY] = 23
                    cal[Calendar.MINUTE] = 59
                    endDateMillis = cal.timeInMillis
                    dialogBinding.etEndDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH]
            ).apply {
                setButton(android.app.DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.btn_permanent)) { _, _ ->
                    endDateMillis = null
                    dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
                }
            }.show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save Changes") { _, _ ->
                val newValue = dialogBinding.etNewValue.text.toString().toDoubleOrNull() ?: 0.0
                val reason = dialogBinding.etReason.text.toString()

                val updated = history.copy(
                    newValue = if (history.type == "SHIFT") history.newValue else newValue,
                    effectiveDate = effectiveDateMillis,
                    endDate = endDateMillis,
                    changeReason = reason
                )
                viewModel.updateEmployeeHistory(updated)
                HapticUtil.vibrateSuccess(binding.root)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun getTimestampForTime(dayMidnight: Long, timeStr: String): Long {
        val parts = timeStr.split(":")
        return Calendar.getInstance().apply {
            timeInMillis = dayMidnight
            set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toInt() ?: 0)
            set(Calendar.MINUTE, parts.getOrNull(1)?.toInt() ?: 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getEffectiveHistoryForDate(history: List<EmployeeHistory>, date: Long, type: String): EmployeeHistory? {
        val midnight = getMidnight(date)
        return history.filter { it.type == type }
            .sortedBy { it.effectiveDate }
            .findLast { midnight >= getMidnight(it.effectiveDate) && (it.endDate == null || midnight <= getMidnight(it.endDate!!)) }
            ?: history.filter { it.type == type }.sortedBy { it.effectiveDate }.firstOrNull { getMidnight(it.effectiveDate) <= midnight }
    }

    private fun observeAttendanceLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.allAttendancePunchesFlow,
                    repository.allDailySummariesFlow,
                    filterRefreshTrigger
                ) { punches, summaries, _ ->
                    punches to summaries
                }.collectLatest { (allPunches, allSummaries) ->
                    val startTs = getMidnight(calFrom.timeInMillis)
                    val endTs = getEndOfDay(calTo.timeInMillis)
                    val empId = employeeId
                    
                    if (empId.isBlank()) {
                        attendanceLogAdapter.submitList(emptyList())
                        return@collectLatest
                    }
                    
                    val filteredSummaries = allSummaries.filter { 
                        it.employeeId == empId.toIntOrNull() &&
                        DateRangeUtil.parseIsoDate(it.shiftDate) in startTs..endTs
                    }.sortedByDescending { it.shiftDate }
                    
                    val logRows = filteredSummaries.map { summary ->
                        val dayPunches = allPunches.filter { 
                            it.staffId == empId && it.date == summary.shiftDate 
                        }.sortedBy { it.timestamp }
                        
                        AttendanceLogRow(
                            date = summary.shiftDate,
                            status = summary.status,
                            scheduledDuration = summary.scheduledShiftDurationMs,
                            workedDuration = (summary.earnedStandardHours * 3600000).toLong() + summary.totalOvertimeMs,
                            otDuration = summary.totalOvertimeMs,
                            penaltyDuration = summary.totalPenaltyMs,
                            punches = dayPunches
                        )
                    }
                    
                    attendanceLogAdapter.submitList(logRows)
                }
            }
        }
    }

    data class AttendanceLogRow(
        val date: String,
        val status: String,
        val scheduledDuration: Long,
        val workedDuration: Long,
        val otDuration: Long,
        val penaltyDuration: Long,
        val punches: List<AttendancePunch>
    )

    inner class AttendanceLogAdapter : RecyclerView.Adapter<AttendanceLogAdapter.ViewHolder>() {
        private var list = emptyList<AttendanceLogRow>()

        fun submitList(newList: List<AttendanceLogRow>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_attendance_log_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvDate)
            val tvStatus = holder.itemView.findViewById<TextView>(R.id.tvStatus)
            val tvScheduled = holder.itemView.findViewById<TextView>(R.id.tvScheduled)
            val tvWorked = holder.itemView.findViewById<TextView>(R.id.tvWorked)
            val tvOT = holder.itemView.findViewById<TextView>(R.id.tvOT)
            val tvPenalty = holder.itemView.findViewById<TextView>(R.id.tvPenalty)
            val tvPunches = holder.itemView.findViewById<TextView>(R.id.tvPunches)

            tvDate.text = item.date
            tvStatus.text = item.status
            tvScheduled.text = formatDuration(item.scheduledDuration)
            tvWorked.text = formatDuration(item.workedDuration)
            tvOT.text = formatDuration(item.otDuration)
            tvPenalty.text = formatDuration(item.penaltyDuration)
            
            val punchStr = item.punches.joinToString(", ") { 
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            }
            tvPunches.text = if (punchStr.isNotEmpty()) "Punches: $punchStr" else "No punches recorded"
            tvPunches.visibility = if (item.punches.isNotEmpty()) View.VISIBLE else View.GONE

            // Color coding
            tvStatus.setTextColor(when {
                item.status.contains("Present", true) -> ContextCompat.getColor(holder.itemView.context, R.color.green)
                item.status.contains("Absent", true) -> ContextCompat.getColor(holder.itemView.context, R.color.red)
                else -> ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
            })
        }

        private fun formatDuration(ms: Long): String {
            val h = ms / 3600000
            val m = (ms % 3600000) / 60000
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }

        override fun getItemCount() = list.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    override fun onDestroy() {
        super.onDestroy()
        liveStatusListener?.let { liveStatusRef?.removeEventListener(it) }
        liveStatusListener = null
        liveStatusRef = null
        _binding = null
    }
}
