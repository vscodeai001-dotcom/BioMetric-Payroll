package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalFbpDeclaration
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFbpDeclarationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(declaration: LocalFbpDeclaration)

    @Query("SELECT * FROM local_fbp_declarations ORDER BY submissionDate DESC")
    fun getAllFlow(): Flow<List<LocalFbpDeclaration>>

    @Query("SELECT * FROM local_fbp_declarations WHERE syncState = 0")
    fun getUnsynced(): List<LocalFbpDeclaration>

    @Query("DELETE FROM local_fbp_declarations WHERE declarationId = :id")
    fun deleteById(id: Int)
}
