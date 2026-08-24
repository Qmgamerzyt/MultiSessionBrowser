package com.app.browser.extension

import com.app.browser.data.AppDatabase
import com.app.browser.data.entities.MatrixEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtensionSessionMatrix(private val db: AppDatabase) {
    
    // Get state for extension in session
    suspend fun getState(extensionId: String, sessionId: String): ExtensionState {
        return withContext(Dispatchers.IO) {
            val state = db.matrixDao().getState(extensionId, sessionId)
            ExtensionState.valueOf(state ?: ExtensionState.AUTO.name)
        }
    }
    
    // Set state for extension in session
    suspend fun setState(extensionId: String, sessionId: String, state: ExtensionState) {
        withContext(Dispatchers.IO) {
            val entity = MatrixEntity(extensionId, sessionId, state.name)
            db.matrixDao().insert(entity)
        }
    }
    
    // Get all states for a session
    suspend fun getStatesForSession(sessionId: String): Map<String, ExtensionState> {
        return withContext(Dispatchers.IO) {
            val entities = db.matrixDao().getBySessionIdOnce(sessionId)
            entities.associate { it.extensionId to ExtensionState.valueOf(it.state) }
        }
    }
    
    // Get all states for an extension
    suspend fun getStatesForExtension(extensionId: String): Map<String, ExtensionState> {
        return withContext(Dispatchers.IO) {
            val entities = db.matrixDao().getByExtensionId(extensionId)
            entities.associate { it.sessionId to ExtensionState.valueOf(it.state) }
        }
    }
    
    // Get full matrix
    suspend fun getFullMatrix(): Map<String, Map<String, ExtensionState>> {
        return withContext(Dispatchers.IO) {
            val entities = db.matrixDao().getAll()
            entities.groupBy { it.extensionId }
                .mapValues { (_, list) ->
                    list.associate { it.sessionId to ExtensionState.valueOf(it.state) }
                }
        }
    }
    
    // Initialize default state for new session
    suspend fun initializeForSession(sessionId: String, extensionIds: List<String>) {
        withContext(Dispatchers.IO) {
            val entities = extensionIds.map { extId ->
                MatrixEntity(extId, sessionId, ExtensionState.AUTO.name)
            }
            db.matrixDao().insertAll(entities)
        }
    }
    
    // Remove session from matrix
    suspend fun removeSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            db.matrixDao().deleteBySessionId(sessionId)
        }
    }
}