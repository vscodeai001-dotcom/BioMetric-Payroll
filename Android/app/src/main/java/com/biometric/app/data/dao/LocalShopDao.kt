package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalShop
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalShopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(shop: LocalShop): Long

    @Query("SELECT * FROM local_shops WHERE isActive = 1")
    fun getAllShops(): Flow<List<LocalShop>>

    @Query("SELECT * FROM local_shops WHERE shopId = :id")
    fun getById(id: String): LocalShop?

    @Query("SELECT * FROM local_shops WHERE syncState = 0")
    fun getUnsynced(): List<LocalShop>
}
