package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalShopClosedDay
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalShopClosedDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalShopClosedDay): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalShopClosedDay>): LongArray

    @Delete
    fun delete(item: LocalShopClosedDay): Int

    @Query("SELECT * FROM local_shop_closed_days")
        fun getAll(): List<LocalShopClosedDay>
    @Query("SELECT * FROM local_shop_closed_days")
    fun getAllFlow(): Flow<List<LocalShopClosedDay>>

    @Query("SELECT * FROM local_shop_closed_days WHERE syncState = 0")
    fun getUnsynced(): List<LocalShopClosedDay>
    @Query("DELETE FROM local_shop_closed_days WHERE id = :id")
    fun deleteById(id: String)

}
