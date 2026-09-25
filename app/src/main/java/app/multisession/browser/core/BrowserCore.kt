package app.multisession.browser.core

import android.app.Application
import android.content.SharedPreferences
import app.multisession.browser.data.BrowserRepository
import app.multisession.browser.data.db.AppDatabase
import app.multisession.browser.downloads.AppDownloadManager
import app.multisession.browser.engine.GeckoEngine
import app.multisession.browser.engine.LocalContentLoader
import app.multisession.browser.extensions.ExtensionManager
import app.multisession.browser.permissions.SitePermissionStore
import app.multisession.browser.projects.ProjectManager
import app.multisession.browser.session.SessionIsolation
import app.multisession.browser.session.SessionManager
import app.multisession.browser.tabs.Tab
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.workspaces.WorkspaceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application-scoped single source of truth. Lives as long as the process does, so GeckoSessions
 * survive Activity recreation (rotation, theme change) without reloading.
 */
class BrowserCore(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dbMutex = Mutex() // fair (FIFO): DB writes apply in the order they were issued

    val db: AppDatabase = AppDatabase.create(app)
    val repo = BrowserRepository(db)
    val engine = GeckoEngine(app)
    val isolation = SessionIsolation(engine)
    val localContent = LocalContentLoader(app)
    val projects = ProjectManager(app, localContent)
    val sessions = SessionManager(this)
    val tabs = TabManager(this)
    val downloads = AppDownloadManager(this)
    val sitePermissions = SitePermissionStore(this)
    val extensions = ExtensionManager(this)
    val workspaces = WorkspaceManager(this)

    private val ready = CompletableDeferred<Unit>()

    /** True once a preference changed; TabManager.reapplySettings() only touches the engine when set. */
    @Volatile var settingsDirty = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.KEY_ACTIVE_SESSION) settingsDirty = true
    }

    init {
        Prefs.registerListener(prefListener)
        scope.launch {
            try {
                sessions.initialize()
                // Every tab's SessionState JSON is parsed here: keep that off the main thread.
                val restored = withContext(Dispatchers.Default) { repo.tabs.getAll().map { Tab.from(it) } }
                val groups = repo.tabGroups.getAll()
                val closedTabs = repo.closedTabs.getAll()
                val closedGroups = repo.closedGroups.getAll()
                tabs.loadFromDb(restored, groups, closedTabs, closedGroups)
                workspaces.load()
                sitePermissions.load()
                downloads.load()
                sessions.startObserving()
                extensions.start()
                AppLog.i(TAG, "Core ready. engine=${isolation.engineVersion()} sessions=${sessions.sessions.value.size}")
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
