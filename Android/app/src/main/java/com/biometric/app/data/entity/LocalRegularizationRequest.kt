package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_regularization_requests")
data class LocalRegularizationRequest(
    @PrimaryKey val id: String,
    val staffId: String,
    val staffName: String,
    val date: String,
    val punchType: String,
    val originalTime: Long?,
    val requestedTime: Long,
    val reason: String,
    val status: String,
    val adminRemarks: String?,
    val submittedAt: Long,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
