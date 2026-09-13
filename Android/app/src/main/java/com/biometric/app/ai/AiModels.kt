package com.biometric.app.ai

import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.RegularizationRequest
import java.io.Serializable

enum class AiIntent {
    ATTENDANCE_QUERY,
    SALARY_QUERY,
    LEAVE_QUERY,
    PUNCTUALITY_QUERY,
    STAFF_PERFORMANCE,
    TREND_ANALYSIS,
    HELP,
    GENERAL,
}

data class WorkforceDataBundle(
    val employees: List<Employee>,
    val attendance: List<Attendance>,
    val regularizations: List<RegularizationRequest> = emptyList(),
    val periodLabel: String,
    val extraContext: Map<String, Any> = emptyMap()
) : Serializable

data class BusinessInsight(
    val title: String,
    val description: String,
    val impact: ImpactLevel,
    val trend: TrendDirection,
    val recommendation: String? = null,
    val reason: String? = null
) : Serializable

enum class ImpactLevel { HIGH, POSITIVE, NEGATIVE, NEUTRAL }
enum class TrendDirection { UP, DOWN, STABLE }
