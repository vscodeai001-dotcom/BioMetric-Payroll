package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_fbp_declarations")
data class LocalFbpDeclaration(
    @PrimaryKey val declarationId: Int,
    val employeeId: Int,
    val financialYear: Int,
    val componentName: String,
    val annualAllocatedAmount: Double,
    val monthlyAllocatedAmount: Double,
    val status: String,
    val submissionDate: Long,
    val isActive: Boolean,
    val adminRemarks: String?,
    val syncState: Int = 0
)
