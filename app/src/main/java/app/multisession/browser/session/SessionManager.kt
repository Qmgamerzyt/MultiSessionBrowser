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

class SessionManager(private val core: BrowserCore) {

    private val _sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val sessions: StateFlow<List<SessionEntity>> = _sessions.asStateFlow()

    private val _active = MutableStateFlow<SessionEntity?>(null)
    val active: StateFlow<SessionEntity?> = _active.asStateFlow()

    val activeId: String? get() = _active.value?.id

    fun get(id: String?): SessionEntity? = id?.let { sid -> _sessions.value.firstOrNull { it.id == sid } }

    suspend fun initialize() {
        val dao = core.repo.sessions
        // Private sessions never survive a process restart: discard them and their profile data.
        dao.getAll().filter { it.isPrivate }.forEach { s ->
            core.repo.deleteSessionCascade(s.id)
            core.isolation.deleteProfileData(s)
            AppLog.i(TAG, "Discarded private session ${s.id.take(8)}")
        }
        var all = dao.getAll()
        if (all.isEmpty()) {
            val s = newEntity(core.app.getString(R.string.default_session_name), PALETTE[0], false, 0)
            dao.upsert(s)
            all = listOf(s)
            AppLog.i(TAG, "Created default session")
        }
        _sessions.value = all
        val preferred = Prefs.activeSessionId
        val chosen = all.firstOrNull { it.id == preferred } ?: all.maxByOrNull { it.lastUsedAt } ?: all.first()
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
            core.app.getString(if (isPrivate) R.string.private_session_name else R.string.default_session_name)
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
        core.persistNow { core.repo.deleteSessionCascade(id) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        val profileDeleted = core.isolation.deleteProfileData(s)
        AppLog.i(TAG, "Session deleted ${id.take(8)} profileDeleted=$profileDeleted")
        if (_active.value?.id == id) {
            val next = _sessions.value.maxByOrNull { it.lastUsedAt }
                ?: create(core.app.getString(R.string.default_session_name), PALETTE[0], false, select = false)
            switchTo(next.id)
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
        // Release the session's WebViews so the clears apply cleanly; tabs are restored lazily from state.
        core.tabs.hibernateSession(id)
        if (cookies) core.isolation.clearCookies(s)
        if (storage) core.isolation.clearSiteData(s)
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
