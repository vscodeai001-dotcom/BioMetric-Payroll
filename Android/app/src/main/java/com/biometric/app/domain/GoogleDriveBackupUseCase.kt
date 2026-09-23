package com.biometric.app.domain

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.biometric.app.data.FullBackupData
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.*
import com.biometric.app.sync.FirebaseSyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.*
import javax.inject.Inject

class GoogleDriveBackupUseCase @Inject constructor(
    private val repository: MainRepository,
    private val firebaseSync: FirebaseSyncManager,
) {
    suspend fun generateBackupJson(): String = withContext(Dispatchers.Default) {
        val backup = FullBackupData(
            timestamp = System.currentTimeMillis(),
            version = 3,
            shops = repository.allShopsFlow.first(),
            employees = repository.allEmployeesFlow.first(),
            attendance = repository.allAttendanceFlow.first(),
            advances = repository.allAdvancesFlow.first(),
            closedDays = repository.allClosedDaysFlow.first(),
            employeeHistory = repository.allHistoryFlow.first(),
            auditLogs = emptyList(),
            salaryPayments = emptyList(),
            reminders = emptyList(),
            recycleBin = emptyList()
        )
        GsonBuilder().setPrettyPrinting().create().toJson(backup)
    }

    suspend fun restoreFromJson(json: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val gson = Gson()
            val backup = gson.fromJson(json, FullBackupData::class.java) ?: return@withContext Result.failure(Exception("Failed to parse JSON backup"))

            val tablesToClear = listOf(
                "shops", "employees", "attendance", "advance_payments", 
                "shop_closed_days", "employee_history", "audit_logs", "salary_payments",
                "reminders", "recycle_bin"
            )
            tablesToClear.forEach { firebaseSync.clearTable(it) }

            val updates = mutableMapOf<String, Any?>()
            backup.shops.filter { it.shopId.isNotBlank() }.forEach { updates["shops/${it.shopId}"] = it }
            backup.employees.filter { it.employeeId.isNotBlank() }.forEach { updates["employees/${it.employeeId}"] = it }
            
            backup.attendance.forEach { 
                if (it.attendanceId.isBlank()) it.attendanceId = UUID.randomUUID().toString()
                updates["attendance/${it.attendanceId}"] = it 
            }
            backup.advances.forEach { 
                if (it.advanceId.isBlank()) it.advanceId = UUID.randomUUID().toString()
                updates["advance_payments/${it.advanceId}"] = it 
            }
            backup.closedDays.forEach { 
                if (it.id.isBlank()) it.id = UUID.randomUUID().toString()
                updates["shop_closed_days/${it.id}"] = it 
            }
            backup.employeeHistory.forEach { 
                if (it.historyId.isBlank()) it.historyId = UUID.randomUUID().toString()
                updates["employee_history/${it.historyId}"] = it 
            }
            backup.auditLogs.forEach {
                if (it.logId.isBlank()) it.logId = UUID.randomUUID().toString()
                updates["audit_logs/${it.logId}"] = it
            }
            backup.salaryPayments.forEach {
                if (it.paymentId.isBlank()) it.paymentId = UUID.randomUUID().toString()
                updates["salary_payments/${it.paymentId}"] = it
            }
            backup.reminders.forEach {
                if (it.reminderId.isBlank()) it.reminderId = UUID.randomUUID().toString()
                updates["reminders/${it.reminderId}"] = it
            }
            backup.recycleBin.forEach {
                if (it.id.isBlank()) it.id = UUID.randomUUID().toString()
                updates["recycle_bin/${it.id}"] = it
            }

            firebaseSync.bulkRestore(updates)
            firebaseSync.clearSnapshotsAndSummaries()

            val msg = "Recovered: ${backup.shops.size} shops, ${backup.employees.size} staff"
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
