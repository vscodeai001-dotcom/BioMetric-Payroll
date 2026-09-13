package com.biometric.app.domain

import com.biometric.app.data.MainRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetSalaryReportUseCase @Inject constructor(
    private val repository: MainRepository
) {
    suspend fun execute(
        shopId: String?,
        start: Long,
        end: Long,
        includeBonus: Boolean = true
    ): Double = withContext(Dispatchers.Default) {
        val employees = repository.getShopEmployees(shopId).firstOrNull() ?: emptyList()
        if (employees.isEmpty()) return@withContext 0.0

        var totalStaffLiability = 0.0
        
        coroutineScope {
            val deferredStats = employees.map { employee ->
                async {
                    val stats = repository.getEmployeeStatsFlow(employee, start, end).firstOrNull()
                    stats?.getTotalCost() ?: 0.0
                }
            }
            totalStaffLiability = deferredStats.awaitAll().sum()
        }
        
        totalStaffLiability
    }
}
