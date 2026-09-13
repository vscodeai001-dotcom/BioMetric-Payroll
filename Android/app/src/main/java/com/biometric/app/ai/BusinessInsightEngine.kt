package com.biometric.app.ai

import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.Attendance
import java.util.*

object BusinessInsightEngine {

    fun analyze(intent: AiIntent, bundle: WorkforceDataBundle): List<BusinessInsight> {
        val insights = mutableListOf<BusinessInsight>()
        val employees = bundle.employees
        val attendance = bundle.attendance

        // 1. Attendance Regularity
        if (intent == AiIntent.ATTENDANCE_QUERY || intent == AiIntent.GENERAL) {
            val attendanceByEmp = attendance.groupBy { it.employeeId }
            val stats = employees.map { emp ->
                val presentDays = attendanceByEmp[emp.employeeId]?.distinctBy { 
                    val cal = Calendar.getInstance().apply { timeInMillis = it.checkInTime }
                    "${cal[Calendar.YEAR]}_${cal[Calendar.DAY_OF_YEAR]}"
                }?.size ?: 0
                emp.name to presentDays
            }

            val totalDays = 30 // Simplified month window
            val irregular = stats.filter { it.second > 0 && it.second < (totalDays * 0.8) }

            if (irregular.isNotEmpty()) {
                insights.add(BusinessInsight(
                    title = "Irregular Attendance",
                    description = "${irregular.size} staff members have attendance below 80% this period.",
                    impact = ImpactLevel.NEGATIVE,
                    trend = TrendDirection.DOWN,
                    recommendation = "Review attendance with: ${irregular.joinToString { it.first }}"
                ))
            } else if (employees.isNotEmpty()) {
                insights.add(BusinessInsight(
                    title = "Perfect Attendance",
                    description = "All active staff members are maintaining high regularity! 🌟",
                    impact = ImpactLevel.POSITIVE,
                    trend = TrendDirection.STABLE
                ))
            }
        }

        // 2. Pending Regularizations
        val pendingRegs = bundle.regularizations.count { it.status == "Pending" }
        if (pendingRegs > 0) {
            insights.add(BusinessInsight(
                title = "Pending Actions",
                description = "There are $pendingRegs attendance regularization requests awaiting your approval.",
                impact = ImpactLevel.NEUTRAL,
                trend = TrendDirection.STABLE,
                recommendation = "Visit the Approvals hub to clear these requests."
            ))
        }

        return insights
    }
}
