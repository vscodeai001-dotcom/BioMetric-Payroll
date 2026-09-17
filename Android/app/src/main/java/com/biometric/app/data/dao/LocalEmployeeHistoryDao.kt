package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalEmployeeHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalEmployeeHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalEmployeeHistory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalEmployeeHistory>): LongArray

    @Delete
    fun delete(item: LocalEmployeeHistory): Int

    @Query("SELECT * FROM local_employee_history")
        fun getAll(): List<LocalEmployeeHistory>
    @Query("SELECT * FROM local_employee_history WHERE employeeId = :employeeId ORDER BY version ASC")
    fun getHistoryByEmployee(employeeId: String): Flow<List<LocalEmployeeHistory>>

    @Query("SELECT * FROM local_employee_history ORDER BY changeDate DESC")
    fun getAllFlow(): Flow<List<LocalEmployeeHistory>>

    @Query("SELECT * FROM local_employee_history WHERE syncState = 0")
    fun getUnsynced(): List<LocalEmployeeHistory>
    @Query("DELETE FROM local_employee_history WHERE historyId = :id")
    fun deleteById(id: String)

}
