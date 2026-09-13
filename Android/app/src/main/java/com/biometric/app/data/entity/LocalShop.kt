package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_shops")
data class LocalShop(
    @PrimaryKey
    val shopId: String,
    val name: String,
    val location: String,
    val openingDate: Long,
    val isActive: Boolean,
    val syncState: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)
