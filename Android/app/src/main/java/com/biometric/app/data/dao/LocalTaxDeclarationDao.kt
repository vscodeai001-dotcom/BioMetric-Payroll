package com.biometric.app.data.dao

import androidx.room.*
import com.biometric.app.data.entity.LocalTaxDeclaration
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTaxDeclarationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(declaration: LocalTaxDeclaration)

    @Query("SELECT * FROM local_tax_declarations ORDER BY submissionDate DESC")
    fun getAllFlow(): Flow<List<LocalTaxDeclaration>>

    @Query("SELECT * FROM local_tax_declarations WHERE syncState = 0")
    fun getUnsynced(): List<LocalTaxDeclaration>

    @Query("DELETE FROM local_tax_declarations WHERE declarationId = :id")
    fun deleteById(id: Int)
}
