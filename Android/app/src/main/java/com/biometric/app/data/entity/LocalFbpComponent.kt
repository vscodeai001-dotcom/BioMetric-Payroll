package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_fbp_components")
data class LocalFbpComponent(
    @PrimaryKey val componentId: Int,
    val name: String,
    val maxAnnualLimit: Double,
    val isActive: Boolean,
    val isTaxExempt: Boolean,
    val syncState: Int = 0
)
