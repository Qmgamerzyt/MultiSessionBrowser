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
