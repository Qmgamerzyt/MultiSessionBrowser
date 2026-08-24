package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.app.browser.data.entities.MatrixEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MatrixDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(matrix: MatrixEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(matrices: List<MatrixEntity>)

    @Query("DELETE FROM extension_session_matrix WHERE extensionId = :extId AND sessionId = :sessId")
    suspend fun delete(extId: String, sessId: String)

    @Query("DELETE FROM extension_session_matrix WHERE extensionId = :extId")
    suspend fun deleteByExtensionId(extId: String)

    @Query("DELETE FROM extension_session_matrix WHERE sessionId = :sessId")
    suspend fun deleteBySessionId(sessId: String)

    @Query("SELECT * FROM extension_session_matrix WHERE sessionId = :sessionId")
    fun getBySessionId(sessionId: String): Flow<List<MatrixEntity>>

    @Query("SELECT * FROM extension_session_matrix WHERE sessionId = :sessionId")
    suspend fun getBySessionIdOnce(sessionId: String): List<MatrixEntity>

    @Query("SELECT * FROM extension_session_matrix WHERE extensionId = :extensionId")
    suspend fun getByExtensionId(extensionId: String): List<MatrixEntity>

    @Query("SELECT * FROM extension_session_matrix")
    suspend fun getAll(): List<MatrixEntity>

    @Query("SELECT state FROM extension_session_matrix WHERE extensionId = :extId AND sessionId = :sessId")
    suspend fun getState(extId: String, sessId: String): String?
}