package com.biometric.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.api.AdminFeatureSettingsDto
import com.biometric.app.api.CompanySettingsResponse
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.*
import com.biometric.app.util.DateRangeUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    private val mobileApi: MobileApiService,
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

    private val _companySettings = MutableStateFlow<CompanySettingsResponse?>(null)
    val companySettings = _companySettings.asStateFlow()

    private val _featureSettings = MutableStateFlow<AdminFeatureSettingsDto?>(null)
    val featureSettings = _featureSettings.asStateFlow()

    init {
        restoreStatsCache()
        checkSubscription()
        ensureUserProfileExists()
        loadCompanySettings()
        loadFeatureSettings()
        
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
        loadCompanySettings()
        loadFeatureSettings()
        viewModelScope.launch {
            recalculateWorkforce(allShops.value, _currentPeriod.value, _currentDate.value, _customEndDate.value)
        }
    }

    private fun loadCompanySettings() {
        val token = sessionStore.token() ?: return
        viewModelScope.launch {
            try {
                val response = mobileApi.getCompanySettings("Bearer $token")
                if (response.isSuccessful) {
                    _companySettings.value = response.body()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load company settings", e)
            }
        }
    }

    private fun loadFeatureSettings() {
        val token = sessionStore.token() ?: return
        viewModelScope.launch {
            try {
                val response = mobileApi.getAdminFeatureSettings("Bearer $token")
                if (response.isSuccessful) {
                    _featureSettings.value = response.body()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load feature settings", e)
            }
        }
    }

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
                    sharedViewModel.allAttendance,
                    repository.allRegularizationsFlow,
                    dashboardData
                ) { employees, attendance, regularizations, bundle ->
                    val advances = bundle.advances
                    val summaries = bundle.summaries
                    val schedules = bundle.schedules
                    val payrolls = bundle.payrolls

                    val activeStaff = employees.filter { it.isActive }
                    val activeIds = activeStaff.map { it.employeeId }.toSet()
                    val today = System.currentTimeMillis()
                    val startOfToday = DateRangeUtil.getStartOfDay(today)
                    val dateTodayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(today))
                    
                    val presentCount = attendance.filter { 
                        it.checkInTime >= startOfToday && activeIds.contains(it.employeeId)
                    }.distinctBy { it.employeeId }.size

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
                    
                    // Critical: Update states and hide loader immediately upon first successful calculation
                    _globalStats.value = stats
                    saveStatsCache(stats)

                    shops.map { shop ->
                        val shopEmployees = employees.filter { it.shopId == shop.shopId && it.isActive }
                        val shopPresent = attendance.filter { 
                            it.shopId == shop.shopId && it.checkInTime >= startOfToday && activeIds.contains(it.employeeId)
                        }.distinctBy { it.employeeId }.size
                        
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
                }.collect { states ->
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

    suspend fun startNeonSync() {
        try { repository.startNeonSync() } catch (_: Exception) {}
    }
}
