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

    @Query("SELECT * FROM offline_tracking_events WHERE syncState != 'SYNCED' ORDER BY eventTime ASC")
    suspend fun getPendingSync(): List<OfflineTrackingEvent>

    @Query("UPDATE offline_tracking_events SET syncState = :state, syncedAt = :syncedAt WHERE eventId = :eventId")
    suspend fun markSynced(eventId: String, state: String, syncedAt: Long)

    @Query("SELECT COUNT(*) FROM offline_tracking_events WHERE syncState != 'SYNCED'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM offline_tracking_events")
    suspend fun count(): Int

    @Query("DELETE FROM offline_tracking_events WHERE eventTime < :cutoff")
    suspend fun pruneBefore(cutoff: Long): Int
}
