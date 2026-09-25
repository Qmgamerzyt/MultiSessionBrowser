package app.multisession.browser.session

import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.data.db.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Sessions = isolated browsing identities. Ordering rules (v2.0.2):
 *  - exactly one session is the DEFAULT session ([SessionEntity.isDefault]); it is always listed first and is
 *    the session opened on a cold start of the process;
 *  - the user may drag the other sessions into any order ([SessionEntity.sortOrder]); nothing can be moved
 *    above the default session and the default session cannot be moved down.
 */
class SessionManager(private val core: BrowserCore) {

    private val _sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val sessions: StateFlow<List<SessionEntity>> = _sessions.asStateFlow()

    private val _active = MutableStateFlow<SessionEntity?>(null)
    val active: StateFlow<SessionEntity?> = _active.asStateFlow()

    val activeId: String? get() = _active.value?.id

    fun get(id: String?): SessionEntity? = id?.let { sid -> _sessions.value.firstOrNull { it.id == sid } }

    val defaultSession: SessionEntity? get() = _sessions.value.firstOrNull { it.isDefault } ?: _sessions.value.firstOrNull()

    suspend fun initialize() {
        val dao = core.repo.sessions
        // Private sessions never survive a process restart: discard them and their profile data.
        // (Private sessions run in Gecko private mode: their site data was memory-only and is already gone.)
        dao.getAll().filter { it.isPrivate }.forEach { s ->
            core.repo.deleteSessionCascade(s.id)
            AppLog.i(TAG, "Discarded private session ${s.id.take(8)}")
        }
        var all = dao.getAll()
        if (all.isEmpty()) {
            val s = newEntity(core.app.getString(R.string.default_session_name), PALETTE[0], false, 0).copy(isDefault = true)
            dao.upsert(s)
            all = listOf(s)
            AppLog.i(TAG, "Created default session")
        }
        if (all.none { it.isDefault }) {
            // Cannot happen after migration 3->4, but never leave the app without a default session.
            dao.setDefault(all.first().id)
            all = dao.getAll()
        }
        _sessions.value = all
        // Cold start ALWAYS opens the default session (existing rule). Switching later is remembered only for
        // the lifetime of the process (minimise/restore keeps the current session because the process survives).
        val chosen = all.first { it.isDefault }
        _active.value = chosen
        Prefs.activeSessionId = chosen.id
    }

    fun startObserving() {
        core.scope.launch {
            core.repo.sessions.observeAll().collect { list ->
                _sessions.value = list
                val cur = _active.value ?: return@collect
                list.firstOrNull { it.id == cur.id }?.let { _active.value = it }
            }
        }
    }

    suspend fun create(name: String, color: Int, isPrivate: Boolean = false, select: Boolean = true): SessionEntity {
        val finalName = name.trim().ifEmpty {
            core.app.getString(if (isPrivate) R.string.incognito_session_name else R.string.default_session_name)
        }
        val order = (_sessions.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val s = newEntity(finalName, color, isPrivate, order)
        core.persistNow { core.repo.sessions.upsert(s) }
        _sessions.value = _sessions.value + s
        AppLog.i(TAG, "Session created ${s.id.take(8)} private=$isPrivate")
        if (select) switchTo(s.id)
        return s
    }

    suspend fun switchTo(id: String): SessionEntity? {
        val s = get(id) ?: core.repo.sessions.get(id) ?: return null
        if (_active.value?.id != id) AppLog.i(TAG, "Switch session -> ${id.take(8)}")
        _active.value = s
        Prefs.activeSessionId = id
        val now = System.currentTimeMillis()
        core.persist { core.repo.sessions.touch(id, now) }
        return s
    }

    suspend fun update(id: String, name: String, color: Int) {
        val s = get(id) ?: return
        replace(s.copy(name = name.trim().ifEmpty { s.name }, color = color))
    }

    suspend fun setDesktopMode(id: String, enabled: Boolean) {
        val s = get(id) ?: return
        replace(s.copy(desktopMode = enabled))
    }

    /** Makes [id] the default session (listed first, opened on cold start). Private sessions cannot be the default. */
    suspend fun setDefault(id: String) {
        val s = get(id) ?: return
        if (s.isPrivate) return
        core.persistNow { core.repo.sessions.setDefault(id) }
        _sessions.value = sorted(_sessions.value.map { it.copy(isDefault = it.id == id) })
        _active.value = get(_active.value?.id)
        AppLog.i(TAG, "Default session -> ${id.take(8)}")
    }

    /**
     * User drag in the session drawer: [orderedIds] is the complete new order as displayed. The default session is
     * forced back to the top whatever position it was dropped at, so the rule "default is always first" holds.
     */
    suspend fun reorder(orderedIds: List<String>) {
        val byId = _sessions.value.associateBy { it.id }
        if (orderedIds.size != byId.size || !orderedIds.all { byId.containsKey(it) }) return
        val def = _sessions.value.firstOrNull { it.isDefault }
        val ids = if (def != null) listOf(def.id) + orderedIds.filter { it != def.id } else orderedIds
        val updated = ids.mapIndexed { i, id -> byId.getValue(id).copy(sortOrder = i) }
        core.persistNow { core.repo.sessions.upsertAll(updated) }
        _sessions.value = updated
        _active.value = get(_active.value?.id)
        AppLog.i(TAG, "Sessions reordered")
    }

    private fun sorted(list: List<SessionEntity>) =
        list.sortedWith(compareByDescending<SessionEntity> { it.isDefault }.thenBy { it.sortOrder }.thenBy { it.createdAt })

    private suspend fun replace(updated: SessionEntity) {
        core.persistNow { core.repo.sessions.upsert(updated) }
        _sessions.value = _sessions.value.map { if (it.id == updated.id) updated else it }
        if (_active.value?.id == updated.id) _active.value = updated
    }

    fun setActiveTab(sessionId: String, tabId: String?) {
        val now = System.currentTimeMillis()
        _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(activeTabId = tabId, lastUsedAt = now) else it }
        if (_active.value?.id == sessionId) _active.value = _active.value?.copy(activeTabId = tabId, lastUsedAt = now)
        core.persist { core.repo.sessions.setActiveTab(sessionId, tabId, now) }
    }

    suspend fun delete(id: String) {
        val s = get(id) ?: return
        core.tabs.destroySession(id)
        core.sitePermissions.clearSession(id)
        core.persistNow { core.repo.deleteSessionCascade(id) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        val profileDeleted = core.isolation.deleteProfileData(s)
        AppLog.i(TAG, "Session deleted ${id.take(8)} profileDeleted=$profileDeleted")
        if (s.isDefault) {
            // Someone must be the default: promote the first remaining (non-private) session or create one.
            val next = _sessions.value.firstOrNull { !it.isPrivate }
                ?: create(core.app.getString(R.string.default_session_name), PALETTE[0], false, select = false)
            setDefault(next.id)
        }
        if (_active.value?.id == id) {
            switchTo((defaultSession ?: _sessions.value.first()).id)
        }
    }

    /** Duplicates tabs/URLs only. The copy gets a brand-new, empty profile: no cookies or logins are copied. */
    suspend fun duplicate(id: String): SessionEntity? {
        val src = get(id) ?: return null
        val urls = core.tabs.tabsFor(id).map { it.url }
        val copy = create(core.app.getString(R.string.session_copy_name, src.name), src.color, false, select = false)
        urls.forEach { core.tabs.createTab(copy.id, it, select = false) }
        return copy
    }

    suspend fun clearData(id: String, cookies: Boolean, storage: Boolean, cache: Boolean, history: Boolean) {
        val s = get(id) ?: return
        // Close the session's GeckoSessions so the clears apply cleanly; tabs are restored lazily from state.
        core.tabs.hibernateSession(id)
        if (cookies) core.isolation.clearCookies(s)
        if (storage) core.isolation.clearSiteData(s)
        // clearDataForSessionContext also drops Gecko's stored site permissions of this context: mirror that.
        if (cookies || storage) core.sitePermissions.clearSession(id)
        if (cache) core.isolation.clearCache(core.app, s)
        if (history) core.persistNow { core.repo.history.clear(id) }
    }

    private fun newEntity(name: String, color: Int, isPrivate: Boolean, sortOrder: Int): SessionEntity {
        val now = System.currentTimeMillis()
        return SessionEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            color = color,
            createdAt = now,
            lastUsedAt = now,
            activeTabId = null,
            isPrivate = isPrivate,
            desktopMode = false,
            sortOrder = sortOrder,
            isDefault = false,
        )
    }

    companion object {
        private const val TAG = "Sessions"
        val PALETTE = intArrayOf(
            0xFF2962FF.toInt(), 0xFF00C853.toInt(), 0xFFAA00FF.toInt(), 0xFFFF6D00.toInt(),
            0xFFD50000.toInt(), 0xFF00B8D4.toInt(), 0xFFFFAB00.toInt(), 0xFF6D4C41.toInt(),
        )
    }
}
