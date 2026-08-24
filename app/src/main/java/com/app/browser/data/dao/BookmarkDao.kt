package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT * FROM bookmarks WHERE sessionId = :sessionId OR sessionId IS NULL ORDER BY createdAt DESC")
    fun getBySessionId(sessionId: String?): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE sessionId = :sessionId OR sessionId IS NULL ORDER BY createdAt DESC")
    suspend fun getBySessionIdOnce(sessionId: String?): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE url = :url AND (sessionId = :sessionId OR sessionId IS NULL)")
    suspend fun exists(url: String, sessionId: String?): Boolean
}