package com.biometric.app.ui.viewmodel

import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SharedViewModel @Inject constructor(
    private val repository: MainRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val userProfile: StateFlow<UserProfile?> = repository.getProfileFlow()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val currentEmployee: StateFlow<Employee?> = userProfile.filterNotNull()
        .flatMapLatest { profile ->
            if (profile.isStaff()) repository.getEmployee(profile.uid)
            else flowOf(null)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val allShops: StateFlow<List<Shop>> = repository.getAllShops()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val allEmployees: StateFlow<List<Employee>> = repository.allEmployeesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val allAttendance: StateFlow<List<Attendance>> = repository.allAttendanceFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAttendancePunches: StateFlow<List<AttendancePunch>> = repository.allAttendancePunchesFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAdvances: StateFlow<List<AdvancePayment>> = repository.allAdvancesFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRegularizations: StateFlow<List<RegularizationRequest>> = repository.allRegularizationsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLeaveRequests: StateFlow<List<LeaveRequest>> = repository.allLeaveRequestsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedShop = MutableStateFlow<Shop?>(null)
    val selectedShop: StateFlow<Shop?> = _selectedShop.asStateFlow()

    private val _isWarmingUp = MutableStateFlow(false)
    val isWarmingUp = _isWarmingUp.asStateFlow()

    fun warmUpDashboard() {
        if (_isWarmingUp.value) return
        _isWarmingUp.value = true
        scope.launch(Dispatchers.IO) {
            repository.startNeonSync()
            _isWarmingUp.value = false
        }
    }

    val currentShopEmployees: StateFlow<List<Employee>> = _selectedShop.filterNotNull()
        .flatMapLatest { repository.getShopEmployees(it.shopId) }
        .stateIn(scope, SharingStarted.WhileSubscribed(60000), emptyList())

    private val _refreshRequested = MutableSharedFlow<Unit>(replay = 0)
    val refreshRequested = _refreshRequested.asSharedFlow()

    fun triggerDashboardRefresh() {
        _refreshRequested.tryEmit(Unit)
    }

    suspend fun insertAttendance(attendance: Attendance) = repository.insertAttendance(attendance)
    suspend fun deleteAttendance(attendance: Attendance) = repository.deleteAttendance(attendance)

    suspend fun insertPunch(punch: AttendancePunch) = repository.insertPunch(punch)
    suspend fun deletePunch(punch: AttendancePunch) = repository.deletePunch(punch)

    fun setSelectedShop(shop: Shop) {
        _selectedShop.value = shop
    }

    fun clearShopData() {
        // No cached shop data to clear anymore
    }
}
