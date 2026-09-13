package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalResignationRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalResignationRequestDao {
    @Query("SELECT * FROM local_resignation_requests ORDER BY submissionDate DESC")
    fun getAllFlow(): Flow<List<LocalResignationRequest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(request: LocalResignationRequest): Long

    @Delete
    fun delete(request: LocalResignationRequest): Int

    @Query("SELECT * FROM local_resignation_requests WHERE syncState = 0")
    fun getUnsynced(): List<LocalResignationRequest>
}
