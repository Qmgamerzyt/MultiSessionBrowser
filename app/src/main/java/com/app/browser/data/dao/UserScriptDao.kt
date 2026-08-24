package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.UserScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserScriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: UserScriptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scripts: List<UserScriptEntity>)

    @Update
    suspend fun update(script: UserScriptEntity)

    @Query("DELETE FROM user_scripts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM user_scripts WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT * FROM user_scripts WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun getBySessionId(sessionId: String): Flow<List<UserScriptEntity>>

    @Query("SELECT * FROM user_scripts WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getBySessionIdOnce(sessionId: String): List<UserScriptEntity>

    @Query("SELECT * FROM user_scripts WHERE id = :id")
    suspend fun getById(id: String): UserScriptEntity?
}