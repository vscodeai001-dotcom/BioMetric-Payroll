package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalDailySummary
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalDailySummaryDao {
    @Query("SELECT * FROM daily_summaries ORDER BY shiftDate DESC")
    fun getAllFlow(): Flow<List<LocalDailySummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalDailySummary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalDailySummary>)

    @Query("DELETE FROM daily_summaries WHERE summaryId = :id")
    fun deleteById(id: Int)
}
