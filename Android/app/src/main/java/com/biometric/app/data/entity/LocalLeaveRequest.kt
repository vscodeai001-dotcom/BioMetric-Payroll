package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_leave_requests")
data class LocalLeaveRequest(
    @PrimaryKey val id: String,
    val staffId: String,
    val staffName: String,
    val leaveType: String,
    val startDate: Long,
    val endDate: Long,
    val reason: String,
    val status: String,
    val adminNotes: String?,
    val isHalfDay: Boolean,
    val createdAt: Long,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
