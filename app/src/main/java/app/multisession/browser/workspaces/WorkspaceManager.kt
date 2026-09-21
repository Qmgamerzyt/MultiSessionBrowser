package app.multisession.browser.workspaces

import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.data.db.WorkspaceEntity
import app.multisession.browser.data.db.WorkspaceGroupEntity
import app.multisession.browser.data.db.WorkspaceItemEntity
import app.multisession.browser.tabs.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Workspaces / research sets: saved browsing environments that can be restored later.
 *
 * SESSION  = isolated cookies / logins (contextId)          -> a workspace belongs to exactly one session
 * TAB GROUP = organisation of open tabs                     -> saved as workspace_groups (empty groups too)
 * WORKSPACE = a stored collection (tabs, groups, order, pinned state, Gecko state) -> restorable any time
 *
 * A workspace references the live objects it came from (sourceTabId / sourceGroupId): restoring re-uses a tab that
 * is still open with the same URL instead of opening a duplicate, and re-uses a group that still exists. Nothing is
 * closed on restore unless the user explicitly chooses "replace current tabs".
 */
class WorkspaceManager(private val core: BrowserCore) {

    private val _workspaces = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceEntity>> = _workspaces.asStateFlow()

    suspend fun load() {
        _workspaces.value = core.repo.workspaces.getAll()
    }

    fun forSession(sessionId: String): List<WorkspaceEntity> = _workspaces.value.filter { it.sessionId == sessionId }

    suspend fun itemCount(id: String): Int = core.repo.workspaces.itemCount(id)

    /** Saves the session's current visible tabs + groups as a new workspace. */
    suspend fun saveCurrent(sessionId: String, name: String): WorkspaceEntity {
        val now = System.currentTimeMillis()
        val ws = WorkspaceEntity(UUID.randomUUID().toString(), sessionId, name.trim().ifEmpty { "Workspace" }, now, now)
        writeSnapshot(ws)
        AppLog.i(TAG, "Workspace saved ${ws.id.take(8)} (${ws.name})")
        return ws
    }

    /** Replaces the stored content with the session's current state (name kept). */
    suspend fun update(id: String): WorkspaceEntity? {
        val ws = get(id) ?: return null
        val updated = ws.copy(updatedAt = System.currentTimeMillis())
        writeSnapshot(updated)
        return updated
    }

    suspend fun rename(id: String, name: String) {
        val ws = get(id) ?: return
        if (name.isBlank()) return
        val updated = ws.copy(name = name.trim(), updatedAt = System.currentTimeMillis())
        core.persistNow { core.repo.workspaces.upsert(updated) }
        refreshList()
    }

    suspend fun duplicate(id: String, newName: String? = null): WorkspaceEntity? {
        val src = get(id) ?: return null
        val groups = core.repo.workspaces.groups(id)
        val items = core.repo.workspaces.items(id)
        val now = System.currentTimeMillis()
        val copy = WorkspaceEntity(UUID.randomUUID().toString(), src.sessionId, newName?.trim()?.ifEmpty { null } ?: "${src.name} (copy)", now, now)
        val groupIdMap = groups.associate { it.id to UUID.randomUUID().toString() }
        core.persistNow {
            core.repo.transaction {
                core.repo.workspaces.upsert(copy)
                core.repo.workspaces.upsertGroups(groups.map { it.copy(id = groupIdMap.getValue(it.id), workspaceId = copy.id) })
                core.repo.workspaces.insertItems(items.map { it.copy(id = 0, workspaceId = copy.id, groupId = it.groupId?.let { g -> groupIdMap[g] }) })
            }
        }
        refreshList()
        return copy
    }

    suspend fun delete(id: String) {
        core.persistNow { core.repo.workspaces.delete(id) }   // groups/items cascade
        refreshList()
    }

    /**
     * Restores the workspace into its session and returns the tab to show (null if it is empty).
     * [replaceCurrent] closes the session's current visible tabs first (they go to "Recently closed"); otherwise the
     * workspace is merged: still-open source tabs are re-used, missing ones are opened.
     */
    suspend fun restore(id: String, replaceCurrent: Boolean): Tab? {
        val ws = get(id) ?: return null
        val sessionId = ws.sessionId
        if (core.sessions.get(sessionId) == null) return null
        val groups = core.repo.workspaces.groups(id)
        val items = core.repo.workspaces.items(id)
        val states = withContext(Dispatchers.Default) {
            items.associate { it.id to it.sessionState?.let { json -> runCatching { GeckoSession.SessionState.fromString(json) }.getOrNull() } }
        }
        val tabs = core.tabs
        if (replaceCurrent) tabs.closeAllTabs(sessionId)

        // Groups: reuse the live group it was saved from, else one with the same name, else create it (empty groups included).
        val liveGroups = tabs.groupsFor(sessionId)
        val groupMap = HashMap<String, String>()
        groups.forEach { g ->
            val live = g.sourceGroupId?.let { sid -> liveGroups.firstOrNull { it.id == sid } }
                ?: tabs.groupsFor(sessionId).firstOrNull { it.name.equals(g.name, ignoreCase = true) }
                ?: tabs.createGroup(sessionId, g.name, g.color, collapsed = g.collapsed)
            groupMap[g.id] = live.id
        }

        val restored = ArrayList<Tab>()
        items.sortedBy { it.position }.forEach { item ->
            val gid = item.groupId?.let { groupMap[it] }
            val existing = item.sourceTabId?.let { tabs.get(it) }?.takeIf { it.sessionId == sessionId && it.url == item.url }
            val tab = when {
                existing != null && !existing.archived -> existing
                existing != null -> tabs.unarchiveTabs(listOf(existing.id)).firstOrNull() ?: existing
                else -> tabs.createTab(sessionId, item.url, select = false, groupId = gid, pinned = item.pinned).also { t ->
                    t.title = item.title
                    t.desktopMode = item.desktopMode
                    t.savedState = states[item.id]
                    tabs.persistTab(t)
                }
            }
            if (tab.groupId != gid) tabs.setTabGroup(tab.id, gid)
            if (tab.pinned != item.pinned) tabs.setPinned(listOf(tab.id), item.pinned)
            restored += tab
        }
        // Workspace order first (pinned/grouped sections are re-normalised by TabManager), then the other open tabs.
        val restoredIds = restored.map { it.id }.toSet()
        val ordered = restored.map { it.id to it.groupId } + tabs.tabsFor(sessionId).filter { it.id !in restoredIds }.map { it.id to it.groupId }
        tabs.applyOrder(sessionId, ordered)
        AppLog.i(TAG, "Workspace ${ws.id.take(8)} restored: ${restored.size} tab(s), ${groups.size} group(s), replace=$replaceCurrent")
        return restored.firstOrNull()
    }

    // ------------------------------------------------------------------ internals

    private fun get(id: String): WorkspaceEntity? = _workspaces.value.firstOrNull { it.id == id }

    private suspend fun refreshList() { _workspaces.value = core.repo.workspaces.getAll() }

    private suspend fun writeSnapshot(ws: WorkspaceEntity) {
        val sessionId = ws.sessionId
        val liveGroups = core.tabs.groupsFor(sessionId)
        val groupIdMap = liveGroups.associate { it.id to UUID.randomUUID().toString() }
        val groupRows = liveGroups.mapIndexed { i, g -> WorkspaceGroupEntity(groupIdMap.getValue(g.id), ws.id, g.name, g.color, i, g.collapsed, g.id) }
        // Snapshots on the main thread, SessionState -> JSON on Default (can be hundreds of KB per tab).
        val tabs = core.tabs.tabsFor(sessionId)
        val snaps = tabs.map { it.snapshot() }
        val jsons = withContext(Dispatchers.Default) { snaps.map { it.stateJson() } }
        val itemRows = tabs.mapIndexed { i, t ->
            WorkspaceItemEntity(
                workspaceId = ws.id, url = t.url, title = t.title, position = i, pinned = t.pinned, desktopMode = t.desktopMode,
                groupId = t.groupId?.let { groupIdMap[it] }, sessionState = jsons[i], sourceTabId = t.id,
            )
        }
        core.persistNow {
            core.repo.transaction {
                core.repo.workspaces.upsert(ws)
                core.repo.workspaces.deleteItems(ws.id)
                core.repo.workspaces.deleteGroups(ws.id)
                core.repo.workspaces.upsertGroups(groupRows)
                core.repo.workspaces.insertItems(itemRows)
            }
        }
        refreshList()
    }

    private companion object {
        const val TAG = "Workspaces"
    }
}
