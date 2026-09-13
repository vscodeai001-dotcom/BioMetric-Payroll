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
}
