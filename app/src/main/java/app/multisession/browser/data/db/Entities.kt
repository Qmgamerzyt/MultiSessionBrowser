package app.multisession.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val lastUsedAt: Long,
    val activeTabId: String? = null,
    val isPrivate: Boolean = false,
    val desktopMode: Boolean = false,
    val sortOrder: Int = 0,
    /** Exactly one session is the default: it is always listed first and opened on cold start. Added in DB v4. */
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
)

/**
 * GeckoView session context id (Gecko "contextual identity") backing a session: stable, derived
 * from the id. All GeckoSessions of the session use it -> shared cookies/storage inside the
 * session, complete separation from every other session.
 */
val SessionEntity.contextId: String get() = "session-$id"

@Entity(
    tableName = "tabs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class TabEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val url: String,
    val title: String,
    val position: Int,
    val createdAt: Long,
    val lastActiveAt: Long,
    val desktopMode: Boolean = false,
    /** Serialised GeckoSession.SessionState (history, scroll, form data) - null for start-page tabs. Added in DB v2. */
    val sessionState: String? = null,
    /** Tab group (tab_groups.id) or null for an ungrouped tab. Added in DB v4 (no FK: a missing group simply ungroups the tab). */
    val groupId: String? = null,
    /** Pinned tabs form a stable section at the top of the session's tab order. Added in DB v5. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Archived tabs are hidden from the normal tab presentation but keep URL/state/group/order. Added in DB v5. */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val archivedAt: Long? = null,
)

/** Chrome-style tab group inside one browser session. Groups live independently of their tabs: an empty group is kept. Added in DB v4. */
@Entity(
    tableName = "tab_groups",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class TabGroupEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val color: Int,
    val position: Int,
    val collapsed: Boolean = false,
    val createdAt: Long,
)

/**
 * A recently closed tab (persisted so "reopen closed tab" survives a process restart). Added in DB v5.
 * id = the id the tab had while open. Deleting the session removes its closed tabs (FK cascade).
 * closedGroupId is set when the tab was closed together with its whole group (see [ClosedGroupEntity]).
 */
@Entity(
    tableName = "closed_tabs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId"), Index("closedAt")]
)
data class ClosedTabEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val url: String,
    val title: String,
    val groupId: String?,
    val groupName: String?,
    val groupColor: Int?,
    val position: Int,
    val pinned: Boolean,
    val desktopMode: Boolean,
    val sessionState: String?,
    val closedGroupId: String?,
    val closedAt: Long,
)

/** A tab group the user deleted together with its tabs; restorable from "Recently closed". Added in DB v5. */
@Entity(
    tableName = "closed_groups",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ClosedGroupEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val color: Int,
    val position: Int,
    val collapsed: Boolean,
    val createdAt: Long,
    val closedAt: Long,
)

/**
 * Workspace / research set: a saved browsing environment of ONE session (tabs, groups, order, pinned state).
 * It is a snapshot with references: items remember the live tab they came from (sourceTabId) so restoring re-uses
 * a still-open tab instead of duplicating it. Added in DB v5. Deleting the session deletes its workspaces (cascade).
 */
@Entity(
    tableName = "workspaces",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workspace_groups",
    foreignKeys = [ForeignKey(
        entity = WorkspaceEntity::class,
        parentColumns = ["id"],
        childColumns = ["workspaceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workspaceId")]
)
data class WorkspaceGroupEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val name: String,
    val color: Int,
    val position: Int,
    val collapsed: Boolean,
    /** The live tab group this was saved from (re-used on restore when it still exists). */
    val sourceGroupId: String?,
)

@Entity(
    tableName = "workspace_items",
    foreignKeys = [ForeignKey(
        entity = WorkspaceEntity::class,
        parentColumns = ["id"],
        childColumns = ["workspaceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workspaceId")]
)
data class WorkspaceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: String,
    val url: String,
    val title: String,
    val position: Int,
    val pinned: Boolean,
    val desktopMode: Boolean,
    /** workspace_groups.id or null. */
    val groupId: String?,
    /** Serialised GeckoSession.SessionState captured when the workspace was saved (history/scroll/forms). */
    val sessionState: String?,
    /** The live tab this item was saved from; restore re-uses that tab if it is still open with the same URL. */
    val sourceTabId: String?,
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId"), Index("visitedAt")]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/**
 * The user's decision for one site permission inside one browser session (sessions are isolated,
 * so a site allowed in "Work" is still asked in "Personal"). value mirrors GeckoView's
 * ContentPermission.VALUE_ALLOW (1) / VALUE_DENY (2); "ask" is the absence of a row. Added in DB v3.
 */
@Entity(
    tableName = "site_permissions",
    primaryKeys = ["sessionId", "origin", "permission"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SitePermissionEntity(
    val sessionId: String,
    val origin: String,
    val permission: String,
    val value: Int,
    val updatedAt: Long,
)

/** sessionId == null means a global bookmark visible in every session. */
@Entity(tableName = "bookmarks", indices = [Index("sessionId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val url: String,
    val title: String,
    val createdAt: Long,
)

/**
 * One download (active or historical) of the app-owned download manager. Added in DB v4.
 * No FK to sessions on purpose: the download history must survive the deletion of the session it came from.
 * status: see app.multisession.browser.downloads.DownloadStatus.
 */
@Entity(tableName = "downloads", indices = [Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val url: String,
    val sourcePage: String?,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Int,
    /** MediaStore content:// URI (Android 10+) of the saved file. */
    val contentUri: String?,
    /** Absolute path (Android 9 public Downloads folder). */
    val filePath: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
