package com.biometric.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.data.EmployeeStats
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StaffViewModel @Inject constructor(
    application: Application,
    private val repository: MainRepository,
    val sharedViewModel: SharedViewModel,
) : AndroidViewModel(application) {

    private val _shopId = MutableStateFlow(sharedViewModel.selectedShop.value?.shopId)
    val shopId = _shopId.asStateFlow()
    private val _selectedCalendar = MutableStateFlow(Calendar.getInstance())
    private val _selectedDay = MutableStateFlow(Calendar.getInstance())
    private val _searchQuery = MutableStateFlow("")

    private val _isStaffLoading = MutableStateFlow(value = true)
    val isStaffLoading = _isStaffLoading.asStateFlow()

    val salaryRules: StateFlow<SalaryRules> = _shopId.flatMapLatest { sId ->
        if (sId.isNullOrBlank()) flowOf(SalaryRules())
        else repository.allShopsFlow.map { shops ->
            shops.find { it.shopId == sId }?.salaryRules ?: SalaryRules()
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, SalaryRules())

    val employees: StateFlow<List<Employee>> = combine(_shopId, _selectedCalendar, _searchQuery) { sId, _, query ->
        sId to query
    }.flatMapLatest { (sId, query) ->
        val flow = if (sId.isNullOrBlank()) repository.getAllEmployees() else repository.getShopEmployees(sId)
        flow.map { list ->
            list.filter { emp ->
                val isActive = (emp.isActive || emp.terminateDate == null)
                val matchesQuery = query.isBlank() || emp.name.contains(query, ignoreCase = true)
                isActive && matchesQuery
            }
        }.onEach { _isStaffLoading.value = false }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _employeeStats = MutableStateFlow<Map<String, EmployeeStats>>(emptyMap())
    val employeeStats: StateFlow<Map<String, EmployeeStats>> = _employeeStats.asStateFlow()

    private val _totalMonthlyBaseSalary = MutableStateFlow(0.0)
    val totalMonthlyBaseSalary: StateFlow<Double> = _totalMonthlyBaseSalary.asStateFlow()

    private val _totalMonthlyEarnedSalary = MutableStateFlow(0.0)

    private val _totalMonthlyEarnedOT = MutableStateFlow(0.0)
    val totalMonthlyEarnedOT: StateFlow<Double> = _totalMonthlyEarnedOT.asStateFlow()

    private val _totalMonthlyEarnedAllowance = MutableStateFlow(0.0)
    val totalMonthlyEarnedAllowance: StateFlow<Double> = _totalMonthlyEarnedAllowance.asStateFlow()

    private val _totalMonthlyStaffCost = MutableStateFlow(0.0)
    val totalMonthlyStaffCost: StateFlow<Double> = _totalMonthlyStaffCost.asStateFlow()

    private val _totalFullMonthStaffCost = MutableStateFlow(0.0)
    val totalFullMonthStaffCost: StateFlow<Double> = _totalFullMonthStaffCost.asStateFlow()

    private val _totalDailyStaffCost = MutableStateFlow(0.0)
    val totalDailyStaffCost: StateFlow<Double> = _totalDailyStaffCost.asStateFlow()

    private val _selectedEmployee = MutableStateFlow<Employee?>(null)
    val selectedEmployee: StateFlow<Employee?> = _selectedEmployee.asStateFlow()

    val selectedCalendar: StateFlow<Calendar> = _selectedCalendar.asStateFlow()
    val selectedDay: StateFlow<Calendar> = _selectedDay.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allShopsFlow.firstOrNull()?.let { shops ->
                if (_shopId.value == null && shops.isNotEmpty()) {
                    _shopId.value = shops.first().shopId
                }
            }
        }

        viewModelScope.launch {
            combine(employees, _selectedCalendar, _selectedDay) { list, cal, day ->
                DataParams(list, cal, day)
            }.flatMapLatest { params ->
                if (params.list.isEmpty()) {
                    _isStaffLoading.value = false
                    flowOf(emptyMap())
                } else {
                    if (_employeeStats.value.isEmpty()) {
                        _isStaffLoading.value = true
                    }
                    val cal = params.cal
                    val monthStart = (cal.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val monthEnd = (cal.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                    }
                    val dayCal = params.day
                    val selDayStart = (dayCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val selDayEnd = (dayCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                    }
                    repository.getBatchEmployeeStatsFlow(
                        employees = params.list,
                        start = monthStart.timeInMillis,
                        end = monthEnd.timeInMillis,
                        selDayStart = selDayStart.timeInMillis,
                        selDayEnd = selDayEnd.timeInMillis,
                    ).catch { e -> Log.e("StaffViewModel", "Error in batch stats flow", e); emit(emptyMap()) }
                }
            }.collectLatest { statsMap ->
                _employeeStats.value = statsMap
                updateTotalCosts(statsMap)
                _isStaffLoading.value = false
            }
        }
    }

    private data class DataParams(val list: List<Employee>, val cal: Calendar, val day: Calendar)

    private fun updateTotalCosts(statsMap: Map<String, EmployeeStats>) {
        _totalMonthlyEarnedSalary.value = statsMap.values.sumOf { it.totalNormalWorkedSalaryMonth.toDouble() + it.bonusAmount.toDouble() }
        _totalMonthlyEarnedOT.value = statsMap.values.sumOf { it.totalOTSalaryMonth.toDouble() }
        _totalMonthlyEarnedAllowance.value = statsMap.values.sumOf { it.monthlyAllowance.toDouble() }
        _totalMonthlyStaffCost.value = statsMap.values.sumOf { it.getRoundedPeriodLiability() }
        _totalMonthlyBaseSalary.value = statsMap.values.sumOf { it.fullMonthSalary }
        _totalFullMonthStaffCost.value = statsMap.values.sumOf { it.getFullMonthProjectedCost() }
        _totalDailyStaffCost.value = statsMap.values.sumOf { it.getSelectedDayTotalCost() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShop(id: String) { 
        if (_shopId.value != id) {
            _shopId.value = id
        }
    }

    fun setSelectedMonth(cal: Calendar) { 
        _selectedCalendar.value = cal
    }

    fun setSelectedDay(cal: Calendar) { _selectedDay.value = cal }

    private val _isHolidaysLoading = MutableStateFlow(value = true)
    val isHolidaysLoading = _isHolidaysLoading.asStateFlow()

    fun getClosedDays(shopId: String, start: Long, end: Long): Flow<List<ShopClosedDay>> =
        repository.getClosedDays(shopId, start, end)
            .onStart { _isHolidaysLoading.value = true }
            .onEach { _isHolidaysLoading.value = false }
            .catch { emit(emptyList()); _isHolidaysLoading.value = false }

    fun addClosedDay(day: ShopClosedDay) { 
        viewModelScope.launch { 
            try { repository.insertClosedDay(day) }
            catch (e: Exception) { Log.e("StaffViewModel", "Insert closed day failed", e) } 
        } 
    }
    
    fun deleteClosedDay(day: ShopClosedDay) { 
        viewModelScope.launch { 
            try { repository.deleteClosedDay(day) }
            catch (e: Exception) { Log.e("StaffViewModel", "Delete closed day failed", e) } 
        } 
    }

    fun addEmployeeDetailed(
        name: String, bioId: String, role: String, email: String, phone: String,
        salaryRate: Double, type: String, calcMethod: String,
        start: String, end: String, breakHours: Double,
        otRule: String, otFlatRate: Double, compOff: Int?,
        hireDate: Long, dob: Long?,
        loginId: String, password: String,
        bankAccount: String?, bankIfsc: String?, bankName: String?, uan: String?, esiNum: String?,
        enablePf: Boolean, enableEsi: Boolean, tdsRate: Double,
        allowance: Double, nightAllowance: Double,
        enableRotation: Boolean, rotGroup: String?, rotPattern: String?,
        bonusEligible: Boolean, plEligible: Boolean,
    ): Boolean {
        val sId = _shopId.value ?: run {
            Log.e("StaffViewModel", "Cannot add employee: No shop selected")
            return false
        }
        viewModelScope.launch {
            try {
                val empId = UUID.randomUUID().toString()
                val newEmployee = Employee(
                    employeeId = empId, shopId = sId, name = name, biometricId = bioId, role = role, email = email, phone = phone,
                    salaryType = type, salaryRate = salaryRate, salaryCalculationMethod = calcMethod,
                    shiftStart = start, shiftEnd = end, breakHours = breakHours,
                    otRule = otRule, otFlatRate = otFlatRate, compOffDayOfWeek = compOff,
                    hireDate = hireDate, dob = dob,
                    bankAccountNumber = bankAccount, bankIfscCode = bankIfsc, bankName = bankName,
                    uanNumber = uan, esiNumber = esiNum, enablePf = enablePf, enableEsi = enableEsi, tdsRatePercent = tdsRate,
                    dailyAllowance = allowance, nightShiftAllowance = nightAllowance,
                    enableShiftRotation = enableRotation, rotationGroup = rotGroup, shiftRotationPattern = rotPattern,
                    isBonusEligibleRule = bonusEligible, isPaidLeaveEligibleRule = plEligible
                )
                repository.insertEmployee(newEmployee)

                val userProfile = UserProfile(
                    uid = empId, name = name, phone = phone, employeeId = loginId.ifBlank { empId },
                    password = password, role = UserRole.STAFF.name
                )
                repository.pushProfileByUid(userProfile)
                
                repository.pushHistoryAtomic(EmployeeHistory(
                    employeeId = empId, type = "SALARY", salaryType = type, oldValue = 0.0, newValue = salaryRate,
                    shiftStart = start, shiftEnd = end, breakHours = breakHours, changeDate = System.currentTimeMillis(),
                    effectiveDate = hireDate, changeReason = "Hired"
                ))
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { 
                Log.e("StaffViewModel", "Add failed", e) 
            }
        }
        return true
    }

    fun updateEmployeeDetailed(
        empId: String, name: String, bioId: String, role: String, email: String, phone: String,
        salaryRate: Double, type: String, calcMethod: String,
        start: String, end: String, breakHours: Double,
        otRule: String, otFlatRate: Double, compOff: Int?,
        hireDate: Long, dob: Long?, terminateDate: Long?,
        loginId: String, password: String,
        bankAccount: String?, bankIfsc: String?, bankName: String?, uan: String?, esiNum: String?,
        enablePf: Boolean, enableEsi: Boolean, tdsRate: Double,
        allowance: Double, nightAllowance: Double,
        enableRotation: Boolean, rotGroup: String?, rotPattern: String?,
        bonusEligible: Boolean, plEligible: Boolean,
        effectiveDate: Long
    ) {
        viewModelScope.launch {
            try {
                val old = repository.getEmployee(empId).firstOrNull() ?: return@launch
                val updated = old.copy(
                    name = name, biometricId = bioId, role = role, email = email, phone = phone,
                    salaryType = type, salaryRate = salaryRate, salaryCalculationMethod = calcMethod,
                    shiftStart = start, shiftEnd = end, breakHours = breakHours,
                    otRule = otRule, otFlatRate = otFlatRate, compOffDayOfWeek = compOff,
                    hireDate = hireDate, dob = dob, terminateDate = terminateDate, isActive = terminateDate == null,
                    bankAccountNumber = bankAccount, bankIfscCode = bankIfsc, bankName = bankName,
                    uanNumber = uan, esiNumber = esiNum, enablePf = enablePf, enableEsi = enableEsi, tdsRatePercent = tdsRate,
                    dailyAllowance = allowance, nightShiftAllowance = nightAllowance,
                    enableShiftRotation = enableRotation, rotationGroup = rotGroup, shiftRotationPattern = rotPattern,
                    isBonusEligibleRule = bonusEligible, isPaidLeaveEligibleRule = plEligible
                )
                repository.updateEmployee(updated)

                val profile = UserProfile(uid = empId, name = name, phone = phone, employeeId = loginId, password = password)
                repository.pushProfileByUid(profile)

                if (old.salaryRate != salaryRate || old.salaryType != type) {
                    addHistoryRecord(empId, "SALARY", effectiveDate, null, salaryRate, old.salaryRate, "Update", updated)
                }
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Update failed", e) }
        }
    }

    private val _selectedStaffProfile = MutableStateFlow<UserProfile?>(null)
    val selectedStaffProfile: StateFlow<UserProfile?> = _selectedStaffProfile.asStateFlow()

    fun loadEmployee(id: String) {
        viewModelScope.launch {
            repository.getEmployee(id)
                .catch { e -> Log.e("StaffViewModel", "Load employee failed", e); emit(null) }
                .collect { _selectedEmployee.value = it }
        }
        viewModelScope.launch {
            try {
                _selectedStaffProfile.value = UserProfile(uid = id)
            } catch (e: Exception) { Log.e("StaffViewModel", "Load profile failed", e) }
        }
    }

    private fun addHistoryRecord(
        employeeId: String, type: String, effectiveDate: Long, endDate: Long?, newValue: Double, oldValue: Double,
        changeReason: String, employee: Employee, salaryType: String? = null, shiftStart: String? = null,
        shiftEnd: String? = null, breakHours: Double? = null
    ) {
        val record = EmployeeHistory(
            employeeId = employeeId, type = type, salaryType = salaryType ?: employee.salaryType,
            oldValue = oldValue, newValue = newValue, shiftStart = shiftStart ?: employee.shiftStart,
            shiftEnd = shiftEnd ?: employee.shiftEnd, breakHours = breakHours ?: employee.breakHours,
            changeDate = System.currentTimeMillis(), effectiveDate = effectiveDate, endDate = endDate, changeReason = changeReason
        )
        repository.pushHistoryAtomic(record)
    }

    fun updateShiftTiming(employee: Employee, start: String, end: String, breakHours: Double, wStart: String?, wEnd: String?, wBreak: Double?, effectiveDate: Long, endDate: Long? = null, changeReason: String? = null, s2Start: String? = null, s2End: String? = null, ws2Start: String? = null, ws2End: String? = null) {
        viewModelScope.launch {
            try {
                val updatedEmployee = employee.copy(shiftStart = start, shiftEnd = end, breakHours = breakHours, shift2Start = s2Start, shift2End = s2End, weekendShiftStart = wStart, weekendShiftEnd = wEnd, weekendBreakHours = wBreak, weekendShift2Start = ws2Start, weekendShift2End = ws2End)
                repository.updateEmployee(updatedEmployee)
                addHistoryRecord(employee.employeeId, "SHIFT", effectiveDate, endDate, 0.0, 0.0, changeReason ?: "Shift Updated", updatedEmployee, shiftStart = start, shiftEnd = end, breakHours = breakHours)
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Update shift failed", e) }
        }
    }

    fun updateSalaryHike(employee: Employee, newRate: Double, effectiveDate: Long, endDate: Long?, reason: String?) {
        viewModelScope.launch {
            try {
                addHistoryRecord(employee.employeeId, "SALARY", effectiveDate, endDate, newRate, employee.salaryRate, reason ?: "Salary Update", employee)
                val now = System.currentTimeMillis()
                if (endDate == null || (now in effectiveDate..endDate)) { repository.updateEmployee(employee.copy(salaryRate = newRate)) }
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Salary hike failed", e) }
        }
    }

    fun updateAllowanceHike(employee: Employee, newAllowance: Double, effectiveDate: Long, endDate: Long?, reason: String?) {
        viewModelScope.launch {
            try {
                addHistoryRecord(employee.employeeId, "ALLOWANCE", effectiveDate, endDate, newAllowance, employee.dailyAllowance, reason ?: "Allowance Update", employee)
                val now = System.currentTimeMillis()
                if (endDate == null || (now in effectiveDate..endDate)) { repository.updateEmployee(employee.copy(dailyAllowance = newAllowance)) }
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Allowance hike failed", e) }
        }
    }

    val employeeHistory: StateFlow<Map<String, List<EmployeeHistory>>> = repository.firebaseSync.getDataFlow<EmployeeHistory>("employee_history")
        .map { list -> list.asSequence().groupBy { it.employeeId }.mapValues { it.value.sortedByDescending { h -> h.effectiveDate } } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    fun updateMonthOverride(employee: Employee, monthKey: String, type: String, isIncluded: Boolean) {
        viewModelScope.launch {
            try {
                val updatedEmployee = when (type) {
                    "BONUS" -> {
                        val newMap = employee.monthlyBonusOverrides.toMutableMap()
                        newMap[monthKey] = isIncluded
                        employee.copy(monthlyBonusOverrides = newMap)
                    }
                    "PAID_LEAVE" -> {
                        val newMap = employee.monthlyPaidLeaveOverrides.toMutableMap()
                        newMap[monthKey] = isIncluded
                        employee.copy(monthlyPaidLeaveOverrides = newMap)
                    }
                    else -> employee
                }
                if (updatedEmployee != employee) {
                    repository.updateEmployee(updatedEmployee)
                }
            } catch (e: Exception) {
                Log.e("StaffViewModel", "Update month override failed", e)
            }
        }
    }

    fun deleteEmployee(employee: Employee) { 
        viewModelScope.launch { 
            try { 
                repository.deleteEmployee(employee) 
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Delete employee failed", e) } 
        } 
    }

    fun markAttendance(employee: Employee, date: Date) {
        viewModelScope.launch {
            try {
                repository.markAttendance(employee, date)
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Mark attendance failed", e) }
        }
    }

    fun addManualAttendanceEntry(employee: Employee, inTime: Long, outTime: Long, isGap: Boolean) {
        val sId = _shopId.value ?: return
        if (inTime < employee.hireDate || (employee.terminateDate != null && inTime > employee.terminateDate!!)) return
        viewModelScope.launch {
            try {
                val duration = if (outTime > inTime) (outTime - inTime).toDouble() / 3600000.0 else 0.0
                repository.insertAttendance(
                    Attendance(
                        attendanceId = UUID.randomUUID().toString(),
                        employeeId = employee.employeeId,
                        shopId = sId,
                        checkInTime = inTime,
                        checkOutTime = if (outTime > 0) outTime else null,
                        hoursWorked = if (isGap) -duration else duration,
                        type = if (isGap) "GAP" else "WORK",
                        shiftStart = employee.shiftStart,
                        shiftEnd = employee.shiftEnd,
                        breakHours = employee.breakHours,
                        salaryType = employee.salaryType,
                        salaryRate = employee.salaryRate
                    )
                )
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Manual entry failed", e) }
        }
    }

    fun updateAttendance(attendance: Attendance) { viewModelScope.launch { try { repository.updateAttendance(attendance); sharedViewModel.triggerDashboardRefresh() } catch (e: Exception) { Log.e("StaffViewModel", "Update attendance failed", e) } } }
    fun deleteAttendance(attendance: Attendance) { viewModelScope.launch { try { repository.deleteAttendance(attendance); sharedViewModel.triggerDashboardRefresh() } catch (e: Exception) { Log.e("StaffViewModel", "Delete attendance failed", e) } } }

    fun giveAdvance(employeeId: String, amount: Double, date: Date = Date()) {
        val sId = _shopId.value ?: return
        viewModelScope.launch {
            try {
                val advanceId = UUID.randomUUID().toString()
                repository.insertAdvance(AdvancePayment(advanceId, employeeId, sId, amount, date.time))
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Give advance failed", e) }
        }
    }

    fun calculateAndPaySalary(employee: Employee, startTime: Long, endTime: Long) {
        val sId = _shopId.value ?: return
        viewModelScope.launch {
            try {
                repository.getEmployeeStatsFlow(employee, startTime, endTime).first().let { stats ->
                    val netPayable = stats.getNetPayableForStaffScreen()
                    val roundedNet = netPayable.roundToInt().toDouble()
                    val paymentId = UUID.randomUUID().toString()
                    repository.insertSalaryPayment(SalaryPayment(paymentId, employee.employeeId, sId, netPayable, 0.0, roundedNet, startTime, endTime))
                    sharedViewModel.triggerDashboardRefresh()
                }
            } catch (e: Exception) { Log.e("StaffViewModel", "Pay salary failed", e) }
        }
    }

    fun deleteEmployeeHistory(history: EmployeeHistory) {
        viewModelScope.launch {
            try {
                repository.deleteEmployeeHistory(history)
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) { Log.e("StaffViewModel", "Delete history failed", e) }
        }
    }

    fun updateEmployeeHistory(history: EmployeeHistory) {
        viewModelScope.launch {
            try {
                repository.firebaseSync.pushHistory(history)
                
                val employee = repository.getEmployee(history.employeeId).first()
                if (employee != null) {
                    val now = System.currentTimeMillis()
                    val isActive = if (history.endDate == null) {
                        history.effectiveDate <= now
                    } else {
                        now in history.effectiveDate..history.endDate!!
                    }

                    if (isActive) {
                        val updatedEmp = when (history.type) {
                            "SALARY" -> employee.copy(salaryRate = history.newValue, salaryType = history.salaryType)
                            "ALLOWANCE" -> employee.copy(dailyAllowance = history.newValue)
                            "SHIFT" -> employee.copy(
                                shiftStart = history.shiftStart,
                                shiftEnd = history.shiftEnd,
                                breakHours = history.breakHours,
                            )
                            else -> employee
                        }
                        if (updatedEmp != employee) {
                            repository.updateEmployee(updatedEmp)
                        }
                    }
                }
                sharedViewModel.triggerDashboardRefresh()
            } catch (e: Exception) {
                Log.e("StaffViewModel", "Update history failed", e)
            }
        }
    }
}
