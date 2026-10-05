package app.multisession.browser.core

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.pm.PackageInfoCompat
import app.multisession.browser.data.BrowserRepository
import app.multisession.browser.data.db.AppDatabase
import app.multisession.browser.data.db.ClosedGroupEntity
import app.multisession.browser.data.db.ClosedTabEntity
import app.multisession.browser.data.db.TabGroupEntity
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
            val failures = ArrayList<Pair<String, Throwable>>()
            try {
                // v2.2.0-beta-1 (tab-loss fix): every restore source runs in its own fault-isolated
                // stage. One exception - e.g. a tab row whose sessionState blob is larger than the
                // Android cursor window - used to abort the WHOLE sequence: loadFromDb never ran
                // (zero tabs in every session) and extensions.start() was skipped (extension quick
                // actions missing), while sessions/history/cookies - read elsewhere - kept working.
                // Failures are logged, written to filesDir/init_status.txt and copied into
                // crash_trace.txt so the next launch posts them as a Copy/Share notification.
                stage("sessions", failures) { sessions.initialize() }
                var restored: List<Tab> = emptyList()
                var groups: List<TabGroupEntity> = emptyList()
                var closed: List<ClosedTabEntity> = emptyList()
                var closedGroups: List<ClosedGroupEntity> = emptyList()
                stage("tabs-read", failures) { restored = readTabsForRestore() }
                stage("groups-read", failures) { groups = repo.tabGroups.getAll() }
                stage("closed-read", failures) {
                    closed = repo.closedTabs.getAll()
                    closedGroups = repo.closedGroups.getAll()
                }
                stage("tabs-load", failures) { tabs.loadFromDb(restored, groups, closed, closedGroups) }
                stage("workspaces", failures) { workspaces.load() }
                stage("permissions", failures) { sitePermissions.load() }
                stage("downloads", failures) { downloads.load() }
                stage("observer", failures) { sessions.startObserving() }
                stage("extensions", failures) { extensions.start() }
                AppLog.i(TAG, "Core ready. engine=${isolation.engineVersion()} sessions=${sessions.sessions.value.size} tabs=${tabs.countAll()} failures=${failures.size}")
                runCatching { writeInitReport(failures) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Core initialisation failed", t)
                failures += "unstaged" to t
                runCatching { writeInitReport(failures) }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    /** One fault-isolated init stage: its failure is recorded and the following stages still run. */
    private suspend fun stage(name: String, failures: MutableList<Pair<String, Throwable>>, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures += name to t
            AppLog.e(TAG, "Core initialisation failed at '$name'", t)
        }
    }

    /**
     * Tab restore read with one deliberate fallback (v2.2.0-beta-1): the normal path maps every row
     * INCLUDING its sessionState into one bulk cursor read; a single row whose state JSON exceeds
     * the cursor-window limit threw there and took down the entire restore. On failure the light
     * columns are re-read and each row's state is fetched individually - an oversized blob now only
     * skips that one tab's page state (the tab itself still comes back at its URL).
     */
    private suspend fun readTabsForRestore(): List<Tab> {
        return try {
            withContext(Dispatchers.Default) { repo.tabs.getAll().map { Tab.from(it) } }
        } catch (t: Throwable) {
            AppLog.w(TAG, "tabs.getAll failed - restoring without unreadable sessionState blobs", t)
            withContext(Dispatchers.Default) {
                repo.tabs.getAllLite().map { row ->
                    val state = try {
                        repo.tabs.stateOf(row.id)
                    } catch (t2: Throwable) {
                        AppLog.w(TAG, "sessionState unreadable for tab ${row.id.take(8)} - restoring it without page state", t2)
                        null
                    }
                    Tab.from(row.toEntity(state))
                }
            }
        }
    }

    /**
     * FilesDir status report, overwritten on every start (v2.2.0-beta-1): stage results plus the
     * FULL stack of every failure. On failure the same text is also written to crash_trace.txt, so
     * the existing Crash-trace notification (Copy/Share, posted on the next launch) lets a user
     * without adb hand the exact throwing frame over from the phone itself.
     */
    private fun writeInitReport(failures: List<Pair<String, Throwable>>) {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val version = "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val sb = StringBuilder()
        sb.append("time=").append(stamp).append('\n')
        sb.append("app=").append(version).append('\n')
        sb.append("status=").append(
            if (failures.isEmpty()) "OK"
            else "FAILED at " + failures.joinToString(", ") { it.first }
        ).append('\n')
        sb.append("sessions=").append(sessions.sessions.value.size)
        sb.append(" tabs=").append(tabs.countAll()).append('\n')
        if (failures.isNotEmpty()) {
            sb.append('\n')
            for ((name, t) in failures) {
                sb.append("stage=").append(name).append('\n')
                var c: Throwable? = t
                while (c != null) {
                    sb.append(c.toString()).append('\n')
                    for (el in c.stackTrace) sb.append("  at ").append(el.toString()).append('\n')
                    sb.append("--- cause ---\n")
                    c = c.cause
                }
            }
            java.io.File(app.filesDir, "crash_trace.txt").writeText(sb.toString())
        }
        java.io.File(app.filesDir, "init_status.txt").writeText(sb.toString())
    }

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
