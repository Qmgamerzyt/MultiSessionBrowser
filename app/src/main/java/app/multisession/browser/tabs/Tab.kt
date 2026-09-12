package app.multisession.browser.tabs

import android.content.Context
import android.graphics.Bitmap
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.TabEntity
import org.mozilla.geckoview.GeckoSession

data class PageError(val code: Int, val category: Int, val description: String, val url: String)

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
    /** Set for tabs created by the page (window.open) until they were shown once. */
    var awaitingDisplay: Boolean = false
    var openerTabId: String? = null
    var desktopMode: Boolean = false
    var createdAt: Long = System.currentTimeMillis()
    var lastActiveAt: Long = createdAt

    val isStartPage: Boolean get() = UrlUtils.isStartPage(url)
    val isLive: Boolean get() = geckoSession != null

    fun displayTitle(context: Context): String = when {
        isStartPage -> context.getString(R.string.new_tab)
        title.isNotBlank() -> title
        else -> UrlUtils.displayHost(url).ifBlank { url }
    }

    fun toEntity() = TabEntity(
        id, sessionId, url, title, position, createdAt, lastActiveAt, desktopMode,
        sessionState = if (isStartPage) null else savedState?.toString(),
    )

    companion object {
        fun from(e: TabEntity) = Tab(e.id, e.sessionId, e.position).apply {
            url = e.url
            title = e.title
            createdAt = e.createdAt
            lastActiveAt = e.lastActiveAt
            desktopMode = e.desktopMode
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
