package app.multisession.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    // The default session is ALWAYS first; the user's manual order applies to everything after it.
    @Query("SELECT * FROM sessions ORDER BY isDefault DESC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY isDefault DESC, sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    // @Upsert (not REPLACE) so updating a session never cascades-deletes its tabs/history.
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sessions SET activeTabId = :tabId, lastUsedAt = :now WHERE id = :id")
    suspend fun setActiveTab(id: String, tabId: String?, now: Long)

    @Query("UPDATE sessions SET lastUsedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("UPDATE sessions SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: String, order: Int)

    /** Makes exactly one session the default. */
    @Query("UPDATE sessions SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefault(id: String)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun getAll(): List<TabEntity>

    @Upsert
    suspend fun upsert(tab: TabEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<TabEntity>)

    /** Title-only write (v2.1.10 perf): an SPA title change must not reserialise the whole row. */
    @Query("UPDATE tabs SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tabs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("UPDATE tabs SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("DELETE FROM tabs WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /** Closing every visible tab of a session keeps its archived tabs. */
    @Query("DELETE FROM tabs WHERE sessionId = :sessionId AND archived = 0")
    suspend fun deleteActiveForSession(sessionId: String)
}

@Dao
interface ClosedTabDao {
    @Query("SELECT * FROM closed_tabs ORDER BY closedAt DESC")
    suspend fun getAll(): List<ClosedTabEntity>

    @Query("SELECT * FROM closed_tabs WHERE id = :id")
    suspend fun get(id: String): ClosedTabEntity?

    @Upsert
    suspend fun upsert(entry: ClosedTabEntity)

    @Upsert
    suspend fun upsertAll(entries: List<ClosedTabEntity>)

    @Query("DELETE FROM closed_tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM closed_tabs WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM closed_tabs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    /** Keeps only the newest [keep] entries. */
    @Query("DELETE FROM closed_tabs WHERE id NOT IN (SELECT id FROM closed_tabs ORDER BY closedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface ClosedGroupDao {
    @Query("SELECT * FROM closed_groups ORDER BY closedAt DESC")
    suspend fun getAll(): List<ClosedGroupEntity>

    @Upsert
    suspend fun upsert(entry: ClosedGroupEntity)

    @Query("DELETE FROM closed_groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM closed_groups WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM closed_groups WHERE id NOT IN (SELECT id FROM closed_groups ORDER BY closedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    suspend fun getAll(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun get(id: String): WorkspaceEntity?

    @Upsert
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM workspace_groups WHERE workspaceId = :workspaceId ORDER BY position ASC")
    suspend fun groups(workspaceId: String): List<WorkspaceGroupEntity>

    @Query("SELECT * FROM workspace_items WHERE workspaceId = :workspaceId ORDER BY position ASC")
    suspend fun items(workspaceId: String): List<WorkspaceItemEntity>

    @Query("SELECT COUNT(*) FROM workspace_items WHERE workspaceId = :workspaceId")
    suspend fun itemCount(workspaceId: String): Int

    @Upsert
    suspend fun upsertGroups(groups: List<WorkspaceGroupEntity>)

    @Insert
    suspend fun insertItems(items: List<WorkspaceItemEntity>)

    @Query("DELETE FROM workspace_groups WHERE workspaceId = :workspaceId")
    suspend fun deleteGroups(workspaceId: String)

    @Query("DELETE FROM workspace_items WHERE workspaceId = :workspaceId")
    suspend fun deleteItems(workspaceId: String)
}

@Dao
interface TabGroupDao {
    @Query("SELECT * FROM tab_groups ORDER BY position ASC, createdAt ASC")
    suspend fun getAll(): List<TabGroupEntity>

    @Upsert
    suspend fun upsert(group: TabGroupEntity)

    @Upsert
    suspend fun upsertAll(groups: List<TabGroupEntity>)

    @Query("DELETE FROM tab_groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tab_groups WHERE sessionId = :sessionId")
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

    @Query("SELECT * FROM history WHERE sessionId = :sessionId AND (url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun search(sessionId: String, query: String, limit: Int): List<HistoryEntity>

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

    @Query("SELECT * FROM bookmarks WHERE (sessionId IS NULL OR sessionId = :sessionId) AND (url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(sessionId: String, query: String, limit: Int): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE url = :url AND (sessionId IS NULL OR sessionId = :sessionId)")
    suspend fun deleteByUrl(sessionId: String, url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions")
    suspend fun getAll(): List<SitePermissionEntity>

    @Upsert
    suspend fun upsert(rule: SitePermissionEntity)

    @Query("DELETE FROM site_permissions WHERE sessionId = :sessionId AND origin = :origin AND permission = :permission")
    suspend fun delete(sessionId: String, origin: String, permission: String)

    @Query("DELETE FROM site_permissions WHERE sessionId = :sessionId AND origin = :origin")
    suspend fun deleteForOrigin(sessionId: String, origin: String)

    @Query("DELETE FROM site_permissions WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    /** Process died while downloading: nothing is running any more, so RUNNING/PENDING rows become PAUSED (resumable). */
    @Query("UPDATE downloads SET status = :to, updatedAt = :now WHERE status IN (:from)")
    suspend fun remap(from: List<Int>, to: Int, now: Long)

    @Query("DELETE FROM downloads WHERE status IN (:statuses)")
    suspend fun deleteWithStatus(statuses: List<Int>)
}
