package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.DatabaseManager
import com.biometric.app.data.dao.*
import com.biometric.app.data.entity.*
import com.biometric.app.api.ApiService
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeonSyncManager @Inject constructor(
    private val employeeDao: LocalEmployeeDao,
    private val attendanceDao: LocalAttendanceDao,
    private val attendancePunchDao: LocalAttendancePunchDao,
    private val advanceDao: LocalAdvancePaymentDao,
    private val regularizationDao: LocalRegularizationRequestDao,
    private val auditLogDao: LocalAuditLogDao,
    private val dailySummaryDao: LocalDailySummaryDao,
    private val shiftScheduleDao: LocalShiftScheduleDao,
    private val payrollHistoryDao: LocalPayrollHistoryDao,
    private val apiService: ApiService,
    private val resignationDao: LocalResignationRequestDao,
    private val leaveDao: LocalLeaveRequestDao
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "NeonSyncManager"
        private const val DEFAULT_SHOP_ID = "NEON_SSOT"
    }

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting SSOT Sync via JDBC...")
        
        val conn = try {
            DatabaseManager.getConnection()
        } catch (t: Throwable) {
            Log.e(TAG, "Sync Connection Error: ${t.message}")
            null
        } ?: return@withContext

        try {
            syncEmployees(conn)
            syncAttendance(conn)
            syncAdvances(conn)
            syncRegularizations(conn)
            syncAuditLogs(conn)
            syncDailySummaries(conn)
            syncShiftSchedules(conn)
            syncPayrollHistories(conn)
            
            // Push local changes to PostgreSQL
            pushLocalChanges(conn)
            
            Log.d(TAG, "SSOT Sync completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Sync process error: ${e.message}")
            e.printStackTrace()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun syncEmployees(conn: Connection) {
        Log.d(TAG, "Syncing Employees...")
        conn.prepareStatement("SELECT * FROM employees WHERE is_deleted = false").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val employeeId = rs.getInt("employeeid").toString()
                    val name = rs.getString("name") ?: "Unknown"
                    val role = rs.getString("role") ?: "Staff"
                    
                    employeeDao.upsert(LocalEmployee(
                        employeeId = employeeId,
                        shopId = DEFAULT_SHOP_ID,
                        name = name,
                        role = role,
                        isActive = true,
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncAttendance(conn: Connection) {
        Log.d(TAG, "Syncing Attendance Logs (Dual Mapping)...")
        conn.prepareStatement("SELECT * FROM attendancelogs").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val logId = rs.getInt("logid").toString()
                    val employeeId = rs.getInt("employeeid").toString()
                    val punchTime = rs.getTimestamp("punchtime")?.time ?: 0L
                    val deviceId = rs.getString("DeviceId") ?: ""
                    val logType = rs.getString("LogType") ?: "Punch"
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(punchTime))
                    
                    // 1. Map to raw AttendancePunch (For Correction Tool)
                    attendancePunchDao.upsert(LocalAttendancePunch(
                        punchId = logId,
                        staffId = employeeId,
                        date = dateStr,
                        type = if (logType.contains("IN", true)) "IN" else "OUT",
                        timestamp = punchTime,
                        latitude = rs.getDouble("latitude"),
                        longitude = rs.getDouble("longitude"),
                        accuracy = 0f,
                        source = deviceId,
                        status = "APPROVED",
                        syncState = 1
                    ))

                    // 2. Map to Attendance (For Salary Engine Compatibility)
                    // Logic: Each raw log becomes a checkIn only record. 
                    // The SalaryEngine will handle pairing if needed or treat as single entries.
                    attendanceDao.upsert(LocalAttendance(
                        attendanceId = "SQL-$logId",
                        employeeId = employeeId,
                        checkInTime = punchTime,
                        checkOutTime = null, 
                        syncState = 1,
                        lastModified = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    private fun syncAdvances(conn: Connection) {
        Log.d(TAG, "Syncing Salary Advances...")
        conn.prepareStatement("SELECT * FROM salaryadvances").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val advanceId = rs.getInt("advanceid").toString()
                    val employeeId = rs.getInt("employeeid").toString()
                    val amount = rs.getDouble("amount")
                    val date = rs.getDate("advancedate")?.time ?: 0L
                    val isRecovered = rs.getObject("payrollid_paid") != null
                    
                    advanceDao.upsert(LocalAdvancePayment(
                        advanceId = advanceId,
                        employeeId = employeeId,
                        shopId = DEFAULT_SHOP_ID,
                        amount = amount,
                        date = date,
                        isRecovered = isRecovered,
                        recoveryPaymentId = rs.getObject("payrollid_paid")?.toString(),
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncRegularizations(conn: Connection) {
        Log.d(TAG, "Syncing Regularizations...")
        conn.prepareStatement("SELECT * FROM attendance_regularizations").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getInt("regularization_id").toString()
                    val employeeId = rs.getInt("employee_id").toString()
                    val reason = rs.getString("reason") ?: ""
                    val status = rs.getString("status") ?: "Pending"
                    val dateStr = rs.getDate("date_of_punch")?.toString() ?: ""
                    val submittedAt = rs.getTimestamp("submission_date")?.time ?: 0L
                    
                    regularizationDao.upsert(LocalRegularizationRequest(
                        id = id,
                        staffId = employeeId,
                        staffName = "Remote User", 
                        date = dateStr,
                        punchType = if (rs.getBoolean("is_in_punch")) "IN" else "OUT",
                        originalTime = null,
                        requestedTime = submittedAt, 
                        reason = reason,
                        status = status,
                        adminRemarks = rs.getString("admin_remarks"),
                        submittedAt = submittedAt,
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncAuditLogs(conn: Connection) {
        Log.d(TAG, "Syncing Audit Logs...")
        conn.prepareStatement("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 50").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val logId = rs.getLong("logid").toString()
                    val action = rs.getString("action_type") ?: ""
                    val module = rs.getString("entity_type") ?: ""
                    val timestamp = rs.getTimestamp("timestamp")?.time ?: 0L
                    
                    auditLogDao.upsert(LocalAuditLog(
                        logId = logId,
                        shopId = DEFAULT_SHOP_ID,
                        action = action,
                        module = module,
                        oldValue = rs.getString("entity_id"),
                        newValue = rs.getString("details"),
                        userDisplayName = rs.getString("user_email") ?: "System",
                        userId = rs.getString("user_id") ?: "0",
                        timestamp = timestamp,
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncDailySummaries(conn: Connection) {
        Log.d(TAG, "Syncing Daily Summaries...")
        conn.prepareStatement("SELECT * FROM daily_summaries").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    dailySummaryDao.upsert(LocalDailySummary(
                        summaryId = rs.getInt("summaryid"),
                        employeeId = rs.getInt("employeeid"),
                        shiftDate = rs.getDate("shiftdate")?.toString() ?: "",
                        status = rs.getString("status") ?: "Absent",
                        earnedStandardHours = rs.getDouble("earned_standard_hours"),
                        totalOvertimeMs = parseIntervalToMs(rs.getString("total_overtime_duration")),
                        totalPenaltyMs = parseIntervalToMs(rs.getString("total_penalty_duration")),
                        totalLatenessMs = parseIntervalToMs(rs.getString("total_lateness")),
                        totalBreakPenaltyMs = parseIntervalToMs(rs.getString("total_break_penalty")),
                        scheduledShiftDurationMs = parseIntervalToMs(rs.getString("scheduled_shift_duration")),
                        shiftAllowanceEarned = rs.getDouble("shift_allowance_earned"),
                        isManualOverride = rs.getBoolean("is_manual_override"),
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncShiftSchedules(conn: Connection) {
        Log.d(TAG, "Syncing Shift Schedules...")
        conn.prepareStatement("SELECT * FROM shiftschedules").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    shiftScheduleDao.upsert(LocalShiftSchedule(
                        scheduleId = rs.getInt("scheduleid"),
                        employeeId = rs.getInt("employeeid"),
                        shiftDate = rs.getDate("shiftdate")?.toString() ?: "",
                        startTime = rs.getTime("starttime")?.toString() ?: "00:00:00",
                        endTime = rs.getTime("endtime")?.toString() ?: "00:00:00",
                        isRecurringPattern = rs.getBoolean("is_recurring_pattern"),
                        patternDurationDays = rs.getInt("pattern_duration_days"),
                        appliesToDayOfWeek = rs.getInt("applies_to_day_of_week"),
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun syncPayrollHistories(conn: Connection) {
        Log.d(TAG, "Syncing Payroll Histories...")
        conn.prepareStatement("SELECT * FROM payrollhistory").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    payrollHistoryDao.upsert(LocalPayrollHistory(
                        payrollId = rs.getInt("payrollid"),
                        employeeId = rs.getInt("employeeid"),
                        payMonth = rs.getInt("paymonth"),
                        payYear = rs.getInt("payyear"),
                        baseSalary = rs.getDouble("basesalary"),
                        totalHoursWorked = rs.getDouble("totalhoursworked"),
                        overtimePay = rs.getDouble("overtimepay"),
                        deductionsHours = rs.getDouble("deductions_hours"),
                        deductionsAdvance = rs.getDouble("deductions_advance"),
                        bonus = rs.getDouble("Bonus"),
                        netSalary = rs.getDouble("netsalary"),
                        manualLeaveDays = rs.getInt("manualleavedays"),
                        absentDays = rs.getInt("absentdays"),
                        totalPenaltyMs = parseIntervalToMs(rs.getString("totalpenaltyduration")),
                        totalOvertimeMs = parseIntervalToMs(rs.getString("totalovertimeduration")),
                        hourlyRate = rs.getDouble("HourlyRate"),
                        basicComponent = rs.getDouble("basic_salary_component"),
                        pfDeduction = rs.getDouble("pf_deduction"),
                        esiDeduction = rs.getDouble("esi_deduction"),
                        employerPfContribution = rs.getDouble("employer_pf_contribution"),
                        employerEsiContribution = rs.getDouble("employer_esi_contribution"),
                        ptDeduction = rs.getDouble("pt_deduction"),
                        tdsDeduction = rs.getDouble("tds_deduction"),
                        totalShiftAllowance = rs.getDouble("TotalShiftAllowance"),
                        syncState = 1
                    ))
                }
            }
        }
    }

    private fun pushLocalChanges(conn: Connection) {
        pushRegularizations(conn)
        pushLeaves(conn)
        pushResignations(conn)
        pushAttendancePunches(conn)
        pushAdvances(conn)
        pushEmployees(conn)
    }

    private fun pushEmployees(conn: Connection) {
        val unsynced = employeeDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Employees...")
        
        val sql = """
            UPDATE employees SET name = ?, role = ? WHERE employeeid = ?
        """.trimIndent()
        
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { emp ->
                stmt.setString(1, emp.name)
                stmt.setString(2, emp.role)
                stmt.setInt(3, emp.employeeId.toInt())
                stmt.executeUpdate()
                employeeDao.upsert(emp.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun pushAttendancePunches(conn: Connection) {
        val unsynced = attendancePunchDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Attendance Punches...")
        
        val sql = """
            INSERT INTO attendancelogs (employeeid, biometricid, punchtime, "DeviceID", "LogType", is_approved, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { punch ->
                stmt.setInt(1, punch.staffId.toInt())
                stmt.setString(2, "ANDROID-${punch.staffId}")
                stmt.setTimestamp(3, Timestamp(punch.timestamp))
                stmt.setString(4, punch.source)
                stmt.setString(5, punch.type)
                stmt.setBoolean(6, true)
                stmt.setDouble(7, punch.latitude)
                stmt.setDouble(8, punch.longitude)
                stmt.executeUpdate()
                attendancePunchDao.upsert(punch.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun pushAdvances(conn: Connection) {
        val unsynced = advanceDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Advances...")
        
        val sql = """
            INSERT INTO salaryadvances (employeeid, advancedate, amount, payrollid_paid, advancetype)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { adv ->
                stmt.setInt(1, adv.employeeId.toInt())
                stmt.setDate(2, java.sql.Date(adv.date))
                stmt.setBigDecimal(3, BigDecimal.valueOf(adv.amount))
                stmt.setNull(4, Types.INTEGER) // Since it's unsynced, it's likely not paid
                stmt.setString(5, "Advance")
                stmt.executeUpdate()
                advanceDao.upsert(adv.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun pushRegularizations(conn: Connection) {
        val unsynced = regularizationDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Regularizations...")
        
        val sql = "UPDATE attendance_regularizations SET status = ?, admin_remarks = ? WHERE regularization_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { req ->
                stmt.setString(1, req.status)
                stmt.setString(2, req.adminRemarks)
                stmt.setInt(3, req.id.toInt())
                stmt.executeUpdate()
                regularizationDao.upsert(req.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun pushLeaves(conn: Connection) {
        val unsynced = leaveDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Leave Requests...")
        
        val sql = "UPDATE leaverequests SET \"Status\" = ?, \"AdminNotes\" = ? WHERE leaverequestid = ?"
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { req ->
                stmt.setString(1, req.status)
                stmt.setString(2, req.adminNotes)
                stmt.setInt(3, req.id.toInt())
                stmt.executeUpdate()
                leaveDao.upsert(req.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun pushResignations(conn: Connection) {
        val unsynced = resignationDao.getUnsynced()
        if (unsynced.isEmpty()) return
        Log.d(TAG, "Pushing ${unsynced.size} Resignations...")
        
        val sql = "UPDATE resignation_requests SET status = ?, admin_remarks = ? WHERE request_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            unsynced.forEach { req ->
                stmt.setString(1, req.status)
                stmt.setString(2, req.adminRemarks)
                stmt.setInt(3, req.requestId.toInt())
                stmt.executeUpdate()
                resignationDao.upsert(req.copy(syncState = 1))
            }
        }
        syncScope.launch { apiService.triggerSignalRRefresh() }
    }

    private fun parseIntervalToMs(interval: String?): Long {
        if (interval == null || interval.isBlank()) return 0L
        // Standard Postgres interval format: HH:mm:ss or MM:ss
        // Also can be "X days HH:mm:ss"
        return try {
            var totalMs = 0L
            val daysMatch = Regex("(\\d+)\\s+days?").find(interval)
            if (daysMatch != null) {
                totalMs += daysMatch.groupValues[1].toLong() * 24 * 60 * 60 * 1000
            }
            
            val timePart = interval.split(" ").last()
            val parts = timePart.split(":")
            when (parts.size) {
                3 -> { // HH:mm:ss
                    totalMs += parts[0].toLong() * 60 * 60 * 1000
                    totalMs += parts[1].toLong() * 60 * 1000
                    totalMs += (parts[2].toDouble() * 1000).toLong()
                }
                2 -> { // mm:ss
                    totalMs += parts[0].toLong() * 60 * 1000
                    totalMs += (parts[1].toDouble() * 1000).toLong()
                }
            }
            totalMs
        } catch (_: Exception) { 0L }
    }
}
