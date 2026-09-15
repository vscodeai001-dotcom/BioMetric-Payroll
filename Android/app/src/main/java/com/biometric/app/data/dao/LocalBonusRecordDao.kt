package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalBonusRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalBonusRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: LocalBonusRecord)

    @Query("SELECT * FROM local_bonus_records ORDER BY bonusDate DESC")
    fun getAllFlow(): Flow<List<LocalBonusRecord>>

    @Query("SELECT * FROM local_bonus_records WHERE syncState = 0")
    fun getUnsynced(): List<LocalBonusRecord>

    @Query("DELETE FROM local_bonus_records WHERE bonusId = :id")
    fun deleteById(id: Int)
}
