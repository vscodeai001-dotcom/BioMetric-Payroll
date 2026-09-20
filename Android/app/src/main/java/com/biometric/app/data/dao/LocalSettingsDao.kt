package com.biometric.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.biometric.app.data.entity.LocalCompanySettings
import com.biometric.app.data.entity.LocalFeatureSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSettingsDao {
    @Query("SELECT * FROM local_company_settings WHERE id = 1")
    fun getCompanySettingsFlow(): Flow<LocalCompanySettings?>

    @Query("SELECT * FROM local_company_settings WHERE id = 1")
    suspend fun getCompanySettings(): LocalCompanySettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompanySettings(settings: LocalCompanySettings)

    @Query("SELECT * FROM local_feature_settings WHERE id = 1")
    fun getFeatureSettingsFlow(): Flow<LocalFeatureSettings?>

    @Query("SELECT * FROM local_feature_settings WHERE id = 1")
    suspend fun getFeatureSettings(): LocalFeatureSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeatureSettings(settings: LocalFeatureSettings)
}
