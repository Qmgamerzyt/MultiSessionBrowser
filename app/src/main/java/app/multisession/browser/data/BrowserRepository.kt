package app.multisession.browser.data

import androidx.room.withTransaction
import app.multisession.browser.data.db.AppDatabase

/** Thin facade over the Room DAOs plus the few compound operations the app needs. */
class BrowserRepository(private val db: AppDatabase) {
    val sessions get() = db.sessions()
    val tabs get() = db.tabs()
    val history get() = db.history()
    val bookmarks get() = db.bookmarks()

    /** Deletes a session and everything owned by it in one transaction. */
    suspend fun deleteSessionCascade(sessionId: String) {
        db.withTransaction {
            db.tabs().deleteForSession(sessionId)
            db.history().clear(sessionId)
            db.bookmarks().deleteForSession(sessionId)
            db.sessions().delete(sessionId)
        }
    }
}
