package com.biometric.app.data

import androidx.room.*

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(location: LocalLocation): Long

    @Query("SELECT * FROM offline_locations WHERE syncState != 'SYNCED' ORDER BY timestamp ASC, id ASC")
    suspend fun getAllQueued(): List<LocalLocation>

    @Query("UPDATE offline_locations SET syncState = 'PENDING' WHERE syncState = 'IN_FLIGHT' AND lastAttemptAt IS NOT NULL AND lastAttemptAt < :cutoff")
    suspend fun recoverStaleInFlight(cutoff: Long): Int

    @Query("SELECT * FROM offline_locations WHERE syncState IN ('PENDING','FAILED') ORDER BY timestamp ASC, id ASC")
    suspend fun getPendingForSync(): List<LocalLocation>

    @Query("SELECT * FROM offline_locations WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getSessionLocations(sessionId: String): List<LocalLocation>

    @Query("SELECT * FROM offline_locations ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LocalLocation>

    @Query("SELECT * FROM offline_locations WHERE syncState = 'SYNCED' ORDER BY syncedAt DESC, id DESC LIMIT 1")
    suspend fun getLastSynced(): LocalLocation?

    @Query("SELECT COUNT(*) FROM offline_locations WHERE syncState != 'SYNCED'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM offline_locations")
    suspend fun getTotalCount(): Int

    @Query("UPDATE offline_locations SET syncState = :state, attemptCount = :attemptCount, lastAttemptAt = :attemptAt, lastError = :error WHERE id = :id")
    suspend fun markAttempt(id: Int, state: String, attemptCount: Int, attemptAt: Long, error: String?)

    @Query("UPDATE offline_locations SET syncState = 'SYNCED', syncedAt = :syncedAt, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: Int, syncedAt: Long)

    @Query("UPDATE offline_locations SET sessionId = :sessionId WHERE id = :id")
    suspend fun rebindSession(id: Int, sessionId: String)

    @Query("DELETE FROM offline_locations WHERE syncState = 'SYNCED' AND timestamp < :cutoff")
    suspend fun pruneSyncedBefore(cutoff: Long): Int

    @Query("DELETE FROM offline_locations")
    suspend fun clearAll()
}
