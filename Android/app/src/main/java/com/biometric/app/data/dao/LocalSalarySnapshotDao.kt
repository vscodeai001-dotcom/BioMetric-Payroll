package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalSalarySnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSalarySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalSalarySnapshot): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalSalarySnapshot>): LongArray

    @Query("SELECT * FROM local_salary_snapshots")
    fun getAllFlow(): Flow<List<LocalSalarySnapshot>>

    @Query("SELECT * FROM local_salary_snapshots WHERE syncState = 0")
    fun getUnsynced(): List<LocalSalarySnapshot>

    @Query("DELETE FROM local_salary_snapshots WHERE snapshotId = :id")
    fun deleteById(id: String)
}
