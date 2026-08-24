package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.ExtensionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(extension: ExtensionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extensions: List<ExtensionEntity>)

    @Update
    suspend fun update(extension: ExtensionEntity)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM extensions")
    fun getAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions")
    suspend fun getAllOnce(): List<ExtensionEntity>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun getById(id: String): ExtensionEntity?

    @Query("SELECT * FROM extensions WHERE isBuiltIn = 1")
    suspend fun getBuiltIn(): List<ExtensionEntity>
}