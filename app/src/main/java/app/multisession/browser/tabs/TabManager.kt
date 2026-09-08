package app.multisession.browser.tabs

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.MutableContextWrapper
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.HistoryEntity
import app.multisession.browser.data.db.TabEntity
import app.multisession.browser.webview.BrowserHost
import app.multisession.browser.webview.WebViewFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns every Tab of every session (in memory) and the WebView lifecycle:
 *  - active tab: fully alive and attached to the Activity
 *  - background tabs: kept alive up to [Prefs.liveTabLimit] (most recently used first)
 *  - beyond the limit / under memory pressure: saveState() -> destroy(); recreated lazily
 * Tab metadata is persisted to Room so everything is restored after process death.
 */
class TabManager(private val core: BrowserCore) {

    interface Listener {
        fun onTabsChanged(sessionId: String) {}
        fun onTabUpdated(tab: Tab) {}
    }

    private val tabs = LinkedHashMap<String, Tab>()
    private val recentlyClosed = ArrayDeque<TabEntity>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** The foreground Activity (set in onStart / cleared in onStop). UI callbacks from WebViews go through it. */
    var host: BrowserHost? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    // ------------------------------------------------------------------ queries

    fun loadFromDb(entities: List<TabEntity>) {
        tabs.clear()
        entities.sortedBy { it.position }.forEach { tabs[it.id] = Tab.from(it) }
        AppLog.i(TAG, "Restored ${tabs.size} tabs from database")
    }

    fun get(id: String?): Tab? = id?.let { tabs[it] }

    fun tabsFor(sessionId: String): List<Tab> =
        tabs.values.filter { it.sessionId == sessionId }.sortedBy { it.position }

    fun countFor(sessionId: String): Int = tabs.values.count { it.sessionId == sessionId }

    fun activeTab(sessionId: String): Tab? {
        val preferred = get(core.sessions.get(sessionId)?.activeTabId)?.takeIf { it.sessionId == sessionId }
        return preferred ?: tabsFor(sessionId).maxByOrNull { it.lastActiveAt }
    }

    fun findByWebView(webView: WebView): Tab? = tabs.values.firstOrNull { it.webView === webView }

    fun hasRecentlyClosed(sessionId: String): Boolean = recentlyClosed.any { it.sessionId == sessionId }

    fun liveCount(): Int = tabs.values.count { it.webView != null }

    // ------------------------------------------------------------------ mutations

    fun createTab(
        sessionId: String,
        url: String = UrlUtils.START_PAGE,
        select: Boolean = true,
        openerTabId: String? = null,
    ): Tab {
        val siblings = tabsFor(sessionId)
        val opener = openerTabId?.let { id -> siblings.firstOrNull { it.id == id } }
        val insertAt = opener?.let { it.position + 1 } ?: siblings.size
        siblings.filter { it.position >= insertAt }.forEach { it.position += 1 }
        val tab = Tab(UUID.randomUUID().toString(), sessionId, insertAt).apply {
            this.url = url
            this.openerTabId = openerTabId
            this.desktopMode = core.sessions.get(sessionId)?.desktopMode ?: false
        }
        tabs[tab.id] = tab
        renumber(sessionId)
        persistSession(sessionId)
        AppLog.i(TAG, "Tab created ${tab.id.take(8)} session=${sessionId.take(8)} startPage=${tab.isStartPage}")
        if (select) selectTab(tab)
        notifyTabsChanged(sessionId)
        return tab
    }

    fun selectTab(tab: Tab) {
        tab.lastActiveAt = System.currentTimeMillis()
        core.sessions.setActiveTab(tab.sessionId, tab.id)
        persistTab(tab)
    }

    /** Closes a tab and returns the tab that should become active next (null if the session is now empty). */
    fun closeTab(tabId: String): Tab? {
        val tab = tabs.remove(tabId) ?: return null
        val sessionId = tab.sessionId
        val oldIndex = tab.position
        destroyWebView(tab)
        if (!tab.isStartPage) rememberClosed(tab.toEntity())
        renumber(sessionId)
        val remaining = tabsFor(sessionId)
        core.persist { core.repo.tabs.delete(tabId) }
        persistSession(sessionId)
        AppLog.i(TAG, "Tab closed ${tabId.take(8)}; ${remaining.size} left in session")
        notifyTabsChanged(sessionId)
        val opener = get(tab.openerTabId)?.takeIf { it.sessionId == sessionId }
        return opener
            ?: remaining.getOrNull(oldIndex.coerceAtMost(remaining.size - 1))
            ?: remaining.lastOrNull()
    }

    fun closeAllTabs(sessionId: String) {
        tabsFor(sessionId).forEach { t ->
            tabs.remove(t.id)
            destroyWebView(t)
            if (!t.isStartPage) rememberClosed(t.toEntity())
        }
        core.persist { core.repo.tabs.deleteForSession(sessionId) }
        AppLog.i(TAG, "All tabs closed in ${sessionId.take(8)}")
        notifyTabsChanged(sessionId)
    }

    fun reopenClosedTab(sessionId: String): Tab? {
        val e = recentlyClosed.firstOrNull { it.sessionId == sessionId } ?: return null
        recentlyClosed.remove(e)
        val tab = createTab(sessionId, e.url, select = true)
        tab.title = e.title
        tab.desktopMode = e.desktopMode
        return tab
    }

    fun moveTab(sessionId: String, from: Int, to: Int) {
        val list = tabsFor(sessionId).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        list.forEachIndexed { i, t -> t.position = i }
        persistSession(sessionId)
        notifyTabsChanged(sessionId)
    }

    /** Removes every tab of a session from memory and destroys their WebViews (used when deleting a session). */
    fun destroySession(sessionId: String) {
        tabsFor(sessionId).forEach { t ->
            tabs.remove(t.id)
            destroyWebView(t)
        }
        recentlyClosed.removeAll { it.sessionId == sessionId }
    }

    fun hibernateSession(sessionId: String) = tabsFor(sessionId).forEach { hibernate(it) }

    fun onNavigated(tab: Tab, url: String, title: String?) {
        tab.url = url
        if (title != null) tab.title = title
        tab.lastActiveAt = System.currentTimeMillis()
        persistTab(tab)
        notifyTabUpdated(tab)
    }

    fun recordHistory(tab: Tab) {
        val session = core.sessions.get(tab.sessionId) ?: return
        if (session.isPrivate) return
        val url = tab.url
        if (!UrlUtils.isWebUrl(url) && !UrlUtils.isLocalContent(url)) return
        val entry = HistoryEntity(
            sessionId = tab.sessionId,
            url = url,
            title = tab.title.ifBlank { UrlUtils.displayHost(url) },
            visitedAt = System.currentTimeMillis(),
        )
        core.persist {
            core.repo.history.deleteByUrl(entry.sessionId, entry.url)
            core.repo.history.insert(entry)
        }
    }

    fun persistTab(tab: Tab) {
        val e = tab.toEntity()
        core.persist { core.repo.tabs.upsert(e) }
    }

    fun persistAll() {
        val all = tabs.values.map { it.toEntity() }
        core.persist { core.repo.tabs.upsertAll(all) }
    }

    private fun persistSession(sessionId: String) {
        val snapshot = tabsFor(sessionId).map { it.toEntity() }
        core.persist { core.repo.tabs.upsertAll(snapshot) }
    }

    private fun renumber(sessionId: String) {
        tabsFor(sessionId).forEachIndexed { i, t -> t.position = i }
    }

    private fun rememberClosed(e: TabEntity) {
        recentlyClosed.addFirst(e)
        while (recentlyClosed.size > MAX_CLOSED) recentlyClosed.removeLast()
    }

    fun notifyTabUpdated(tab: Tab) = listeners.forEach { it.onTabUpdated(tab) }
    private fun notifyTabsChanged(sessionId: String) = listeners.forEach { it.onTabsChanged(sessionId) }

    // ------------------------------------------------------------------ WebView lifecycle

    /**
     * Returns the tab's WebView, creating it (bound to the session's isolated profile) when needed.
     * With [loadContent] the previous state is restored or the URL is loaded.
     */
    fun ensureWebView(tab: Tab, activity: Activity, loadContent: Boolean = true): WebView {
        tab.webView?.let { existing ->
            rebindContext(existing, activity)
            return existing
        }
        val session = core.sessions.get(tab.sessionId)
            ?: throw IllegalStateException("Session ${tab.sessionId} not found for tab ${tab.id}")
        val webView = WebViewFactory.create(activity, tab, session, core)
        tab.webView = webView
        AppLog.i(TAG, "WebView created tab=${tab.id.take(8)} profile=${core.isolation.profileName(session)} live=${liveCount()}")
        if (loadContent) {
            var restored = false
            tab.savedState?.let { state ->
                restored = try {
                    webView.restoreState(state) != null
                } catch (t: Throwable) {
                    AppLog.w(TAG, "restoreState failed", t); false
                }
                tab.savedState = null
            }
            if (!restored && !tab.isStartPage) webView.loadUrl(tab.url)
        }
        return webView
    }

    /** Saves navigation state and destroys the WebView; the tab can be recreated later. */
    fun hibernate(tab: Tab) {
        val wv = tab.webView ?: return
        val bundle = Bundle()
        try {
            wv.saveState(bundle)
        } catch (t: Throwable) {
            AppLog.w(TAG, "saveState failed", t)
        }
        tab.savedState = if (bundle.isEmpty) null else bundle
        destroyWebView(tab)
        AppLog.i(TAG, "Tab hibernated ${tab.id.take(8)} live=${liveCount()}")
    }

    fun destroyWebView(tab: Tab) {
        val wv = tab.webView ?: return
        tab.webView = null
        tab.isLoading = false
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.onPause()
            wv.webChromeClient = null
            wv.destroy()
        } catch (t: Throwable) {
            AppLog.w(TAG, "destroy failed", t)
        }
        (wv.context as? MutableContextWrapper)?.baseContext = core.app
        AppLog.d(TAG, "WebView destroyed tab=${tab.id.take(8)}")
    }

    /** Keeps at most [Prefs.liveTabLimit] WebViews alive (the active one always survives). */
    fun enforceLiveLimit(activeTabId: String?) {
        val keepBackground = (Prefs.liveTabLimit - 1).coerceAtLeast(0)
        val background = tabs.values
            .filter { it.webView != null && it.id != activeTabId }
            .sortedByDescending { it.lastActiveAt }
        background.drop(keepBackground).forEach { hibernate(it) }
    }

    fun onTrimMemory(level: Int) {
        val activeId = core.sessions.activeId?.let { activeTab(it)?.id }
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> hibernateAll(exceptTabId = null)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> hibernateAll(exceptTabId = activeId)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                tabs.values.filter { it.webView != null && it.id != activeId }
                    .sortedByDescending { it.lastActiveAt }.drop(1).forEach { hibernate(it) }
            }
        }
    }

    private fun hibernateAll(exceptTabId: String?) {
        tabs.values.filter { it.webView != null && it.id != exceptTabId }.forEach { hibernate(it) }
    }

    /** Re-applies user settings (JS, UA, zoom...) to every live WebView after Settings changed. */
    fun reapplySettings() {
        tabs.values.forEach { tab ->
            val wv = tab.webView ?: return@forEach
            val session = core.sessions.get(tab.sessionId) ?: return@forEach
            WebViewFactory.applySettings(wv, session, tab, core)
        }
    }

    private fun rebindContext(webView: WebView, activity: Activity) {
        (webView.context as? MutableContextWrapper)?.let { if (it.baseContext !== activity) it.baseContext = activity }
    }

    /** Called when the Activity is destroyed: detach views and drop Activity references (no leaks). */
    fun detachFromActivity() {
        tabs.values.forEach { tab ->
            val wv = tab.webView ?: return@forEach
            (wv.parent as? ViewGroup)?.removeView(wv)
            (wv.context as? MutableContextWrapper)?.baseContext = core.app
        }
    }

    fun onRenderProcessGone(tab: Tab) {
        AppLog.w(TAG, "Render process gone for tab ${tab.id.take(8)}")
        destroyWebView(tab)
        tab.error = PageError(ERROR_RENDERER_GONE, "Renderer process terminated", tab.url)
        notifyTabUpdated(tab)
    }

    companion object {
        private const val TAG = "Tabs"
        private const val MAX_CLOSED = 20
        const val ERROR_RENDERER_GONE = -1000
    }
}
