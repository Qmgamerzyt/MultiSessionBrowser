package com.app.browser.tab

import com.app.browser.data.AppDatabase
import com.app.browser.data.entities.TabEntity
import com.app.browser.engine.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession

class TabManager(
    private val db: AppDatabase,
    private val engineProvider: EngineProvider
) {
    
    private var activeTabId: String? = null
    private var geckoSessions = mutableMapOf<String, GeckoSession>()
    
    // Open a new tab
    suspend fun openTab(sessionId: String, url: String = "about:blank"): Tab {
        val count = db.tabDao().getBySessionIdOnce(sessionId).size
        val tab = Tab(sessionId = sessionId, url = url, orderIndex = count)
        val entity = tab.toEntity()
        db.tabDao().insert(entity)
        
        // Create GeckoSession
        val runtime = engineProvider.getRuntime()
            ?: throw IllegalStateException("Engine not initialized")
        val geckoSession = GeckoSession()
        geckoSession.open(runtime)
        geckoSession.loadUrl(url)
        geckoSessions[tab.id] = geckoSession
        
        return tab
    }
    
    // Close a tab
    suspend fun closeTab(tabId: String) {
        // Close GeckoSession
        geckoSessions[tabId]?.close()
        geckoSessions.remove(tabId)
        
        // Remove from DB
        db.tabDao().deleteById(tabId)
        
        // Update order indices
        reorderAfterDelete(tabId)
    }
    
    private suspend fun reorderAfterDelete(deletedTabId: String) {
        // TODO: Implement reordering - for now just decrement indices after the deleted one
    }
    
    // Switch active tab
    suspend fun switchToTab(tabId: String): GeckoSession? {
        activeTabId = tabId
        return geckoSessions[tabId]
    }
    
    // Get active GeckoSession
    fun getActiveSession(): GeckoSession? {
        activeTabId?.let { return geckoSessions[it] }
        return null
    }
    
    // Get GeckoSession for tab
    fun getGeckoSession(tabId: String): GeckoSession? = geckoSessions[tabId]
    
    // Update tab URL
    suspend fun updateUrl(tabId: String, url: String) {
        val tab = db.tabDao().getById(tabId)
            ?: return
        val updated = tab.copy(url = url, updatedAt = System.currentTimeMillis())
        db.tabDao().update(updated.toEntity())
    }
    
    // Update tab title
    suspend fun updateTitle(tabId: String, title: String) {
        val tab = db.tabDao().getById(tabId)
            ?: return
        val updated = tab.copy(title = title, updatedAt = System.currentTimeMillis())
        db.tabDao().update(updated.toEntity())
    }
    
    // Restore tabs from DB (on session switch or app start)
    suspend fun restoreTabs(sessionId: String): List<Tab> {
        val entities = db.tabDao().getBySessionIdOnce(sessionId)
        val tabs = entities.map { it.toDomain() }
        
        // Recreate GeckoSessions for all tabs
        val runtime = engineProvider.getRuntime()
            ?: throw IllegalStateException("Engine not initialized")
        
        geckoSessions.clear()
        for (tab in tabs) {
            val geckoSession = GeckoSession()
            geckoSession.open(runtime)
            geckoSession.loadUrl(tab.url)
            geckoSessions[tab.id] = geckoSession
        }
        
        return tabs
    }
    
    // Get all tabs for session
    suspend fun getTabs(sessionId: String): List<Tab> {
        return db.tabDao().getBySessionIdOnce(sessionId).map { it.toDomain() }
    }
    
    // Get active tab
    fun getActiveTab(): Tab? {
        activeTabId?.let { id ->
            val entity = db.tabDao().getById(id)
            return entity?.toDomain()
        }
        return null
    }
    
    // Cleanup
    fun destroy() {
        geckoSessions.values.forEach { it.close() }
        geckoSessions.clear()
        activeTabId = null
    }
}

// Extensions
private fun Tab.toEntity(): TabEntity = TabEntity(
    id = id,
    sessionId = sessionId,
    url = url,
    title = title,
    orderIndex = orderIndex,
    updatedAt = updatedAt
)

private fun TabEntity.toDomain(): Tab = Tab(
    id = id,
    sessionId = sessionId,
    url = url,
    title = title,
    orderIndex = orderIndex,
    updatedAt = updatedAt
)