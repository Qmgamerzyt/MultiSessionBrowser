package app.multisession.browser.tabs

import android.content.ComponentCallbacks2
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.HistoryEntity
import app.multisession.browser.data.db.TabEntity
import app.multisession.browser.engine.BrowserHost
import app.multisession.browser.engine.SessionFactory
import org.mozilla.geckoview.GeckoSession
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns every Tab of every browser session (in memory) and the GeckoSession lifecycle:
 *  - displayed tab: GeckoSession open, active and attached to the Activity's GeckoView
 *  - background tabs: GeckoSession kept open (inactive) up to [Prefs.liveTabLimit], most recently used first
 *  - beyond the limit / under memory pressure: SessionState kept -> session.close(); re-opened lazily
 * Tab metadata + SessionState are persisted to Room so everything is restored after process death.
 * Nothing here destroys/recreates sessions during ordinary tab switching.
 */
class TabManager(private val core: BrowserCore) {

    interface Listener {
        fun onTabsChanged(sessionId: String) {}
        fun onTabUpdated(tab: Tab) {}
    }

    private val tabs = LinkedHashMap<String, Tab>()
    private val recentlyClosed = ArrayDeque<TabEntity>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** The foreground Activity (set in onStart / cleared in onStop). UI callbacks from the engine go through it. */
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

    fun findBySession(session: GeckoSession): Tab? = tabs.values.firstOrNull { it.geckoSession === session }

    fun hasRecentlyClosed(sessionId: String): Boolean = recentlyClosed.any { it.sessionId == sessionId }

    fun liveCount(): Int = tabs.values.count { it.geckoSession != null }

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

    /**
     * window.open / target=_blank: a new tab in the opener's browser session whose GeckoSession is
     * configured (same contextId) but NOT opened - Gecko opens it itself after NavigationDelegate.onNewSession.
     */
    fun createPopupTab(opener: Tab, uri: String): Tab? {
        val session = core.sessions.get(opener.sessionId) ?: return null
        val tab = createTab(opener.sessionId, uri, select = false, openerTabId = opener.id)
        tab.geckoSession = SessionFactory.create(core, tab, session)
        tab.awaitingDisplay = true
        AppLog.i(TAG, "Popup tab ${tab.id.take(8)} prepared for ${uri.take(60)}")
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
        closeSession(tab)
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
            closeSession(t)
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
        tab.savedState = e.sessionState?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
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

    /** Removes every tab of a browser session from memory and closes their GeckoSessions (used when deleting a session). */
    fun destroySession(sessionId: String) {
        tabsFor(sessionId).forEach { t ->
            tabs.remove(t.id)
            closeSession(t)
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

    // ------------------------------------------------------------------ GeckoSession lifecycle

    /**
     * Returns the tab's open GeckoSession, creating it (bound to the browser session's contextId)
     * when needed. With [loadContent] the saved state is restored or the URL is loaded.
     */
    fun ensureSession(tab: Tab, loadContent: Boolean = true): GeckoSession {
        tab.geckoSession?.let { existing ->
            if (existing.isOpen) return existing
            // A session Gecko closed (content process gone) can be reopened as-is.
            existing.open(core.engine.runtime)
            if (loadContent) restoreOrLoad(tab, existing)
            return existing
        }
        val session = core.sessions.get(tab.sessionId)
            ?: throw IllegalStateException("Session ${tab.sessionId} not found for tab ${tab.id}")
        val gs = SessionFactory.create(core, tab, session)
        gs.open(core.engine.runtime)
        tab.geckoSession = gs
        AppLog.i(TAG, "GeckoSession opened tab=${tab.id.take(8)} context=${core.isolation.contextId(session).take(16)} live=${liveCount()}")
        if (loadContent) restoreOrLoad(tab, gs)
        return gs
    }

    private fun restoreOrLoad(tab: Tab, gs: GeckoSession) {
        val state = tab.savedState
        if (state != null) {
            try {
                gs.restoreState(state)
                return
            } catch (t: Throwable) {
                AppLog.w(TAG, "restoreState failed", t)
                tab.savedState = null
            }
        }
        if (!tab.isStartPage) gs.load(GeckoSession.Loader().uri(tab.url))
    }

    /** Keeps the (already up-to-date) SessionState and closes the GeckoSession; the tab can be re-opened later. */
    fun hibernate(tab: Tab) {
        if (tab.geckoSession == null) return
        closeSession(tab)
        AppLog.i(TAG, "Tab hibernated ${tab.id.take(8)} live=${liveCount()}")
    }

    fun closeSession(tab: Tab) {
        val gs = tab.geckoSession ?: return
        host?.onSessionClosing(tab)
        tab.geckoSession = null
        tab.isLoading = false
        tab.awaitingDisplay = false
        try {
            if (gs.isOpen) gs.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "close failed", t)
        }
        AppLog.d(TAG, "GeckoSession closed tab=${tab.id.take(8)}")
    }

    /** Marks [displayed] active/focused and every other live session inactive (saves CPU; audio keeps playing). */
    fun setDisplayed(displayed: Tab?) {
        tabs.values.forEach { t ->
            val gs = t.geckoSession ?: return@forEach
            if (!gs.isOpen) return@forEach
            val isIt = t === displayed
            try {
                gs.setActive(isIt)
                gs.setFocused(isIt)
            } catch (t2: Throwable) {
                AppLog.w(TAG, "setActive failed", t2)
            }
        }
    }

    /** Keeps at most [Prefs.liveTabLimit] GeckoSessions open (the active one always survives). */
    fun enforceLiveLimit(activeTabId: String?) {
        val keepBackground = (Prefs.liveTabLimit - 1).coerceAtLeast(0)
        val background = tabs.values
            .filter { it.geckoSession != null && it.id != activeTabId && !it.awaitingDisplay }
            .sortedByDescending { it.lastActiveAt }
        background.drop(keepBackground).forEach { hibernate(it) }
    }

    fun onTrimMemory(level: Int) {
        val activeId = core.sessions.activeId?.let { activeTab(it)?.id }
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> hibernateAll(exceptTabId = activeId) // keep the visible/last tab (may be playing audio)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> hibernateAll(exceptTabId = activeId)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                tabs.values.filter { it.geckoSession != null && it.id != activeId }
                    .sortedByDescending { it.lastActiveAt }.drop(1).forEach { hibernate(it) }
            }
        }
    }

    private fun hibernateAll(exceptTabId: String?) {
        tabs.values.filter { it.geckoSession != null && it.id != exceptTabId }.forEach { hibernate(it) }
    }

    /** Re-applies user settings (JS, UA, zoom, cookies...) to the runtime and every live session after Settings changed. */
    fun reapplySettings() {
        core.engine.applyGlobalSettings()
        tabs.values.forEach { tab ->
            val session = core.sessions.get(tab.sessionId) ?: return@forEach
            SessionFactory.applyTabSettings(tab, session)
        }
    }

    /** Called when the Activity is destroyed: drop Activity references held by live sessions (no leaks). */
    fun detachFromActivity() {
        tabs.values.forEach { tab ->
            val gs = tab.geckoSession ?: return@forEach
            try { gs.selectionActionDelegate = null } catch (_: Throwable) {}
        }
    }

    /** The tab's content process crashed or was killed; Gecko already closed the GeckoSession. */
    fun onContentProcessGone(tab: Tab, crashed: Boolean) {
        host?.onSessionClosing(tab)
        val gs = tab.geckoSession
        tab.geckoSession = null
        tab.isLoading = false
        tab.awaitingDisplay = false
        try { if (gs != null && gs.isOpen) gs.close() } catch (_: Throwable) {}
        if (crashed) tab.error = PageError(ERROR_RENDERER_GONE, 0, "Content process terminated", tab.url)
        notifyTabUpdated(tab)
        host?.onContentProcessGone(tab, crashed)
    }

    companion object {
        private const val TAG = "Tabs"
        private const val MAX_CLOSED = 20
        const val ERROR_RENDERER_GONE = -1000
    }
}
