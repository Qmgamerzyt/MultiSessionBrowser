package app.multisession.browser.data.db

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
)

/** Name of the isolated WebView profile backing a session (stable, derived from the id). */
val SessionEntity.profileName: String get() = "session-$id"

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

/** sessionId == null means a global bookmark visible in every session. */
@Entity(tableName = "bookmarks", indices = [Index("sessionId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val url: String,
    val title: String,
    val createdAt: Long,
)
