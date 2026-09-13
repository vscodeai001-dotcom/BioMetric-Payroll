package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_employees")
data class LocalEmployee(
    @PrimaryKey val employeeId: String,
    val shopId: String,
    val name: String,
    val role: String,
    val isActive: Boolean,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
