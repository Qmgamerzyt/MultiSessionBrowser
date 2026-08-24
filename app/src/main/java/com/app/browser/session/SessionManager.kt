package com.app.browser.session

import android.content.Context
import com.app.browser.data.AppDatabase
import com.app.browser.data.entities.SessionEntity
import com.app.browser.engine.EngineProvider
import com.app.browser.engine.ProfileManager
import com.app.browser.engine.StorageCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionManager(
    private val context: Context,
    private val db: AppDatabase,
    private val profileManager: ProfileManager,
    private val engineProvider: EngineProvider,
    private val storageCleaner: StorageCleaner
) {
    
    private var currentSessionId: String? = null
    
    // Create a new session
    suspend fun createSession(name: String, colorHex: String = "#6200EE", iconKey: String = "default"): Session {
        val session = Session(name = name, colorHex = colorHex, iconKey = iconKey)
        val entity = session.toEntity()
        db.sessionDao().insert(entity)
        return session
    }
    
    // Rename session
    suspend fun renameSession(sessionId: String, newName: String) {
        val session = db.sessionDao().getById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        val updated = session.copy(name = newName)
        db.sessionDao().update(updated.toEntity())
    }
    
    // Delete session - wipe all data
    suspend fun deleteSession(sessionId: String) {
        // Stop using this session if it's current
        if (currentSessionId == sessionId) {
            currentSessionId = null
        }
        
        // Delete tabs, history, bookmarks, matrix, scripts from DB
        db.tabDao().deleteBySessionId(sessionId)
        db.historyDao().deleteBySessionId(sessionId)
        db.bookmarkDao().deleteBySessionId(sessionId)
        db.matrixDao().deleteBySessionId(sessionId)
        db.userScriptDao().deleteBySessionId(sessionId)
        
        // Delete session entity
        db.sessionDao().deleteById(sessionId)
        
        // Wipe web data (cookies, localStorage, etc.)
        val runtime = engineProvider.getRuntime()
        if (runtime != null) {
            storageCleaner.clearSessionData(runtime, sessionId)
        }
        
        // Delete profile directory
        profileManager.deleteProfileDir(sessionId)
    }
    
    // Switch to a session - returns the session and its tabs
    suspend fun switchToSession(sessionId: String): SessionWithTabs {
        val sessionEntity = db.sessionDao().getById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        
        // Update last active
        db.sessionDao().updateLastActive(sessionId, System.currentTimeMillis())
        
        val session = sessionEntity.toDomain()
        currentSessionId = sessionId
        
        // Get tabs for this session
        val tabEntities = db.tabDao().getBySessionIdOnce(sessionId)
        val tabs = tabEntities.map { it.toDomain() }
        
        return SessionWithTabs(session, tabs)
    }
    
    // Get current session
    suspend fun getCurrentSession(): Session? {
        currentSessionId?.let { id ->
            val entity = db.sessionDao().getById(id)
            return entity?.toDomain()
        }
        return null
    }
    
    // Get all sessions
    fun getAllSessions(): kotlinx.coroutines.flow.Flow<List<Session>> {
        return db.sessionDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }
    
    suspend fun getAllSessionsOnce(): List<Session> {
        return db.sessionDao().getAllOnce().map { it.toDomain() }
    }
    
    // Get profile directory for session
    fun getProfileDir(sessionId: String): String = profileManager.getProfileDir(sessionId)
    
    data class SessionWithTabs(
        val session: Session,
        val tabs: List<Tab>
    )
}

// Extensions for entity <-> domain conversion
private fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    name = name,
    colorHex = colorHex,
    iconKey = iconKey,
    createdAt = createdAt,
    lastActive = lastActive
)

private fun SessionEntity.toDomain(): Session = Session(
    id = id,
    name = name,
    colorHex = colorHex,
    iconKey = iconKey,
    createdAt = createdAt,
    lastActive = lastActive
)