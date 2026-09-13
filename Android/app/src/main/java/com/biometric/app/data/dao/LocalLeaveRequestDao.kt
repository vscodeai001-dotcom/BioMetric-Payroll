package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalLeaveRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalLeaveRequestDao {
    @Query("SELECT * FROM local_leave_requests ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<LocalLeaveRequest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(request: LocalLeaveRequest): Long

    @Delete
    fun delete(request: LocalLeaveRequest): Int

    @Query("SELECT * FROM local_leave_requests WHERE syncState = 0")
    fun getUnsynced(): List<LocalLeaveRequest>
}
