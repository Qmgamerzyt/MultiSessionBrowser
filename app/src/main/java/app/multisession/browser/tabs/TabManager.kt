package app.multisession.browser.tabs

import android.content.ComponentCallbacks2
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.HistoryEntity
import app.multisession.browser.data.db.TabEntity
import app.multisession.browser.data.db.TabGroupEntity
import app.multisession.browser.engine.BrowserHost
import app.multisession.browser.engine.SessionFactory
import app.multisession.browser.permissions.SitePermissionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns every Tab and TabGroup of every browser session (in memory) and the GeckoSession lifecycle:
 *  - displayed tab: GeckoSession open, active and attached to the Activity's GeckoView
 *  - background tabs: GeckoSession kept open (inactive) up to [Prefs.liveTabLimit], most recently used first
 *  - beyond the limit / under memory pressure: SessionState kept -> session.close(); re-opened lazily
 * Tab metadata + SessionState + groups are persisted to Room so everything is restored after process death.
 * Nothing here destroys/recreates sessions during ordinary tab switching.
 *
 * Ordering model: [Tab.position] is the single order inside a browser session. Grouped tabs are kept contiguous
 * (in group order, groups first, ungrouped tabs last) by [normalizeOrder]; the tabs UI shows exactly that order.
 */
class TabManager(private val core: BrowserCore) {

    interface Listener {
        fun onTabsChanged(sessionId: String) {}
        fun onTabUpdated(tab: Tab) {}
    }

    private val tabs = LinkedHashMap<String, Tab>()
    private val groups = LinkedHashMap<String, TabGroup>()
    private val recentlyClosed = ArrayDeque<TabEntity>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** The foreground Activity (set in onStart / cleared in onStop). UI callbacks from the engine go through it. */
    var host: BrowserHost? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    // ------------------------------------------------------------------ queries

    fun loadFromDb(restored: List<Tab>, restoredGroups: List<TabGroupEntity>) {
        tabs.clear()
        groups.clear()
        restoredGroups.sortedBy { it.position }.forEach { groups[it.id] = TabGroup.from(it) }
        restored.sortedBy { it.position }.forEach { t ->
            if (t.groupId != null && groups[t.groupId!!]?.sessionId != t.sessionId) t.groupId = null   // orphan -> ungrouped, tab kept
            tabs[t.id] = t
        }
        AppLog.i(TAG, "Restored ${tabs.size} tabs and ${groups.size} groups from database")
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

    // ------------------------------------------------------------------ groups

    fun group(id: String?): TabGroup? = id?.let { groups[it] }
    fun groupsFor(sessionId: String): List<TabGroup> = groups.values.filter { it.sessionId == sessionId }.sortedBy { it.position }
    fun tabsInGroup(groupId: String): List<Tab> = tabs.values.filter { it.groupId == groupId }.sortedBy { it.position }
    fun ungroupedTabs(sessionId: String): List<Tab> = tabsFor(sessionId).filter { it.groupId == null }

    fun createGroup(sessionId: String, name: String, color: Int): TabGroup {
        val pos = (groupsFor(sessionId).maxOfOrNull { it.position } ?: -1) + 1
        val g = TabGroup(UUID.randomUUID().toString(), sessionId, name.trim().ifEmpty { "Group ${pos + 1}" }, color, pos, false, System.currentTimeMillis())
        groups[g.id] = g
        persistGroup(g)
        AppLog.i(TAG, "Group created ${g.id.take(8)} in ${sessionId.take(8)}")
        notifyTabsChanged(sessionId)
        return g
    }

    fun renameGroup(groupId: String, name: String, color: Int? = null) {
        val g = groups[groupId] ?: return
        if (name.isNotBlank()) g.name = name.trim()
        if (color != null) g.color = color
        persistGroup(g)
        notifyTabsChanged(g.sessionId)
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) {
        val g = groups[groupId] ?: return
        if (g.collapsed == collapsed) return
        g.collapsed = collapsed
        persistGroup(g)
        notifyTabsChanged(g.sessionId)
    }

    fun moveGroup(sessionId: String, from: Int, to: Int) {
        val list = groupsFor(sessionId).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        list.forEachIndexed { i, g -> g.position = i }
        core.persist { core.repo.tabGroups.upsertAll(list.map { it.toEntity() }) }
        normalizeOrder(sessionId)
        persistSession(sessionId)
        notifyTabsChanged(sessionId)
    }

    /** Puts [tabId] into [groupId] (null = ungroup). The tab moves to the end of its new group. */
    fun setTabGroup(tabId: String, groupId: String?) {
        val tab = tabs[tabId] ?: return
        val g = groupId?.let { groups[it] }
        if (groupId != null && (g == null || g.sessionId != tab.sessionId)) return
        if (tab.groupId == groupId) return
        tab.groupId = groupId
        tab.position = Int.MAX_VALUE / 2 + tab.position   // sorts last inside the destination group, keeps relative order
        normalizeOrder(tab.sessionId)
        persistSession(tab.sessionId)
        notifyTabsChanged(tab.sessionId)
    }

    /** Ungroups every tab (tabs are kept!) and deletes the group. */
    fun ungroup(groupId: String) {
        val g = groups.remove(groupId) ?: return
        tabsInGroup(groupId).forEach { it.groupId = null }
        // tabsInGroup is empty now (groupId cleared), so iterate the session instead
        core.persist { core.repo.tabs.clearGroup(groupId); core.repo.tabGroups.delete(groupId) }
        normalizeOrder(g.sessionId)
        persistSession(g.sessionId)
        AppLog.i(TAG, "Group ungrouped ${groupId.take(8)}")
        notifyTabsChanged(g.sessionId)
    }

    /** Closes every tab of the group. The group itself is KEPT (empty groups persist by design). */
    fun closeGroupTabs(groupId: String): List<Tab> {
        val g = groups[groupId] ?: return emptyList()
        val closed = tabsInGroup(groupId)
        closed.forEach { t ->
            tabs.remove(t.id)
            closeSession(t)
            if (!t.isStartPage) rememberClosed(t.toEntity())
            core.persist { core.repo.tabs.delete(t.id) }
        }
        normalizeOrder(g.sessionId)
        persistSession(g.sessionId)
        notifyTabsChanged(g.sessionId)
        return closed
    }

    /** Deletes the group AND closes its tabs (explicit user action only). */
    fun deleteGroupWithTabs(groupId: String) {
        closeGroupTabs(groupId)
        val g = groups.remove(groupId) ?: return
        core.persist { core.repo.tabGroups.delete(groupId) }
        AppLog.i(TAG, "Group deleted ${groupId.take(8)}")
        notifyTabsChanged(g.sessionId)
    }

    /**
     * New order coming from the tabs UI after a drag: [ordered] lists every tab of the session (top to bottom)
     * with the group it now belongs to. Positions and memberships are rewritten and persisted atomically.
     */
    fun applyOrder(sessionId: String, ordered: List<Pair<String, String?>>) {
        val known = tabsFor(sessionId).associateBy { it.id }
        if (ordered.size != known.size || !ordered.all { known.containsKey(it.first) }) {
            AppLog.w(TAG, "applyOrder ignored: list does not match session tabs"); return
        }
        ordered.forEachIndexed { i, (id, gid) ->
            val t = known.getValue(id)
            t.position = i
            t.groupId = gid?.takeIf { groups[it]?.sessionId == sessionId }
        }
        normalizeOrder(sessionId)
        persistSession(sessionId)
        notifyTabsChanged(sessionId)
    }

    /** Groups first (group order, tabs keep their relative order), then ungrouped tabs. Positions become 0..n-1. */
    private fun normalizeOrder(sessionId: String) {
        val gIndex = groupsFor(sessionId).withIndex().associate { it.value.id to it.index }
        tabsFor(sessionId)
            .sortedWith(compareBy<Tab> { t -> t.groupId?.let { gIndex[it] } ?: Int.MAX_VALUE }.thenBy { it.position })
            .forEachIndexed { i, t -> t.position = i }
    }

    private fun persistGroup(g: TabGroup) {
        val e = g.toEntity()
        core.persist { core.repo.tabGroups.upsert(e) }
    }

    // ------------------------------------------------------------------ mutations

    fun createTab(
        sessionId: String,
        url: String = UrlUtils.START_PAGE,
        select: Boolean = true,
        openerTabId: String? = null,
        groupId: String? = null,
    ): Tab {
        val siblings = tabsFor(sessionId)
        val opener = openerTabId?.let { id -> siblings.firstOrNull { it.id == id } }
        val insertAt = opener?.let { it.position + 1 } ?: siblings.size
        siblings.filter { it.position >= insertAt }.forEach { it.position += 1 }
        val tab = Tab(UUID.randomUUID().toString(), sessionId, insertAt).apply {
            this.url = url
            this.openerTabId = openerTabId
            this.desktopMode = core.sessions.get(sessionId)?.desktopMode ?: false
            this.groupId = (groupId ?: opener?.groupId)?.takeIf { groups[it]?.sessionId == sessionId }
        }
        tabs[tab.id] = tab
        normalizeOrder(sessionId)
        persistSession(sessionId)
        AppLog.i(TAG, "Tab created ${tab.id.take(8)} session=${sessionId.take(8)} group=${tab.groupId?.take(8)} startPage=${tab.isStartPage}")
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

    /** A tab requested by a WebExtension (browser.tabs.create): opened by Gecko, shown when it starts loading. */
    fun createExtensionTab(sessionId: String, uri: String?): Tab? {
        val session = core.sessions.get(sessionId) ?: return null
        val tab = createTab(sessionId, uri ?: UrlUtils.START_PAGE, select = false)
        tab.geckoSession = SessionFactory.create(core, tab, session)
        tab.awaitingDisplay = true
        return tab
    }

    fun selectTab(tab: Tab) {
        tab.lastActiveAt = System.currentTimeMillis()
        core.sessions.setActiveTab(tab.sessionId, tab.id)
        persistTab(tab)
    }

    /** Closes a tab and returns the tab that should become active next (null if the session is now empty). The tab's group is kept. */
    fun closeTab(tabId: String): Tab? {
        val tab = tabs.remove(tabId) ?: return null
        val sessionId = tab.sessionId
        val oldIndex = tab.position
        closeSession(tab)
        if (!tab.isStartPage) rememberClosed(tab.toEntity())
        normalizeOrder(sessionId)
        val remaining = tabsFor(sessionId)
        core.persist { core.repo.tabs.delete(tabId) }
        persistSession(sessionId)
        AppLog.i(TAG, "Tab closed ${tabId.take(8)}; ${remaining.size} left in session (group ${tab.groupId?.take(8)} kept)")
        notifyTabsChanged(sessionId)
        val opener = get(tab.openerTabId)?.takeIf { it.sessionId == sessionId }
        val sameGroup = tab.groupId?.let { gid -> remaining.filter { it.groupId == gid } }?.takeIf { it.isNotEmpty() }
        return opener
            ?: sameGroup?.minByOrNull { kotlin.math.abs(it.position - oldIndex) }
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
        AppLog.i(TAG, "All tabs closed in ${sessionId.take(8)} (groups kept)")
        notifyTabsChanged(sessionId)
    }

    fun reopenClosedTab(sessionId: String): Tab? {
        val e = recentlyClosed.firstOrNull { it.sessionId == sessionId } ?: return null
        recentlyClosed.remove(e)
        val tab = createTab(sessionId, e.url, select = true, groupId = e.groupId)
        tab.title = e.title
        tab.desktopMode = e.desktopMode
        tab.savedState = e.sessionState?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        return tab
    }

    /** Reorders inside the flat list of a session (legacy entry point; the tabs UI uses [applyOrder]). */
    fun moveTab(sessionId: String, from: Int, to: Int) {
        val list = tabsFor(sessionId).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        list.forEachIndexed { i, t -> t.position = i }
        normalizeOrder(sessionId)
        persistSession(sessionId)
        notifyTabsChanged(sessionId)
    }

    /** Removes every tab and group of a browser session from memory and closes their GeckoSessions (used when deleting a session). */
    fun destroySession(sessionId: String) {
        tabsFor(sessionId).forEach { t ->
            tabs.remove(t.id)
            closeSession(t)
        }
        groups.values.filter { it.sessionId == sessionId }.map { it.id }.forEach { groups.remove(it) }
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

    // SessionState -> JSON is the expensive part of persisting a tab and it runs on every title change and
    // navigation: the snapshot is taken on the main thread, serialised on Default, written by Room on IO.
    fun persistTab(tab: Tab) {
        val snap = tab.snapshot()
        core.persist {
            val e = withContext(Dispatchers.Default) { snap.toEntity() }
            core.repo.tabs.upsert(e)
        }
    }

    fun persistAll() = persistSnapshots(tabs.values.map { it.snapshot() })

    private fun persistSession(sessionId: String) = persistSnapshots(tabsFor(sessionId).map { it.snapshot() })

    private fun persistSnapshots(snaps: List<TabSnapshot>) {
        if (snaps.isEmpty()) return
        core.persist {
            val entities = withContext(Dispatchers.Default) { snaps.map { it.toEntity() } }
            core.repo.tabs.upsertAll(entities)
        }
    }

    private fun rememberClosed(e: TabEntity) {
        recentlyClosed.addFirst(e)
        while (recentlyClosed.size > MAX_CLOSED) recentlyClosed.removeLast()
    }

    fun notifyTabUpdated(tab: Tab) = listeners.forEach { it.onTabUpdated(tab) }
    fun notifyTabsChanged(sessionId: String) = listeners.forEach { it.onTabsChanged(sessionId) }

    // ------------------------------------------------------------------ scripts / media

    /**
     * Runs [source] (a javascript: URL or bare script) in the page of [tab] as a bookmarklet. The script is wrapped
     * so its completion value is undefined: Gecko therefore never replaces the document with a returned string.
     * Only this app-initiated load passes NavigationDelegate.onLoadRequest (see [Tab.pendingScript]).
     */
    fun runScript(tab: Tab, source: String): Boolean {
        val gs = tab.geckoSession?.takeIf { it.isOpen } ?: return false
        if (tab.isStartPage) return false
        var body = source.trim()
        if (body.lowercase().startsWith("javascript:")) body = body.substring("javascript:".length).trim()
        if (body.isEmpty()) return false
        // '#' would start a URL fragment and line breaks are illegal in a URL; everything else stays as typed so
        // pre-encoded bookmarklets (%20 ...) are not double-encoded.
        val safe = body.replace("%0A", "\n").replace("#", "%23").replace("\r", "%0D").replace("\n", "%0A").replace("\t", "%09")
        val wrapped = "javascript:(function(){try{$safe\n}catch(e){console.error('[bookmarklet]',e)}})();void 0"
        tab.pendingScript = wrapped
        try {
            gs.load(GeckoSession.Loader().uri(wrapped))
        } catch (t: Throwable) {
            tab.pendingScript = null
            AppLog.w(TAG, "runScript failed", t)
            return false
        }
        return true
    }

    /**
     * The user blocked camera/microphone for [origin] in [sessionId]: every live page of that site is reloaded, which
     * tears down its MediaStream tracks (the only way to end a granted getUserMedia stream from outside the page in
     * GeckoView) and makes the site re-request the permission, which the stored BLOCK rule now denies.
     */
    fun revokeMedia(sessionId: String, origin: String): Int {
        var n = 0
        tabs.values.filter { it.sessionId == sessionId && it.geckoSession?.isOpen == true && SitePermissionStore.originOf(it.url) == origin }
            .forEach { t ->
                try { t.geckoSession?.reload(); n++ } catch (e: Throwable) { AppLog.w(TAG, "revoke reload failed", e) }
            }
        if (n > 0) AppLog.i(TAG, "Media revoked for $origin: $n page(s) reloaded")
        return n
    }

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
        // Most recently used first; a tab that is still loading is kept in preference to an idle one so a
        // page opened in the background is not frozen half-way and reloaded from scratch later.
        val background = tabs.values
            .filter { it.geckoSession != null && it.id != activeTabId && !it.awaitingDisplay }
            .sortedWith(compareByDescending<Tab> { it.isLoading }.thenByDescending { it.lastActiveAt })
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

    /**
     * Re-applies user settings (JS, UA, zoom, cookies...) to the runtime and every live session - but only
     * when a preference actually changed since the last call.
     */
    fun reapplySettings() {
        if (!core.settingsDirty) return
        core.settingsDirty = false
        AppLog.i(TAG, "Preferences changed: re-applying engine settings")
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
