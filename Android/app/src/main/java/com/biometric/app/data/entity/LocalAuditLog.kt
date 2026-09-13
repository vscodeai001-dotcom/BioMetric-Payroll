package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_audit_logs")
data class LocalAuditLog(
    @PrimaryKey val logId: String,
    val shopId: String,
    val action: String,
    val module: String,
    val oldValue: String?,
    val newValue: String?,
    val userDisplayName: String,
    val userId: String,
    val timestamp: Long,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
