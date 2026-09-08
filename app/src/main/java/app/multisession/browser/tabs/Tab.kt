package app.multisession.browser.tabs

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.TabEntity

data class PageError(val code: Int, val description: String, val url: String)

/** In-memory tab model. The WebView is optional: hibernated tabs keep only [savedState] + [url]. */
class Tab(val id: String, val sessionId: String, var position: Int) {
    var url: String = UrlUtils.START_PAGE
    var title: String = ""
    var favicon: Bitmap? = null
    var thumbnail: Bitmap? = null
    var webView: WebView? = null
    var savedState: Bundle? = null
    var isLoading: Boolean = false
    var progress: Int = 0
    var error: PageError? = null
    var openerTabId: String? = null
    var desktopMode: Boolean = false
    var createdAt: Long = System.currentTimeMillis()
    var lastActiveAt: Long = createdAt

    val isStartPage: Boolean get() = UrlUtils.isStartPage(url)
    val isLive: Boolean get() = webView != null

    fun displayTitle(context: Context): String = when {
        isStartPage -> context.getString(R.string.new_tab)
        title.isNotBlank() -> title
        else -> UrlUtils.displayHost(url).ifBlank { url }
    }

    fun toEntity() = TabEntity(id, sessionId, url, title, position, createdAt, lastActiveAt, desktopMode)

    companion object {
        fun from(e: TabEntity) = Tab(e.id, e.sessionId, e.position).apply {
            url = e.url
            title = e.title
            createdAt = e.createdAt
            lastActiveAt = e.lastActiveAt
            desktopMode = e.desktopMode
        }
    }
}
