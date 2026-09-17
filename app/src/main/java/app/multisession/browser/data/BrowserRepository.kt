package app.multisession.browser.data

import androidx.room.withTransaction
import app.multisession.browser.data.db.AppDatabase

/** Thin facade over the Room DAOs plus the few compound operations the app needs. */
class BrowserRepository(private val db: AppDatabase) {
    val sessions get() = db.sessions()
    val tabs get() = db.tabs()
    val tabGroups get() = db.tabGroups()
    val history get() = db.history()
    val bookmarks get() = db.bookmarks()
    val sitePermissions get() = db.sitePermissions()
    val downloads get() = db.downloads()

    /** Deletes a session and everything owned by it in one transaction (downloads history is kept on purpose). */
    suspend fun deleteSessionCascade(sessionId: String) {
        db.withTransaction {
            db.tabs().deleteForSession(sessionId)
            db.tabGroups().deleteForSession(sessionId)
            db.history().clear(sessionId)
            db.bookmarks().deleteForSession(sessionId)
            db.sitePermissions().deleteForSession(sessionId)
            db.sessions().delete(sessionId)
        }
    }

    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
