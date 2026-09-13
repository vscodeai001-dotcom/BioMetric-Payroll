package com.biometric.app.data

import androidx.room.*

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(location: LocalLocation)

    @Query("SELECT * FROM offline_locations ORDER BY timestamp ASC")
    fun getAllQueued(): List<LocalLocation>

    @Query("DELETE FROM offline_locations WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM offline_locations")
    fun clearAll()
}
