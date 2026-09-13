package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalRegularizationRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalRegularizationRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalRegularizationRequest): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalRegularizationRequest>): LongArray

    @Delete
    fun delete(item: LocalRegularizationRequest): Int

    @Query("SELECT * FROM local_regularization_requests ORDER BY submittedAt DESC")
    fun getAllFlow(): Flow<List<LocalRegularizationRequest>>

    @Query("SELECT * FROM local_regularization_requests WHERE syncState = 0")
    fun getUnsynced(): List<LocalRegularizationRequest>
}
