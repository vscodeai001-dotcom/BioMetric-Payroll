package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalAttendancePunch
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAttendancePunchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalAttendancePunch): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalAttendancePunch>): LongArray

    @Delete
    fun delete(item: LocalAttendancePunch): Int

    @Query("SELECT * FROM local_attendance_punches")
        fun getAll(): List<LocalAttendancePunch>
    @Query("SELECT * FROM local_attendance_punches ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<LocalAttendancePunch>>

    @Query("SELECT * FROM local_attendance_punches WHERE syncState = 0")
    fun getUnsynced(): List<LocalAttendancePunch>
    @Query("DELETE FROM local_attendance_punches WHERE punchId = :id")
    fun deleteById(id: String)

}
