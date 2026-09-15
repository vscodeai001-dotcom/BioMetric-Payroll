package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalAuditLog
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalAuditLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalAuditLog>): LongArray

    @Query("SELECT * FROM local_audit_logs ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<LocalAuditLog>>

    @Query("SELECT * FROM local_audit_logs WHERE syncState = 0")
    fun getUnsynced(): List<LocalAuditLog>

    @Query("DELETE FROM local_audit_logs WHERE logId = :id")
    fun deleteById(id: String)
}
