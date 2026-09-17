package app.multisession.browser.tabs

import android.content.Context
import android.graphics.Bitmap
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.TabEntity
import app.multisession.browser.data.db.TabGroupEntity
import org.mozilla.geckoview.GeckoSession

data class PageError(val code: Int, val category: Int, val description: String, val url: String)

/** In-memory tab group (see TabGroupEntity). Groups survive having 0 tabs: they are deleted only by an explicit user action. */
class TabGroup(
    val id: String,
    val sessionId: String,
    var name: String,
    var color: Int,
    var position: Int,
    var collapsed: Boolean,
    val createdAt: Long,
) {
    fun toEntity() = TabGroupEntity(id, sessionId, name, color, position, collapsed, createdAt)

    companion object {
        fun from(e: TabGroupEntity) = TabGroup(e.id, e.sessionId, e.name, e.color, e.position, e.collapsed, e.createdAt)
    }
}

/**
 * Snapshot of a tab taken on the main thread. [toEntity] serialises the GeckoSession.SessionState to JSON
 * (hundreds of KB for long histories / big forms), so TabManager runs it on Dispatchers.Default. Gecko
 * hands out a fresh SessionState copy per callback, so reading it on another thread is safe.
 */
class TabSnapshot(
    private val id: String,
    private val sessionId: String,
    private val url: String,
    private val title: String,
    private val position: Int,
    private val createdAt: Long,
    private val lastActiveAt: Long,
    private val desktopMode: Boolean,
    private val state: GeckoSession.SessionState?,
    private val isStartPage: Boolean,
    private val groupId: String?,
) {
    fun toEntity() = TabEntity(
        id, sessionId, url, title, position, createdAt, lastActiveAt, desktopMode,
        sessionState = if (isStartPage) null else state?.toString(),
        groupId = groupId,
    )
}

/**
 * In-memory tab model. The [geckoSession] is optional: hibernated tabs keep only [savedState]
 * (Gecko's serialisable history/scroll/form state) + [url] and are re-opened lazily.
 */
class Tab(val id: String, val sessionId: String, var position: Int) {
    var url: String = UrlUtils.START_PAGE
    var title: String = ""
    var favicon: Bitmap? = null
    var thumbnail: Bitmap? = null
    var geckoSession: GeckoSession? = null
    /** Latest state reported by ProgressDelegate.onSessionStateChange (live) or captured when hibernating. */
    var savedState: GeckoSession.SessionState? = null
    var isLoading: Boolean = false
    var progress: Int = 0
    var error: PageError? = null
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false
    var isFullScreen: Boolean = false
    /** Permissions Gecko reported for the current page (NavigationDelegate.onLocationChange). */
    var sitePermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList()
    /** Set for tabs created by the page (window.open) until they were shown once. */
    var awaitingDisplay: Boolean = false
    var openerTabId: String? = null
    var desktopMode: Boolean = false
    /** Tab group id or null (ungrouped). */
    var groupId: String? = null
    /**
     * A javascript: URL this app itself asked Gecko to run (bookmarklet / HUD command). NavigationDelegate.onLoadRequest
     * lets exactly this one app-initiated javascript: load through and keeps denying every page-initiated one.
     */
    var pendingScript: String? = null
    var createdAt: Long = System.currentTimeMillis()
    var lastActiveAt: Long = createdAt

    val isStartPage: Boolean get() = UrlUtils.isStartPage(url)
    val isLive: Boolean get() = geckoSession != null

    fun displayTitle(context: Context): String = when {
        isStartPage -> context.getString(R.string.new_tab)
        title.isNotBlank() -> title
        else -> UrlUtils.displayHost(url).ifBlank { url }
    }

    fun toEntity() = snapshot().toEntity()

    /** Immutable copy of the persisted fields; serialise it with [TabSnapshot.toEntity] off the main thread. */
    fun snapshot() = TabSnapshot(id, sessionId, url, title, position, createdAt, lastActiveAt, desktopMode, savedState, isStartPage, groupId)

    companion object {
        fun from(e: TabEntity) = Tab(e.id, e.sessionId, e.position).apply {
            url = e.url
            title = e.title
            createdAt = e.createdAt
            lastActiveAt = e.lastActiveAt
            desktopMode = e.desktopMode
            groupId = e.groupId
            savedState = e.sessionState?.takeIf { it.isNotBlank() }?.let { json ->
                try {
                    GeckoSession.SessionState.fromString(json)
                } catch (t: Throwable) {
                    AppLog.w("Tab", "Could not parse saved session state for ${e.id.take(8)}", t); null
                }
            }
        }
    }
}
