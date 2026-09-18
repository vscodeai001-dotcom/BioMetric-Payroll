package com.biometric.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable GPS ledger. Rows are retained after sync so an offline route can be
 * audited/replayed locally; syncState identifies what still needs transmission.
 */
@Entity(
    tableName = "offline_locations",
    indices = [
        Index(value = ["sessionId", "timestamp"]),
        Index(value = ["syncState", "timestamp"]),
        Index(value = ["clientEventId"], unique = true)
    ]
)
data class LocalLocation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(defaultValue = "''") val clientEventId: String,
    val sessionId: String,
    @ColumnInfo(defaultValue = "0") val sequence: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    @ColumnInfo(defaultValue = "0.0") val bearing: Float = 0f,
    val batteryLevel: Int,
    /** Original device GPS timestamp. Never replace this with upload time. */
    val timestamp: Long,
    @ColumnInfo(defaultValue = "0") val capturedElapsedRealtime: Long,
    @ColumnInfo(defaultValue = "'PENDING'") val syncState: String = SYNC_PENDING,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val isOfflineCapture: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    companion object {
        const val SYNC_PENDING = "PENDING"
        const val SYNC_IN_FLIGHT = "IN_FLIGHT"
        const val SYNCED = "SYNCED"
        const val FIREBASE_SYNCED = "FIREBASE_SYNCED"
        const val SYNC_FAILED = "FAILED"
    }
}
