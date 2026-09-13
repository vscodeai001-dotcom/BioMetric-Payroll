package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalEmployee
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalEmployeeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(employee: LocalEmployee): Long

    @Delete
    fun delete(employee: LocalEmployee): Int

    @Query("SELECT * FROM local_employees")
    fun getAll(): List<LocalEmployee>

    @Query("SELECT * FROM local_employees")
    fun getAllFlow(): Flow<List<LocalEmployee>>

    @Query("SELECT * FROM local_employees WHERE employeeId = :id")
    fun getById(id: String): LocalEmployee?

    @Query("SELECT * FROM local_employees WHERE syncState = 0")
    fun getUnsynced(): List<LocalEmployee>
}
