package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalPayrollHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalPayrollHistoryDao {
    @Query("SELECT * FROM payroll_history ORDER BY payYear DESC, payMonth DESC")
    fun getAllFlow(): Flow<List<LocalPayrollHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalPayrollHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalPayrollHistory>)

    @Query("DELETE FROM payroll_history WHERE payrollId = :id")
    fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM payroll_history WHERE employeeId = :employeeId AND payYear = :year AND payMonth = :month")
    suspend fun countLocked(employeeId: Int, year: Int, month: Int): Int
}
