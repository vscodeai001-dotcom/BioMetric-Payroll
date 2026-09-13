package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalAttendance
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(attendance: LocalAttendance): Long

    @Delete
    fun delete(attendance: LocalAttendance): Int

    @Query("SELECT * FROM local_attendance")
    fun getAll(): List<LocalAttendance>

    @Query("SELECT * FROM local_attendance")
    fun getAllFlow(): Flow<List<LocalAttendance>>

    @Query("SELECT * FROM local_attendance WHERE attendanceId = :id")
    fun getById(id: String): LocalAttendance?

    @Query("SELECT * FROM local_attendance WHERE syncState = 0")
    fun getUnsynced(): List<LocalAttendance>
}
