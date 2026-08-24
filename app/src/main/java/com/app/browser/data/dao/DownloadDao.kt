package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT * FROM downloads WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun getBySessionId(sessionId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getBySessionIdOnce(sessionId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?
}