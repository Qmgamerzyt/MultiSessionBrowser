package app.multisession.browser.core

import android.app.Application
import app.multisession.browser.data.BrowserRepository
import app.multisession.browser.data.db.AppDatabase
import app.multisession.browser.projects.ProjectManager
import app.multisession.browser.session.SessionIsolation
import app.multisession.browser.session.SessionManager
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.webview.DownloadHandler
import app.multisession.browser.webview.LocalContentLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped single source of truth. Lives as long as the process does, so
 * WebViews survive Activity recreation (rotation, theme change) without reloading.
 */
class BrowserCore(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dbMutex = Mutex() // fair (FIFO): DB writes apply in the order they were issued

    val db: AppDatabase = AppDatabase.create(app)
    val repo = BrowserRepository(db)
    val isolation = SessionIsolation()
    val localContent = LocalContentLoader(app)
    val projects = ProjectManager(app)
    val sessions = SessionManager(this)
    val tabs = TabManager(this)
    val downloads = DownloadHandler(this)

    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                sessions.initialize()
                tabs.loadFromDb(repo.tabs.getAll())
                sessions.startObserving()
                AppLog.i(TAG, "Core ready. isolation=${isolation.mode} sessions=${sessions.sessions.value.size}")
            } catch (t: Throwable) {
                AppLog.e(TAG, "Core initialisation failed", t)
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Fire-and-forget persistence that still preserves ordering between writes. */
    fun persist(block: suspend () -> Unit): Job = scope.launch {
        dbMutex.withLock {
            try {
                block()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Persist failed", t)
            }
        }
    }

    suspend fun <T> persistNow(block: suspend () -> T): T = dbMutex.withLock { block() }

    private companion object {
        const val TAG = "Core"
    }
}
