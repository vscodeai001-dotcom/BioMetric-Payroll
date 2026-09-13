package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_resignation_requests")
data class LocalResignationRequest(
    @PrimaryKey val requestId: String,
    val employeeId: String,
    val submissionDate: Long,
    val desiredLastWorkingDay: Long,
    val reason: String?,
    val status: String,
    val approvedLastWorkingDay: Long?,
    val adminRemarks: String?,
    val isSettled: Boolean,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
