package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM sessions ORDER BY lastActive DESC")
    fun getAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY lastActive DESC")
    suspend fun getAllOnce(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("UPDATE sessions SET lastActive = :lastActive WHERE id = :id")
    suspend fun updateLastActive(id: String, lastActive: Long)
}