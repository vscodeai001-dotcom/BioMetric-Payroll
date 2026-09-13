package com.biometric.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.data.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportRepository: ReportRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _reportData = MutableStateFlow<List<Any>?>(null)
    val reportData = _reportData.asStateFlow()

    fun generateReport(reportType: String, startDate: String, endDate: String, year: Int, month: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _reportData.value = null
            try {
                _reportData.value = when (reportType) {
                    "ATTENDANCE_MONTHLY_SUMMARY" -> reportRepository.generateConsolidatedAttendance(startDate, endDate)
                    "PAYROLL_VARIANCE" -> reportRepository.generatePayrollVariance(year, month)
                    "FINANCIAL_REGISTER" -> reportRepository.generateFinancialRegister(year, month)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                _reportData.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
