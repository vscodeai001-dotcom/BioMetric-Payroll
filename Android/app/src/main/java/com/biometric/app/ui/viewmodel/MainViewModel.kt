package com.biometric.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.api.AdminFeatureSettingsDto
import com.biometric.app.api.CompanySettingsResponse
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.*
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.biometric.app.util.DateRangeUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

data class ShopWorkforceState(
    val shop: Shop,
    val staffCount: Int = 0,
    val presentCount: Int = 0,
    val pendingLeaves: Int = 0,
    val pendingRegularizations: Int = 0
) : Serializable

data class GlobalDashboardStats(
    val totalWorkforce: Int = 0,
    val activeEmployees: Int = 0,
    val presentToday: Int = 0,
    val absentToday: Int = 0,
    val unpaidAdvances: Double = 0.0,
    val pendingPayrolls: Int = 0,
    val currentPayrollCost: Double = 0.0,
    val previousPayrollCost: Double = 0.0,
    val payrollVariancePercent: Double = 0.0,
    val shiftsScheduledToday: Int = 0,
    val totalMonthScheduledMs: Long = 0,
    val recentAdvances: List<AdvancePayment> = emptyList()
) : Serializable

private data class DashboardDataBundle(
    val advances: List<AdvancePayment>,
    val summaries: List<LocalDailySummary>,
    val schedules: List<LocalShiftSchedule>,
    val payrolls: List<LocalPayrollHistory>
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MainRepository,
    private val sharedViewModel: SharedViewModel,
    private val sessionStore: MobileSessionStore
) : ViewModel() {

    private var workforceRecalcJob: Job? = null

    val allShops: StateFlow<List<Shop>> = sharedViewModel.allShops

    private val _globalStats = MutableStateFlow(GlobalDashboardStats())
    val globalStats: StateFlow<GlobalDashboardStats> = _globalStats.asStateFlow()

    private val _shopsWorkforceState = MutableStateFlow<List<ShopWorkforceState>>(emptyList())
    val shopsWorkforceState: StateFlow<List<ShopWorkforceState>> = _shopsWorkforceState.asStateFlow()

    private val _currentPeriod = MutableStateFlow("Up To Date")
    val currentPeriod: StateFlow<String> = _currentPeriod.asStateFlow()

    private val _currentDate = MutableStateFlow(System.currentTimeMillis())
    val currentDate: StateFlow<Long> = _currentDate.asStateFlow()

    private val _customEndDate = MutableStateFlow<Long?>(null)
    val customEndDate: StateFlow<Long?> = _customEndDate.asStateFlow()

    private val _isSubscribed = MutableStateFlow(value = true)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _isLoading = MutableStateFlow(value = true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MIRROR: CompanySettings and FeatureSettings are now sourced from Room 
    // which is hydrated by FirebaseRoomHydrator. This ensures Dashboards 
    // update immediately and work offline.
    val companySettings: StateFlow<CompanySettingsResponse?> = repository.companySettingsFlow
        .map { local ->
            local?.let {
                CompanySettingsResponse(
                    companyName = it.companyName,
                    officeLatitude = it.officeLatitude,
                    officeLongitude = it.officeLongitude,
                    geoRadiusMeters = it.geoRadiusMeters
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val featureSettings: StateFlow<AdminFeatureSettingsDto?> = repository.featureSettingsFlow
        .map { local ->
            local?.let {
                AdminFeatureSettingsDto(
                    enableEmployeeManagement = it.enableEmployeeManagement,
                    enablePayroll = it.enablePayroll,
                    // enableAttendance missing in DTO, skipping
                    enableLeaveManagement = it.enableLeaveManagement,
                    enableSalaryAdvance = it.enableSalaryAdvance,
                    enableBonusManagement = it.enableBonusManagement,
                    enableProfessionalTax = it.enableProfessionalTax,
                    enableStatutoryCompliance = it.enableStatutoryCompliance,
                    enableEmailNotifications = it.enableEmailNotifications,
                    enableInAppNotifications = it.enableInAppNotifications,
                    enableCustomReporting = it.enableCustomReporting,
                    enableCompanyReports = it.enableCompanyReports,
                    enableAuditLog = it.enableAuditLog,
                    enableRecycleBin = it.enableRecycleBin,
                    enableGeoFencing = it.enableGeoFencing,
                    enableAutomaticGeofencePunching = it.enableAutomaticGeofencePunching,
                    enableDualAttendance = it.enableDualAttendance,
                    enablePunchCorrection = it.enablePunchCorrection,
                    enableRegularizationRequest = it.enableRegularizationReq,
                    enableResignationModule = it.enableResignationModule,
                    enableYearEndSummary = it.enableYearEndSummary,
                    enableTaxDeclarations = it.enableTaxDeclarations,
                    enableFlexibleBenefits = it.enableFlexibleBenefits,
                    enableTdsDeduction = it.enableTdsDeduction,
                    enableAutoShiftRotation = it.enableAutoShiftRotation,
                    enableShiftScheduling = it.enableShiftScheduling,
                    enableSandwichRule = it.enableSandwichRule,
                    enableLeaveAccrual = it.enableLeaveAccrual,
                    showThemeToggle = it.showThemeToggle,
                    employeeToolsVisible = it.employeeToolsVisible,
                    employeeCanViewDashboard = it.employeeCanViewDashboard,
                    employeeCanViewAttendance = it.employeeCanViewAttendance,
                    employeeCanViewLeave = it.employeeCanViewLeave,
                    employeeCanViewLeaveHistory = it.employeeCanViewLeaveHistory,
                    employeeCanViewAdvance = it.employeeCanViewAdvance,
                    employeeCanViewBonus = it.employeeCanViewBonus,
                    employeeCanViewTax = it.employeeCanViewTax,
                    employeeCanViewPayslip = it.employeeCanViewPayslip,
                    employeeCanViewResignation = it.employeeCanViewResignation,
                    employeeCanViewReports = it.employeeCanViewReports,
                    employeeCanViewShifts = it.employeeCanViewShifts,
                    adminCanViewDashboard = it.adminCanViewDashboard,
                    adminCanViewAttendance = it.adminCanViewAttendance,
                    adminCanManageShifts = it.adminCanManageShifts,
                    adminCanRunPayroll = it.adminCanRunPayroll,
                    adminCanViewReports = it.adminCanViewReports,
                    adminCanManageEmployees = it.adminCanManageEmployees,
                    adminCanEditSettings = it.adminCanEditSettings,
                    adminCanManageEmployeePermissions = it.adminCanManageEmployeePermissions,
                    adminCanManagePunchApprovals = it.adminCanManagePunchApprovals
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        restoreStatsCache()
        checkSubscription()
        ensureUserProfileExists()
        
        viewModelScope.launch {
            sharedViewModel.refreshRequested.collect {
                triggerRefresh()
            }
        }

        viewModelScope.launch {
            combine(allShops, _currentPeriod, _currentDate, _customEndDate) { shops, period, date, endDate ->
                recalculateWorkforce(shops, period, date, endDate)
            }.catch { e -> Log.e("MainViewModel", "Error in workforce trigger flow", e) }
            .collect()
        }
    }

    fun triggerRefresh() {
        viewModelScope.launch {
            recalculateWorkforce(allShops.value, _currentPeriod.value, _currentDate.value, _customEndDate.value)
        }
    }

    @OptIn(FlowPreview::class)
    private fun recalculateWorkforce(shops: List<Shop>, period: String, date: Long, endDate: Long?) {
        workforceRecalcJob?.cancel()
        
        // The Web dashboard is company-wide. Shops are a legacy Android cache
        // dimension and must never prevent employee/attendance KPIs from loading.
        _isLoading.value = true

        workforceRecalcJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val range = DateRangeUtil.getRangeForPeriod(period, date, endDate = endDate)
                
                val dashboardData = combine(
                    sharedViewModel.allAdvances,
                    repository.allDailySummariesFlow,
                    repository.allShiftSchedulesFlow,
                    repository.allPayrollHistoriesFlow
                ) { advances, summaries, schedules, payrolls ->
                    DashboardDataBundle(advances, summaries, schedules, payrolls)
                }

                combine(
                    sharedViewModel.allEmployees,
                    sharedViewModel.allAttendancePunches, // Use Punches (SSOT) instead of Sessions (Legacy)
                    repository.allRegularizationsFlow,
                    dashboardData
                ) { employees, attendance, regularizations, bundle ->
                    val advances = bundle.advances
                    val summaries = bundle.summaries
                    val schedules = bundle.schedules
                    val payrolls = bundle.payrolls

                    val activeStaff = employees.filter { it.isActive }
                    val activeIds = activeStaff.map { it.employeeId.toString() }.toSet()
                    val today = System.currentTimeMillis()
                    val startOfToday = DateRangeUtil.getStartOfDay(today)
                    val dateTodayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(today))
                    
                    // MIRROR: presentCount calculation now uses the authoritative punches
                    // collection to ensure real-time parity with the Web dashboard.
                    val presentCount = attendance.filter { 
                        it.timestamp >= startOfToday && activeIds.contains(it.staffId)
                    }.distinctBy { it.staffId }.size

                    val unpaidAdvAmount = advances.filter { !it.isRecovered }.sumOf { it.amount }
                    val recentAdvancesList = advances.filter { !it.isRecovered }.sortedByDescending { it.date }.take(4)

                    // Ported from DashboardAnalyticsService.cs & Home.razor
                    // Blazor uses DateTime.Now.AddMonths(-1) for the "current" cycle in dashboard.
                    val monthCal = Calendar.getInstance()
                    monthCal.add(Calendar.MONTH, -1)
                    val targetMonth = monthCal.get(Calendar.MONTH) + 1
                    val targetYear = monthCal.get(Calendar.YEAR)

                    val currentPayrollCost = payrolls.filter { it.payMonth == targetMonth && it.payYear == targetYear }.sumOf { it.netSalary }
                    
                    monthCal.add(Calendar.MONTH, -1)
                    val prevMonth = monthCal.get(Calendar.MONTH) + 1
                    val prevYear = monthCal.get(Calendar.YEAR)
                    val previousPayrollCost = payrolls.filter { it.payMonth == prevMonth && it.payYear == prevYear }.sumOf { it.netSalary }
                    
                    val variance = if (previousPayrollCost == 0.0) 0.0 else ((currentPayrollCost - previousPayrollCost) / previousPayrollCost) * 100.0

                    val shiftsToday = schedules.filter { it.shiftDate == dateTodayStr }.size
                    
                    val summariesThisMonth = summaries.filter { 
                        val sDate = it.shiftDate.split("-")
                        if (sDate.size == 3) {
                            sDate[0].toInt() == Calendar.getInstance().get(Calendar.YEAR) && sDate[1].toInt() == Calendar.getInstance().get(Calendar.MONTH) + 1
                        } else false
                    }
                    val totalScheduledMs = summariesThisMonth.sumOf { it.scheduledShiftDurationMs }

                    val hasLastMonthPayroll = payrolls.any { it.payMonth == targetMonth && it.payYear == targetYear }
                    val pendingPayrollsCount = if (hasLastMonthPayroll) 0 else 1

                    // Update Global Stats
                    val stats = GlobalDashboardStats(
                        totalWorkforce = employees.size,
                        activeEmployees = activeStaff.size,
                        presentToday = presentCount,
                        absentToday = Math.max(0, activeStaff.size - presentCount),
                        unpaidAdvances = unpaidAdvAmount,
                        pendingPayrolls = pendingPayrollsCount,
                        currentPayrollCost = currentPayrollCost,
                        previousPayrollCost = previousPayrollCost,
                        payrollVariancePercent = variance,
                        shiftsScheduledToday = shiftsToday,
                        totalMonthScheduledMs = totalScheduledMs,
                        recentAdvances = recentAdvancesList
                    )
                    
                    // Critical: Update stats and hide loader immediately upon first successful calculation
                    _globalStats.value = stats
                    saveStatsCache(stats)

                    shops.map { shop ->
                        val shopEmployees = employees.filter { it.shopId == shop.shopId && it.isActive }
                        val shopActiveIds = shopEmployees.map { it.employeeId }.toSet()
                        
                        // MIRROR: Shop-level present count now uses the authoritative punches
                        // SSOT to match the behavior of the Web dashboard.
                        val shopPresent = attendance.filter { 
                            it.timestamp >= startOfToday && shopActiveIds.contains(it.staffId)
                        }.distinctBy { it.staffId }.size
                        
                        val pendingRegs = regularizations.filter { 
                            it.status == "Pending" 
                        }.size

                        ShopWorkforceState(
                            shop = shop,
                            staffCount = shopEmployees.size,
                            presentCount = shopPresent,
                            pendingRegularizations = pendingRegs
                        )
                    }
                }.debounce(1200.milliseconds) // Throttle UI updates during bulk Firebase sync
                .collect { states ->
                    _shopsWorkforceState.value = states
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Workforce recalculation failed", e)
                _isLoading.value = false
            }
        }
    }

    private fun ensureUserProfileExists() {
        viewModelScope.launch {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    val profile = repository.getProfileFlow().firstOrNull()
                    if (profile == null) {
                        val newProfile = UserProfile(
                            uid = currentUser.uid,
                            phone = currentUser.phoneNumber ?: "",
                            name = currentUser.displayName ?: "Admin",
                            role = UserProfile.ROLE_OWNER
                        )
                        repository.pushProfileByUid(newProfile)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkSubscription() {
        viewModelScope.launch {
            repository.getProfileFlow().catch { emit(null) }.collectLatest { profile ->
                if (profile != null) {
                    val sevenDays = 7 * 24 * 60 * 60 * 1000L
                    val isTrialOver = System.currentTimeMillis() > (profile.joinDate + sevenDays)
                    _isSubscribed.value = profile.isPremium || !isTrialOver
                } else {
                    _isSubscribed.value = true
                }
            }
        }
    }

    fun upgradeToPremium(profile: UserProfile) {
        viewModelScope.launch {
            try { repository.updateProfile(profile) } catch (_: Exception) {}
        }
    }

    fun addShop(shop: Shop) {
        viewModelScope.launch { try { repository.insertShop(shop) } catch (_: Exception) {} }
    }

    fun deleteShop(shop: Shop) {
        viewModelScope.launch { try { repository.deleteShop(shop.shopId) } catch (_: Exception) {} }
    }

    fun setFilter(period: String, date: Long = System.currentTimeMillis(), endDate: Long? = null) {
        _isLoading.value = true
        _currentPeriod.value = period
        _currentDate.value = date
        _customEndDate.value = endDate
    }

    fun startRealtimeSync() {
        try { repository.startSync() } catch (_: Exception) {}
    }

    private fun saveStatsCache(stats: GlobalDashboardStats) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = Gson().toJson(stats)
                sessionStore.saveDashboardCache(json)
            } catch (_: Exception) {}
        }
    }

    private fun restoreStatsCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = sessionStore.getDashboardCache()
                if (!json.isNullOrBlank()) {
                    val stats = Gson().fromJson(json, GlobalDashboardStats::class.java)
                    if (stats != null) {
                        _globalStats.value = stats
                    }
                }
            } catch (_: Exception) {}
        }
    }

}
