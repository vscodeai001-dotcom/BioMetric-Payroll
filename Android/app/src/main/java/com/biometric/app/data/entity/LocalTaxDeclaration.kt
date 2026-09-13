package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_tax_declarations")
data class LocalTaxDeclaration(
    @PrimaryKey val declarationId: Int,
    val employeeId: Int,
    val financialYear: Int,
    val regime: String,
    val section80C: Double,
    val section80D: Double,
    val hraRentPaid: Double,
    val otherExemptions: Double,
    val status: String,
    val adminRemarks: String?,
    val submissionDate: Long,
    val approvalDate: Long?,
    val syncState: Int = 0
)
