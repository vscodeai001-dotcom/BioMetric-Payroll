package com.biometric.app.ai

import com.biometric.app.data.MainRepository
import kotlinx.coroutines.flow.first
import java.util.*

class BusinessContextBuilder(private val repository: MainRepository) {

    suspend fun build(shopId: String?, start: Long, end: Long, periodLabel: String): WorkforceDataBundle {
        val currentShopId = shopId ?: ""
        
        val employees = repository.getShopEmployees(shopId).first()
        val attendance = repository.getAttendanceForPeriod(shopId, start, end).first()
        val regularizations = repository.allRegularizationsFlow.first()

        val extraContext = mutableMapOf<String, Any>()
        if (currentShopId.isNotEmpty()) {
            extraContext["salary_liability"] = repository.calculateProjectedSalary(currentShopId, start, end)
        }

        return WorkforceDataBundle(
            employees = employees,
            attendance = attendance,
            regularizations = regularizations,
            periodLabel = periodLabel,
            extraContext = extraContext
        )
    }
}
