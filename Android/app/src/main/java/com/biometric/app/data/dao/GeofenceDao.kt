package com.biometric.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.biometric.app.data.entity.GeofenceLocation

@Dao
interface GeofenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(geofences: List<GeofenceLocation>)

    @Query("SELECT * FROM geofences WHERE isActive = 1")
    suspend fun getAllActive(): List<GeofenceLocation>

    @Query("DELETE FROM geofences")
    suspend fun clearAll()
}
