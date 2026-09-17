package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalShiftSchedule
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalShiftScheduleDao {
    @Query("SELECT * FROM shift_schedules ORDER BY shiftDate DESC")
    fun getAllFlow(): Flow<List<LocalShiftSchedule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalShiftSchedule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalShiftSchedule>)

    @Query("DELETE FROM shift_schedules WHERE scheduleId = :id")
    fun deleteById(id: Int)

    @Query("SELECT * FROM shift_schedules WHERE employeeId = :employeeId AND shiftDate BETWEEN :fromDate AND :toDate ORDER BY shiftDate ASC, scheduleId ASC")
    suspend fun getForEmployeeBetween(employeeId: Int, fromDate: String, toDate: String): List<LocalShiftSchedule>

    @Query("SELECT * FROM shift_schedules WHERE employeeId = :employeeId AND shiftDate BETWEEN :fromDate AND :toDate ORDER BY shiftDate ASC, scheduleId ASC")
    fun observeForEmployeeBetween(employeeId: Int, fromDate: String, toDate: String): Flow<List<LocalShiftSchedule>>
    @Query("SELECT * FROM shift_schedules WHERE employeeId = :employeeId AND (shiftDate BETWEEN :fromDate AND :toDate OR isRecurringPattern = 1) ORDER BY shiftDate ASC, scheduleId ASC")
    suspend fun getForEmployeeWithPatterns(employeeId: Int, fromDate: String, toDate: String): List<LocalShiftSchedule>

    @Query("SELECT * FROM shift_schedules WHERE employeeId = :employeeId AND (shiftDate BETWEEN :fromDate AND :toDate OR isRecurringPattern = 1) ORDER BY shiftDate ASC, scheduleId ASC")
    fun observeForEmployeeWithPatterns(employeeId: Int, fromDate: String, toDate: String): Flow<List<LocalShiftSchedule>>

}
