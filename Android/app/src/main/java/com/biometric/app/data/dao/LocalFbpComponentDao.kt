package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalFbpComponent
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFbpComponentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(component: LocalFbpComponent)

    @Query("SELECT * FROM local_fbp_components WHERE isActive = 1")
    fun getActiveFlow(): Flow<List<LocalFbpComponent>>

    @Query("SELECT * FROM local_fbp_components WHERE syncState = 0")
    fun getUnsynced(): List<LocalFbpComponent>
}
