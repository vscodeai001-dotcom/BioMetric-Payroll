package com.biometric.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.biometric.app.data.entity.OfflineTrackingEvent

@Dao
interface OfflineTrackingEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: OfflineTrackingEvent)

    @Query("SELECT * FROM offline_tracking_events ORDER BY eventTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OfflineTrackingEvent>

    @Query("SELECT COUNT(*) FROM offline_tracking_events")
    suspend fun count(): Int

    @Query("DELETE FROM offline_tracking_events WHERE eventTime < :cutoff")
    suspend fun pruneBefore(cutoff: Long): Int
}
