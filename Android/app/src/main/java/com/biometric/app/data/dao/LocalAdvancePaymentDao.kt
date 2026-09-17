package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalAdvancePayment
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAdvancePaymentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: LocalAdvancePayment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<LocalAdvancePayment>): LongArray

    @Delete
    fun delete(item: LocalAdvancePayment): Int

    @Query("SELECT * FROM local_advance_payments")
        fun getAll(): List<LocalAdvancePayment>
    @Query("SELECT * FROM local_advance_payments ORDER BY date DESC")
    fun getAllFlow(): Flow<List<LocalAdvancePayment>>

    @Query("SELECT * FROM local_advance_payments WHERE syncState = 0")
    fun getUnsynced(): List<LocalAdvancePayment>
    @Query("DELETE FROM local_advance_payments WHERE advanceId = :id")
    fun deleteById(id: String)

}
