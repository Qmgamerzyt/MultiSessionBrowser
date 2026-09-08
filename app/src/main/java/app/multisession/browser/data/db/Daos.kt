package app.multisession.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    // @Upsert (not REPLACE) so updating a session never cascades-deletes its tabs/history.
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sessions SET activeTabId = :tabId, lastUsedAt = :now WHERE id = :id")
    suspend fun setActiveTab(id: String, tabId: String?, now: Long)

    @Query("UPDATE sessions SET lastUsedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun getAll(): List<TabEntity>

    @Upsert
    suspend fun upsert(tab: TabEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tabs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("SELECT * FROM history WHERE sessionId = :sessionId ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recent(sessionId: String, limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE sessionId = :sessionId ORDER BY visitedAt DESC LIMIT 1000")
    fun observe(sessionId: String): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE sessionId = :sessionId AND url = :url")
    suspend fun deleteByUrl(sessionId: String, url: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE sessionId = :sessionId")
    suspend fun clear(sessionId: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE sessionId IS NULL OR sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeForSession(sessionId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE sessionId IS NULL OR sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forSession(sessionId: String, limit: Int): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND (sessionId IS NULL OR sessionId = :sessionId))")
    suspend fun exists(sessionId: String, url: String): Boolean

    @Query("DELETE FROM bookmarks WHERE url = :url AND (sessionId IS NULL OR sessionId = :sessionId)")
    suspend fun deleteByUrl(sessionId: String, url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
