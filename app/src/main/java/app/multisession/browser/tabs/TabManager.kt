package app.multisession.browser.tabs

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.ClosedGroupEntity
import app.multisession.browser.data.db.ClosedTabEntity
import app.multisession.browser.data.db.HistoryEntity
import app.multisession.browser.data.db.TabGroupEntity
import app.multisession.browser.engine.BrowserHost
import app.multisession.browser.engine.PageScale
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
 *  - beyond the limit / idle for long / under memory pressure: SessionState kept -> session.close(); re-opened lazily
 *    (tabs playing media or holding a camera/microphone stream are never hibernated automatically)
 *  - archived tabs: hidden from the normal presentation, GeckoSession released, all metadata kept
 * Tab metadata + SessionState + groups + recently closed tabs/groups are persisted to Room so everything is
 * restored after process death. Nothing here destroys/recreates sessions during ordinary tab switching.
 *
 * Ordering model: [Tab.position] is the single order inside a browser session. [normalizeOrder] keeps it in the
 * shape the grid shows: pinned tabs first, then grouped tabs (contiguous, in group order), then ungrouped tabs.
 * Archived tabs are not part of that order (they keep their last position for restoring).
 */
class TabManager(private val core: BrowserCore) {

    interface Listener {
        fun onTabsChanged(sessionId: String) {}
        fun onTabUpdated(tab: Tab) {}
    }

    private val tabs = LinkedHashMap<String, Tab>()          // every tab, archived included
    private val groups = LinkedHashMap<String, TabGroup>()
    private val closedTabs = ArrayList<ClosedTabEntity>()      // newest first, all sessions (persisted mirror)
    private val closedGroups = ArrayList<ClosedGroupEntity>()
    /** SessionState objects of tabs closed in THIS process (no JSON round trip needed to reopen them). */
    private val closedStates = HashMap<String, GeckoSession.SessionState>()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val thumbnailOwners = ArrayList<String>()        // LRU of tab ids currently holding a thumbnail bitmap
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The foreground Activity (set in onStart / cleared in onStop). UI callbacks from the engine go through it. */
    var host: BrowserHost? = null

    /** Tab whose GeckoSession is (or was last) attached to the GeckoView; null while the start page shows. */
    private var displayedTab: Tab? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    // ------------------------------------------------------------------ restore

    fun loadFromDb(restored: List<Tab>, restoredGroups: List<TabGroupEntity>, closed: List<ClosedTabEntity>, closedGroupList: List<ClosedGroupEntity>) {
        tabs.clear(); groups.clear(); closedTabs.clear(); closedGroups.clear()
        restoredGroups.sortedBy { it.position }.forEach { groups[it.id] = TabGroup.from(it) }
        restored.sortedBy { it.position }.forEach { t ->
            if (t.groupId != null && groups[t.groupId!!]?.sessionId != t.sessionId) t.groupId = null   // orphan -> ungrouped, tab kept
            tabs[t.id] = t
        }
        closedTabs.addAll(closed.sortedByDescending { it.closedAt })
        closedGroups.addAll(closedGroupList.sortedByDescending { it.closedAt })
        // Positions are re-normalised per session once so a half-written order never shows up as a scrambled grid.
        tabs.values.map { it.sessionId }.distinct().forEach { normalizeOrder(it) }
        AppLog.i(TAG, "Restored ${tabs.size} tabs (${tabs.values.count { it.archived }} archived), ${groups.size} groups, ${closedTabs.size} closed tabs, ${closedGroups.size} closed groups")
        scheduleIdleSweep()
    }

    // ------------------------------------------------------------------ queries

    fun get(id: String?): Tab? = id?.let { tabs[it] }

    /** Visible (non-archived) tabs of a session in display order. */
    fun tabsFor(sessionId: String): List<Tab> =
        tabs.values.filter { it.sessionId == sessionId && !it.archived }.sortedBy { it.position }

    /** Every tab of a session, archived ones included. */
    fun allTabsFor(sessionId: String): List<Tab> = tabs.values.filter { it.sessionId == sessionId }.sortedBy { it.position }

    fun archivedTabsFor(sessionId: String): List<Tab> =
        tabs.values.filter { it.sessionId == sessionId && it.archived }.sortedWith(compareByDescending<Tab> { it.archivedAt ?: 0L }.thenBy { it.position })

    fun countFor(sessionId: String): Int = tabs.values.count { it.sessionId == sessionId && !it.archived }
    fun archivedCountFor(sessionId: String): Int = tabs.values.count { it.sessionId == sessionId && it.archived }
    /** Every in-memory tab of every session (init diagnostics report, v2.2.0-beta-1). */
    fun countAll(): Int = tabs.size

    fun activeTab(sessionId: String): Tab? {
        val preferred = get(core.sessions.get(sessionId)?.activeTabId)?.takeIf { it.sessionId == sessionId && !it.archived }
        return preferred ?: tabsFor(sessionId).maxByOrNull { it.lastActiveAt }
    }

    fun findBySession(session: GeckoSession): Tab? = tabs.values.firstOrNull { it.geckoSession === session }

    /**
     * Every tab that currently holds a GeckoSession paired with it (the same population as
     * [liveCount]). v2.1.8: lets [ExtensionManager.reattachAll] reach the sessions that were created
     * before the extension list arrived and (re-)apply their delegates and the active-tab marker.
     */
    fun liveSessions(): List<Pair<Tab, GeckoSession>> =
        tabs.values.mapNotNull { t -> t.geckoSession?.let { t to it } }

    /** The GeckoSession behind the GeckoView right now, null while the start page is on screen. */
    fun displayedSession(): GeckoSession? = displayedTab?.geckoSession

    /** The tab behind the GeckoView right now, null while the start page is on screen. Exposed as a
     *  read-only view on purpose: [displayedTab] is only ever written by [setDisplayed]. */
    fun displayedTabOrNull(): Tab? = displayedTab

    fun liveCount(): Int = tabs.values.count { it.geckoSession != null }

    fun pinnedTabs(sessionId: String): List<Tab> = tabsFor(sessionId).filter { it.pinned }

    // ------------------------------------------------------------------ groups

    fun group(id: String?): TabGroup? = id?.let { groups[it] }
    fun groupsFor(sessionId: String): List<TabGroup> = groups.values.filter { it.sessionId == sessionId }.sortedBy { it.position }
    /** Visible tabs of a group (pinned members included; the grid filters them into the pinned section itself). */
    fun tabsInGroup(groupId: String): List<Tab> = tabs.values.filter { it.groupId == groupId && !it.archived }.sortedBy { it.position }
    fun ungroupedTabs(sessionId: String): List<Tab> = tabsFor(sessionId).filter { it.groupId == null && !it.pinned }

    fun createGroup(sessionId: String, name: String, color: Int, id: String? = null, collapsed: Boolean = false): TabGroup {
        val pos = (groupsFor(sessionId).maxOfOrNull { it.position } ?: -1) + 1
        val gid = id?.takeIf { groups[it] == null } ?: UUID.randomUUID().toString()
        val g = TabGroup(gid, sessionId, name.trim().ifEmpty { "Group ${pos + 1}" }, color, pos, collapsed, System.currentTimeMillis())
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

    /**
     * v2.1.10 (A9): group headers reordered by a grid drag. [orderedIds] must list every group of
     * the session exactly once, top to bottom; positions are rewritten and persisted atomically
     * (mirrors moveGroup - the tabs themselves keep their membership and positions inside groups).
     */
    fun applyGroupOrder(sessionId: String, orderedIds: List<String>) {
        val list = groupsFor(sessionId)
        if (orderedIds.size != list.size || orderedIds.toSet() != list.map { it.id }.toSet()) {
            AppLog.w(TAG, "applyGroupOrder ignored: ids do not match session groups"); return
        }
        val byId = list.associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId.getValue(id).position = i }
        core.persist { core.repo.tabGroups.upsertAll(list.map { it.toEntity() }) }
        normalizeOrder(sessionId)
        persistSession(sessionId)
        notifyTabsChanged(sessionId)
    }

    /** Puts [tabId] into [groupId] (null = ungroup). The tab moves to the end of its new group. */
    fun setTabGroup(tabId: String, groupId: String?) = setTabsGroup(listOf(tabId), groupId)

    /** Bulk variant of [setTabGroup]; tabs of other sessions than the group's are ignored. */
    fun setTabsGroup(tabIds: Collection<String>, groupId: String?) {
        val g = groupId?.let { groups[it] }
        if (groupId != null && g == null) return
        val touched = HashSet<String>()
        tabIds.mapNotNull { tabs[it] }.forEach { tab ->
            if (g != null && g.sessionId != tab.sessionId) return@forEach
            if (tab.groupId == groupId) return@forEach
            tab.groupId = groupId
            tab.position = Int.MAX_VALUE / 2 + tab.position   // sorts last inside the destination, keeps relative order
            touched += tab.sessionId
        }
        touched.forEach { sid -> normalizeOrder(sid); persistSession(sid); notifyTabsChanged(sid) }
    }

    /** Ungroups every tab (tabs are kept, archived ones included) and deletes the group. */
    fun ungroup(groupId: String) {
        val g = groups.remove(groupId) ?: return
        tabs.values.filter { it.groupId == groupId }.forEach { it.groupId = null }
        core.persist { core.repo.tabs.clearGroup(groupId); core.repo.tabGroups.delete(groupId) }
        normalizeOrder(g.sessionId)
        persistSession(g.sessionId)
        AppLog.i(TAG, "Group ungrouped ${groupId.take(8)}")
        notifyTabsChanged(g.sessionId)
    }

    /** Closes every visible tab of the group. The group itself is KEPT (empty groups persist by design). */
    fun closeGroupTabs(groupId: String): List<Tab> {
        val g = groups[groupId] ?: return emptyList()
        return closeTabs(tabsInGroup(groupId).map { it.id }, sessionIdHint = g.sessionId)
    }

    /** Deletes the group AND closes its tabs (explicit user action only). Group + tabs go to "Recently closed" together. */
    fun deleteGroupWithTabs(groupId: String) {
        val g = groups[groupId] ?: return
        val now = System.currentTimeMillis()
        val members = tabsInGroup(groupId)
        val closedGroup = ClosedGroupEntity(g.id, g.sessionId, g.name, g.color, g.position, g.collapsed, g.createdAt, now)
        closedGroups.add(0, closedGroup)
        while (closedGroups.size > MAX_CLOSED_GROUPS) closedGroups.removeAt(closedGroups.size - 1)
        core.persist { core.repo.closedGroups.upsert(closedGroup); core.repo.closedGroups.trim(MAX_CLOSED_GROUPS) }
        closeTabs(members.map { it.id }, sessionIdHint = g.sessionId, closedGroupId = g.id)
        groups.remove(groupId)
        tabs.values.filter { it.groupId == groupId }.forEach { it.groupId = null }   // archived members stay archived, now ungrouped
        core.persist { core.repo.tabs.clearGroup(groupId); core.repo.tabGroups.delete(groupId) }
        AppLog.i(TAG, "Group deleted ${groupId.take(8)} (${members.size} tabs -> recently closed)")
        notifyTabsChanged(g.sessionId)
    }

    /**
     * New order coming from the tab grid after a drag: [ordered] lists every VISIBLE tab of the session (top to
     * bottom) with the group it now belongs to. Positions and memberships are rewritten and persisted atomically;
     * the pinned flag is never changed by reordering.
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

    /** Pinned first, then groups (group order, tabs keep their relative order), then ungrouped tabs. Positions become 0..n-1. */
    private fun normalizeOrder(sessionId: String) {
        val gIndex = groupsFor(sessionId).withIndex().associate { it.value.id to it.index }
        tabsFor(sessionId)
            .sortedWith(
                compareBy<Tab> { if (it.pinned) 0 else 1 }
                    .thenBy { t -> if (t.pinned) 0 else t.groupId?.let { gIndex[it] } ?: Int.MAX_VALUE }
                    .thenBy { it.position }
            )
            .forEachIndexed { i, t -> t.position = i }
    }

    private fun persistGroup(g: TabGroup) {
        val e = g.toEntity()
        core.persist { core.repo.tabGroups.upsert(e) }
    }

    // ------------------------------------------------------------------ pinning / archive

    fun setPinned(tabIds: Collection<String>, pinned: Boolean) {
        val touched = HashSet<String>()
        tabIds.mapNotNull { tabs[it] }.forEach { t ->
            if (t.pinned == pinned) return@forEach
            t.pinned = pinned
            t.position = Int.MAX_VALUE / 2 + t.position   // newly (un)pinned tabs go to the end of their new section
            touched += t.sessionId
        }
        touched.forEach { sid -> normalizeOrder(sid); persistSession(sid); notifyTabsChanged(sid) }
    }

    /** Hides the tabs from the normal presentation and releases their GeckoSessions; URL/state/group/order/pinned are kept. */
    fun archiveTabs(tabIds: Collection<String>): List<Tab> {
        val now = System.currentTimeMillis()
        val archived = tabIds.mapNotNull { tabs[it] }.filter { !it.archived }
        archived.forEach { t ->
            hibernate(t)
            t.archived = true
            t.archivedAt = now
            t.error = null
        }
        archived.map { it.sessionId }.distinct().forEach { sid -> normalizeOrder(sid); persistSnapshots(allTabsFor(sid).map { it.snapshot() }); notifyTabsChanged(sid) }
        if (archived.isNotEmpty()) AppLog.i(TAG, "Archived ${archived.size} tab(s)")
        return archived
    }

    /** Brings archived tabs back (end of their pinned/group/ungrouped section). Lost groups simply mean "ungrouped". */
    fun unarchiveTabs(tabIds: Collection<String>): List<Tab> {
        val restored = tabIds.mapNotNull { tabs[it] }.filter { it.archived }
        restored.forEach { t ->
            t.archived = false
            t.archivedAt = null
            t.lastActiveAt = System.currentTimeMillis()
            if (t.groupId != null && groups[t.groupId!!]?.sessionId != t.sessionId) t.groupId = null
            t.position = Int.MAX_VALUE / 2 + t.position
        }
        restored.map { it.sessionId }.distinct().forEach { sid -> normalizeOrder(sid); persistSnapshots(allTabsFor(sid).map { it.snapshot() }); notifyTabsChanged(sid) }
        return restored
    }

    /** Permanently removes archived tabs (they still go to "Recently closed"). */
    fun deleteArchived(tabIds: Collection<String>) = closeTabs(tabIds.filter { tabs[it]?.archived == true })

    // ------------------------------------------------------------------ mutations

    fun createTab(
        sessionId: String,
        url: String = UrlUtils.START_PAGE,
        select: Boolean = true,
        openerTabId: String? = null,
        groupId: String? = null,
        pinned: Boolean = false,
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
            this.pinned = pinned
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
        val tab = tabs[tabId] ?: return null
        val sessionId = tab.sessionId
        val oldIndex = tab.position
        val wasArchived = tab.archived
        closeTabs(listOf(tabId), sessionIdHint = sessionId)
        if (wasArchived) return activeTab(sessionId)
        val remaining = tabsFor(sessionId)
        val opener = get(tab.openerTabId)?.takeIf { it.sessionId == sessionId && !it.archived }
        val sameGroup = tab.groupId?.let { gid -> remaining.filter { it.groupId == gid } }?.takeIf { it.isNotEmpty() }
        return opener
            ?: sameGroup?.minByOrNull { kotlin.math.abs(it.position - oldIndex) }
            ?: remaining.getOrNull(oldIndex.coerceAtMost(remaining.size - 1))
            ?: remaining.lastOrNull()
    }

    /** Closes several tabs at once (bulk action / group action). Every non-start-page tab goes to "Recently closed". */
    fun closeTabs(tabIds: Collection<String>, sessionIdHint: String? = null, closedGroupId: String? = null): List<Tab> {
        val closed = tabIds.mapNotNull { tabs.remove(it) }
        if (closed.isEmpty()) return closed
        closed.forEach { t ->
            closeSession(t)
            dropThumbnail(t)
            rememberClosed(t, closedGroupId)
        }
        val ids = closed.map { it.id }
        core.persist { core.repo.tabs.deleteAll(ids) }
        (closed.map { it.sessionId } + listOfNotNull(sessionIdHint)).distinct().forEach { sid ->
            normalizeOrder(sid)
            persistSession(sid)
            notifyTabsChanged(sid)
        }
        AppLog.i(TAG, "Closed ${closed.size} tab(s); ${closed.firstOrNull()?.let { countFor(it.sessionId) } ?: 0} left in session (groups kept)")
        return closed
    }

    fun closeAllTabs(sessionId: String) {
        closeTabs(tabsFor(sessionId).map { it.id }, sessionIdHint = sessionId)
        AppLog.i(TAG, "All tabs closed in ${sessionId.take(8)} (groups + archived tabs kept)")
    }

    // ------------------------------------------------------------------ recently closed (persisted)

    fun hasRecentlyClosed(sessionId: String): Boolean = closedTabs.any { it.sessionId == sessionId } || closedGroups.any { it.sessionId == sessionId }
    fun recentlyClosedTabs(sessionId: String): List<ClosedTabEntity> = closedTabs.filter { it.sessionId == sessionId }
    fun recentlyClosedGroups(sessionId: String): List<ClosedGroupEntity> = closedGroups.filter { it.sessionId == sessionId }

    private fun rememberClosed(tab: Tab, closedGroupId: String?) {
        if (tab.isStartPage) return
        val g = tab.groupId?.let { groups[it] }
        val e = ClosedTabEntity(
            id = tab.id, sessionId = tab.sessionId, url = tab.url, title = tab.title, groupId = tab.groupId,
            groupName = g?.name, groupColor = g?.color, position = tab.position, pinned = tab.pinned, desktopMode = tab.desktopMode,
            sessionState = null, closedGroupId = closedGroupId, closedAt = System.currentTimeMillis(),
        )
        closedTabs.removeAll { it.id == e.id }
        closedTabs.add(0, e)
        tab.savedState?.let { closedStates[e.id] = it }
        while (closedTabs.size > MAX_CLOSED) { val old = closedTabs.removeAt(closedTabs.size - 1); closedStates.remove(old.id) }
        val snap = tab.snapshot()
        core.persist {
            val json = withContext(Dispatchers.Default) { snap.stateJson() }
            core.repo.closedTabs.upsert(e.copy(sessionState = json))
            core.repo.closedTabs.trim(MAX_CLOSED)
        }
    }

    /**
     * Reopens the newest closed tab of [sessionId] (or the entry [closedId]). The entry is removed first, so it can
     * never be restored twice. A tab closed from a group returns to that group; if the group was deleted meanwhile
     * but is still in the closed-group history it is recreated (same id, name, colour).
     */
    fun reopenClosedTab(sessionId: String, closedId: String? = null, select: Boolean = true): Tab? {
        val e = (if (closedId != null) closedTabs.firstOrNull { it.id == closedId } else closedTabs.firstOrNull { it.sessionId == sessionId }) ?: return null
        if (e.sessionId != sessionId || core.sessions.get(sessionId) == null) return null
        closedTabs.remove(e)
        val state = closedStates.remove(e.id)
        core.persist { core.repo.closedTabs.delete(e.id) }
        val gid = e.groupId?.let { resolveGroupForReopen(it, e) }
        val tab = createTab(sessionId, e.url, select = select, groupId = gid, pinned = e.pinned)
        tab.title = e.title
        tab.desktopMode = e.desktopMode
        // State of a tab closed in an earlier process run is parsed from JSON here (one tab, a few ms; the grid
        // reopen is a user action). Tabs closed in this process reuse the SessionState object directly.
        tab.savedState = state ?: e.sessionState?.let { json -> runCatching { GeckoSession.SessionState.fromString(json) }.getOrNull() }
        persistTab(tab)
        AppLog.i(TAG, "Reopened closed tab ${e.id.take(8)} as ${tab.id.take(8)}")
        return tab
    }

    private fun resolveGroupForReopen(groupId: String, e: ClosedTabEntity): String? {
        groups[groupId]?.takeIf { it.sessionId == e.sessionId }?.let { return it.id }
        val cg = closedGroups.firstOrNull { it.id == groupId && it.sessionId == e.sessionId }
        if (cg != null) return createGroup(cg.sessionId, cg.name, cg.color, id = cg.id, collapsed = false).id
        if (e.groupName != null) return createGroup(e.sessionId, e.groupName, e.groupColor ?: 0xFF2962FF.toInt(), id = groupId).id
        return null
    }

    /** Restores a deleted group with every tab that was closed together with it (original order, pinned state). */
    fun restoreClosedGroup(groupId: String): TabGroup? {
        val cg = closedGroups.firstOrNull { it.id == groupId } ?: return null
        if (core.sessions.get(cg.sessionId) == null) return null
        closedGroups.remove(cg)
        core.persist { core.repo.closedGroups.delete(cg.id) }
        val g = groups[cg.id]?.takeIf { it.sessionId == cg.sessionId } ?: createGroup(cg.sessionId, cg.name, cg.color, id = cg.id, collapsed = cg.collapsed)
        val members = closedTabs.filter { it.closedGroupId == cg.id && it.sessionId == cg.sessionId }.sortedBy { it.position }
        var first: Tab? = null
        members.forEach { e ->
            val t = reopenClosedTab(cg.sessionId, e.id, select = false) ?: return@forEach
            if (t.groupId != g.id) setTabGroup(t.id, g.id)
            if (first == null) first = t
        }
        AppLog.i(TAG, "Restored closed group ${cg.id.take(8)} with ${members.size} tab(s)")
        notifyTabsChanged(cg.sessionId)
        return g
    }

    fun clearRecentlyClosed(sessionId: String) {
        closedTabs.removeAll { it.sessionId == sessionId }
        closedGroups.removeAll { it.sessionId == sessionId }
        closedStates.clear()
        core.persist { core.repo.closedTabs.deleteForSession(sessionId); core.repo.closedGroups.deleteForSession(sessionId) }
        notifyTabsChanged(sessionId)
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
        allTabsFor(sessionId).forEach { t ->
            tabs.remove(t.id)
            closeSession(t)
            dropThumbnail(t)
        }
        groups.values.filter { it.sessionId == sessionId }.map { it.id }.forEach { groups.remove(it) }
        closedTabs.removeAll { it.sessionId == sessionId }
        closedGroups.removeAll { it.sessionId == sessionId }
    }

    fun hibernateSession(sessionId: String) = allTabsFor(sessionId).forEach { hibernate(it) }

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

    /**
     * Title-only persist (v2.1.10 perf): [persistTab] takes a SessionState snapshot and serialises it
     * to JSON on every call, and page titles change often on SPAs (unread counts, clocks) without
     * anything else about the tab moving. The title is its own column and does not live in the JSON.
     */
    fun persistTitle(tab: Tab) {
        val id = tab.id
        val title = tab.title
        core.persist { core.repo.tabs.updateTitle(id, title) }
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

    fun notifyTabUpdated(tab: Tab) = listeners.forEach { it.onTabUpdated(tab) }
    fun notifyTabsChanged(sessionId: String) = listeners.forEach { it.onTabsChanged(sessionId) }

    // ------------------------------------------------------------------ thumbnails

    /** Keeps at most [MAX_THUMBNAILS] grid thumbnails in memory (LRU); the rest fall back to the placeholder. */
    fun storeThumbnail(tab: Tab, bitmap: Bitmap) {
        // Bitmaps are dropped, never recycle()d: a card in the (possibly open) tab grid or a RecyclerView removal
        // animation may still be drawing them - drawing a recycled bitmap throws and crashes the app. Pixel memory
        // is freed by the GC (NativeAllocationRegistry) as soon as the last reference is gone.
        tab.thumbnail = bitmap
        thumbnailOwners.remove(tab.id)
        thumbnailOwners.add(tab.id)
        while (thumbnailOwners.size > MAX_THUMBNAILS) {
            val victim = tabs[thumbnailOwners.removeAt(0)] ?: continue
            victim.thumbnail = null
            notifyTabUpdated(victim)   // an open grid replaces the card image with the placeholder
        }
        notifyTabUpdated(tab)
    }

    private fun dropThumbnail(tab: Tab) {
        thumbnailOwners.remove(tab.id)
        tab.thumbnail = null
    }

    // ------------------------------------------------------------------ scripts / media

    /**
     * Runs [source] (a javascript: URL or bare script) in the page of [tab] as a bookmarklet. The script is wrapped
     * so its completion value is undefined: Gecko therefore never replaces the document with a returned string.
     * Only this app-initiated load passes NavigationDelegate.onLoadRequest (see [Tab.pendingScript]).
     */
    fun runScript(tab: Tab, source: String): Boolean = loadScript(tab, source)

    /**
     * Applies [percent] (already clamped by `PageScale`) as the tab's NATIVE page zoom. v2.1.10:
     * `moz-scale:<percent>` is intercepted by the patched omni.js (`GeckoViewNavigation`) and written to
     * `browsingContext.fullZoom` - it never enters the content process, so no page policy applies to it.
     * This replaced the old CSS-`zoom` javascript: load, which the Content-Security-Policy of sites like
     * Discord silently blocked (the Plan-2 "scale does nothing on some sites" root cause).
     *
     * The BYPASS flag routes the load straight to omni.js: `TabDelegates.onLoadRequest` never sees our own
     * URI, and a page-initiated `moz-scale:` link (which lacks the flag) is denied there. No history
     * entry, no start/progress/stop callbacks, no feedback loop - [Tab.appliedScale] is the only record.
     */
    fun setPageScale(tab: Tab, percent: Int): Boolean {
        val gs = tab.geckoSession?.takeIf { it.isOpen } ?: return false
        gs.load(
            GeckoSession.Loader()
                .uri("moz-scale:$percent")
                .flags(GeckoSession.LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE)
        )
        return true
    }

    private fun loadScript(tab: Tab, source: String): Boolean {
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
            // Never leave a slot set that Gecko will not be asked to consume: a stale slot would
            // authorize whatever javascript: navigation happens to arrive next.
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
                try { t.hasMediaCapture = false; t.geckoSession?.reload(); n++ } catch (e: Throwable) { AppLog.w(TAG, "revoke reload failed", e) }
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
            tab.nativeActiveState = null   // fresh docshell: the next setDisplayed must push again
            if (loadContent) restoreOrLoad(tab, existing)
            return existing
        }
        val session = core.sessions.get(tab.sessionId)
            ?: throw IllegalStateException("Session ${tab.sessionId} not found for tab ${tab.id}")
        val gs = SessionFactory.create(core, tab, session)
        gs.open(core.engine.runtime)
        tab.geckoSession = gs
        tab.nativeActiveState = null
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

    /**
     * Keeps the (already up-to-date, see ProgressDelegate.onSessionStateChange) SessionState and closes the
     * GeckoSession, freeing its content-process memory; the tab is re-opened lazily from that state.
     */
    fun hibernate(tab: Tab) {
        if (tab.geckoSession == null) return
        closeSession(tab)
        AppLog.i(TAG, "Tab hibernated ${tab.id.take(8)} live=${liveCount()}")
    }

    fun closeSession(tab: Tab) {
        val gs = tab.geckoSession ?: return
        host?.onSessionClosing(tab)
        // v2.1.8: Gecko's tabTracker would otherwise keep pointing at a session that is about to
        // disappear (an add-on calling tabs.query then sees a dead tab), and the tab's action
        // overrides must go with it so sessionActions cannot grow.
        core.extensions.setTabActive(gs, false)
        core.extensions.forgetSession(gs)
        tab.geckoSession = null
        tab.nativeActiveState = null
        tab.isLoading = false
        tab.awaitingDisplay = false
        tab.isPlayingMedia = false
        tab.hasMediaCapture = false
        try {
            if (gs.isOpen) gs.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "close failed", t)
        }
        AppLog.d(TAG, "GeckoSession closed tab=${tab.id.take(8)}")
    }

    /**
     * Marks [displayed] active/focused and every other live session inactive (saves CPU; audio keeps playing).
     *
     * v2.1.8: this is also the single place Gecko's extension view of "the active tab" is updated.
     * `WebExtensionController.setTabActive` dispatches `GeckoView:WebExtension:SetTabActive`, whose
     * handler sets `mobileWindowTracker._topWindow` when active and `nativeTab.active` either way;
     * `tabTracker.activeTab` reads that top window, so before this mirror existed it was always
     * null and an add-on's `action.click()` died in `addActiveTabPermission(null)` - a silent no-op.
     * Ordering inside the loop is irrelevant: `setTabActive(_, false)` never clears the top window.
     *
     * Reports a real change through [BrowserHost.onDisplayedTabChanged] so surfaces tied to the old
     * tab (an extension popup) can be dropped; re-entrant calls for the same tab do not fire it.
     */
    fun setDisplayed(displayed: Tab?) {
        val changed = displayed !== displayedTab
        displayedTab = displayed
        val start = SystemClock.uptimeMillis()
        tabs.values.forEach { t ->
            val gs = t.geckoSession ?: return@forEach
            if (!gs.isOpen) return@forEach
            val isIt = t === displayed
            // v2.1.10 (perf): only tabs whose state actually changes talk to Gecko. This used to push
            // setActive + setFocused + setTabActive to EVERY live session on every switch - N-2 of the
            // calls redundantly, N binder round-trips for a two-tab change.
            if (t.nativeActiveState == isIt) return@forEach
            try {
                gs.setActive(isIt)
                gs.setFocused(isIt)
            } catch (t2: Throwable) {
                AppLog.w(TAG, "setActive failed", t2)
            }
            core.extensions.setTabActive(gs, isIt)
            t.nativeActiveState = isIt
        }
        if (changed) {
            host?.onDisplayedTabChanged(displayed)
            // A restored / re-hibernated tab has an unknown appliedScale (null) even though its
            // context may still carry the previous document's zoom, so applyFor re-issues an explicit
            // native set; tabs whose percent already matches are skipped without any engine traffic.
            displayed?.let { PageScale.applyFor(core, it) }
        }
        // One-shot cost marker for field diagnosis (tab switches must stay well under a frame).
        val took = SystemClock.uptimeMillis() - start
        if (took >= 16) AppLog.w(TAG, "setDisplayed took $took ms (live=${liveCount()})")
    }

    /** Background tabs that may be hibernated automatically: not displayed, not a pending popup, not playing / capturing media. */
    private fun hibernationCandidates(activeTabId: String?): List<Tab> =
        tabs.values.filter { it.geckoSession != null && it.id != activeTabId && !it.awaitingDisplay && !it.isBusy }

    /** Keeps at most [Prefs.liveTabLimit] GeckoSessions open (the active one and busy media/call tabs always survive). */
    fun enforceLiveLimit(activeTabId: String?) {
        val keepBackground = (Prefs.liveTabLimit - 1).coerceAtLeast(0)
        // Most recently used first; a tab that is still loading is kept in preference to an idle one so a
        // page opened in the background is not frozen half-way and reloaded from scratch later.
        val background = hibernationCandidates(activeTabId)
            .sortedWith(compareByDescending<Tab> { it.isLoading }.thenByDescending { it.lastActiveAt })
        background.drop(keepBackground).forEach { hibernate(it) }
    }

    /**
     * Idle sweep: a background tab not used for [Prefs.idleHibernateMinutes] releases its GeckoSession even when
     * the live limit is not reached (real RAM reduction for tabs the user left behind). Runs every few minutes on
     * the main thread; cheap (no I/O). 0 disables it.
     */
    fun scheduleIdleSweep() {
        mainHandler.removeCallbacks(idleSweep)
        mainHandler.postDelayed(idleSweep, IDLE_SWEEP_INTERVAL_MS)
    }

    private val idleSweep = object : Runnable {
        override fun run() {
            try {
                val minutes = Prefs.idleHibernateMinutes
                if (minutes > 0) {
                    val cutoff = System.currentTimeMillis() - minutes * 60_000L
                    val activeId = core.sessions.activeId?.let { activeTab(it)?.id }
                    hibernationCandidates(activeId).filter { !it.isLoading && it.lastActiveAt < cutoff }.forEach { hibernate(it) }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "idle sweep failed", t)
            } finally {
                mainHandler.postDelayed(this, IDLE_SWEEP_INTERVAL_MS)
            }
        }
    }

    fun onTrimMemory(level: Int) {
        val activeId = core.sessions.activeId?.let { activeTab(it)?.id }
        when (level) {
            // Delivered every time the UI is simply hidden (Home button / recents). It is NOT a memory signal: the
            // old ">= RUNNING_LOW" range matched it (20 > 10) and hibernated every background tab on each minimise.
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> return
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                hibernationCandidates(activeId).sortedByDescending { it.lastActiveAt }.drop(1).forEach { hibernate(it) }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> hibernateAll(exceptTabId = activeId) // keep the visible/last tab (may be playing audio / in a call)
            else -> if (level > ComponentCallbacks2.TRIM_MEMORY_COMPLETE) hibernateAll(exceptTabId = activeId)
        }
    }

    /** Everything except the displayed tab and tabs in a call / playing media (a running voice call must survive pressure). */
    private fun hibernateAll(exceptTabId: String?) {
        hibernationCandidates(exceptTabId).forEach { hibernate(it) }
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
        tab.nativeActiveState = null
        tab.isLoading = false
        tab.awaitingDisplay = false
        tab.isPlayingMedia = false
        tab.hasMediaCapture = false
        try { if (gs != null && gs.isOpen) gs.close() } catch (_: Throwable) {}
        if (crashed) tab.error = PageError(ERROR_RENDERER_GONE, 0, "Content process terminated", tab.url)
        notifyTabUpdated(tab)
        host?.onContentProcessGone(tab, crashed)
    }

    companion object {
        private const val TAG = "Tabs"
        private const val MAX_CLOSED = 50
        private const val MAX_CLOSED_GROUPS = 15
        private const val MAX_THUMBNAILS = 24
        private const val IDLE_SWEEP_INTERVAL_MS = 5 * 60_000L
        const val ERROR_RENDERER_GONE = -1000
    }
}
