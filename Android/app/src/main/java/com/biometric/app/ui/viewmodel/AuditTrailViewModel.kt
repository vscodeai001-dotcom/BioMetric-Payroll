package com.biometric.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.util.DateRangeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class AuditSummary(
    val updates: Int = 0,
    val deletions: Int = 0,
    val restorations: Int = 0,
    val newEntries: Int = 0,
)

data class AuditTrailState(
    val summary: AuditSummary = AuditSummary(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class AuditTrailViewModel @Inject constructor(
    application: Application,
    private val repository: MainRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AuditTrailState())
    val state: StateFlow<AuditTrailState> = _state.asStateFlow()

    private val _pagedLogs = MutableStateFlow<PagingData<AuditLog>>(PagingData.empty())
    val pagedLogs: StateFlow<PagingData<AuditLog>> = _pagedLogs.asStateFlow()

    private var auditJob: Job? = null

    fun loadLogs(shopId: String?, period: String, date: Long, search: String = "") {
        auditJob?.cancel()
        auditJob = viewModelScope.launch(Dispatchers.Default) {
            val range = DateRangeUtil.getRangeForPeriod(period, date)
            val start = range.first
            val end = range.second

            val cacheKey = "audit_summary_${shopId ?: "all"}_${period}_$date"
            val cachedSummary = repository.getAuditSummaryCache(cacheKey)
            
            _state.update { it.copy(summary = cachedSummary ?: AuditSummary(), isLoading = cachedSummary == null) }

            val pagedFlow = repository.getAuditLogsPaged(shopId, start, end, search).cachedIn(viewModelScope)

            _pagedLogs.value = PagingData.empty()

            launch {
                pagedFlow.collect {
                    _pagedLogs.value = it
                }
            }

            launch {
                try {
                    repository.getAuditLogsSummary(shopId, start, end)
                        .catch { e ->
                            android.util.Log.e("AuditTrailVM", "Summary error", e)
                            _state.update { it.copy(isLoading = false) }
                        }
                        .collectLatest { summaryLogs ->
                            val summary = AuditSummary(
                                updates = summaryLogs.count { it.action.equals("UPDATE", ignoreCase = true) || it.action.equals("EDIT", ignoreCase = true) },
                                deletions = summaryLogs.count { it.action.contains("DELETE", ignoreCase = true) || it.action.contains("REJECT", ignoreCase = true) },
                                restorations = summaryLogs.count { it.action.contains("RESTORE", ignoreCase = true) || it.action.contains("APPROVE", ignoreCase = true) },
                                newEntries = summaryLogs.count { it.action.equals("ADD", ignoreCase = true) || it.action.equals("CREATE", ignoreCase = true) || it.action.equals("INSERT", ignoreCase = true) }
                            )
                            repository.saveListCache(cacheKey, listOf(summary))
                            _state.update { it.copy(summary = summary, isLoading = false) }
                        }
                } finally {
                    _state.update { it.copy(isLoading = false) }
                }
            }

            // Safety timeout: Guarantee that isLoading is never stuck as true
            launch {
                delay(2500)
                if (_state.value.isLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }
}
