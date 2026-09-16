package com.biometric.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_tracking_events",
    indices = [
        Index(value = ["eventTime"]),
        Index(value = ["sessionId", "eventTime"]),
        Index(value = ["syncState", "eventTime"])
    ]
)
data class OfflineTrackingEvent(
    @PrimaryKey val eventId: String,
    val eventTime: Long,
    val eventType: String,
    val severity: String,
    val message: String,
    val sessionId: String?,
    val networkAvailable: Boolean,
    val queueDepth: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val correlationId: String? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val syncState: String = SYNC_PENDING,
    val syncedAt: Long? = null
) {
    companion object {
        const val SYNC_PENDING = "PENDING"
        const val SYNC_IN_FLIGHT = "IN_FLIGHT"
        const val SYNCED = "SYNCED"
        const val SYNC_FAILED = "FAILED"
        
        // Event Types
        const val BOUNDARY_BREACH = "BOUNDARY_BREACH"
    }
}
