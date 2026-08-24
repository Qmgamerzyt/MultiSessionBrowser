package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.app.browser.data.entities.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(histories: List<HistoryEntity>)

    @Query("DELETE FROM history WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM history WHERE sessionId = :sessionId ORDER BY visitedAt DESC LIMIT 1000")
    fun getBySessionId(sessionId: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE sessionId = :sessionId ORDER BY visitedAt DESC LIMIT 1000")
    suspend fun getBySessionIdOnce(sessionId: String): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE sessionId = :sessionId AND url = :url ORDER BY visitedAt DESC LIMIT 1")
    suspend fun getLatestByUrl(sessionId: String, url: String): HistoryEntity?

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}