package app.multisession.browser.ui.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.BookmarkEntity
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.downloads.AppDownloadManager
import app.multisession.browser.downloads.DownloadStatus
import app.multisession.browser.engine.BrowserHost
import app.multisession.browser.engine.SessionFactory
import app.multisession.browser.engine.WebNotifications
import app.multisession.browser.extensions.ExtensionAction
import app.multisession.browser.extensions.ExtensionHost
import app.multisession.browser.permissions.PermissionValue
import app.multisession.browser.permissions.SitePermissionStore
import app.multisession.browser.permissions.SitePermissionType
import app.multisession.browser.tabs.PageError
import app.multisession.browser.tabs.Tab
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.ui.downloads.DownloadsActivity
import app.multisession.browser.ui.extensions.ExtensionsActivity
import app.multisession.browser.ui.library.BookmarksActivity
import app.multisession.browser.ui.library.HistoryActivity
import app.multisession.browser.ui.library.ProjectsActivity
import app.multisession.browser.ui.library.SimpleListActivity
import app.multisession.browser.ui.sessions.SessionEditDialog
import app.multisession.browser.ui.sessions.SessionsDrawer
import app.multisession.browser.ui.settings.SettingsActivity
import app.multisession.browser.ui.tabs.TabsSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.net.URISyntaxException

/**
 * The browser screen. One [GeckoView] displays the GeckoSession of the current tab; sessions are
 * swapped in/out on tab switch (never destroyed for that). The native start page replaces the
 * GeckoView for empty tabs.
 *
 * v2.0.2 UI: everything is app-owned - a left session drawer, compact back/forward/undo/redo next to it, the
 * central address bar, a tabs button (tab/group sheet), a right app-menu drawer and an optional floating HUD.
 *
 * Focus rules (v1.1.2 fix, preserved): the invisible focusHolder is the first focusable view, the
 * URL bar only gains focus from an explicit user tap, and returning to the app never rebuilds or
 * re-attaches the displayed content view - so a focused web input (e.g. OTP field) keeps focus.
 */
class BrowserActivity : AppCompatActivity(), BrowserHost, ExtensionHost, TabManager.Listener, StartPageController.Callbacks, HudController.Actions {

    private val core get() = BrowserApp.core()
    override val activity: AppCompatActivity get() = this

    // ---- views
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var root: View
    private lateinit var browserRoot: View
    private lateinit var isolationBanner: TextView
    private lateinit var topBar: View
    private lateinit var sessionDot: View
    private lateinit var sessionName: TextView
    private lateinit var focusHolder: View
    private lateinit var urlInput: EditText
    private lateinit var securityIcon: ImageView
    private lateinit var reloadStopButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var errorPage: View
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var tabCountView: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var sessionsDrawerView: View
    private lateinit var menuDrawerView: View
    private lateinit var sessionsDrawer: SessionsDrawer
    private lateinit var appMenu: AppMenu
    private lateinit var hud: HudController

    /** Created lazily and attached to [webContainer] only together with an OPEN session (see attachSession). */
    private var geckoView: GeckoView? = null

    private var startPage: StartPageController? = null
    private var currentTabId: String? = null
    private var uiReady = false
    private var inFullScreen = false
    /** Toolbar hidden by the HUD "fullscreen" item (not HTML5 fullscreen). */
    private var toolbarHidden = false
    private lateinit var suggestionPopup: SuggestionPopup

    private var fileResultCallback: ((Array<Uri>?) -> Unit)? = null
    private var cameraOutputUri: Uri? = null
    private var pendingPermissionAction: ((Map<String, Boolean>) -> Unit)? = null
    private var activePermissionDialog: AlertDialog? = null
    /** Permission prompts are shown one at a time, in order: a second request never cancels the first. */
    private val promptQueue = ArrayDeque<PendingPrompt>()
    private var promptShowing = false
    private val sitePermissions get() = core.sitePermissions
    private var extensionPopup: BottomSheetDialog? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleFileChooserResult(result.resultCode, result.data)
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        action?.invoke(grants)
    }
    private val openUrlLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val url = result.data?.getStringExtra(SimpleListActivity.EXTRA_OPEN_URL) ?: return@registerForActivityResult
            val newTab = result.data?.getBooleanExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, false) ?: false
            openUrl(url, newTab)
        }
    }

    private val currentTab: Tab? get() = core.tabs.get(currentTabId)
    fun currentTabIdOrNull(): String? = currentTabId

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        bindViews()
        postCrashTraceIfAny()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })

        lifecycleScope.launch {
            core.awaitReady()
            uiReady = true
            core.tabs.addListener(this@BrowserActivity)
            core.tabs.host = this@BrowserActivity
            core.extensions.host = this@BrowserActivity
            updateIsolationBanner()
            showActiveSessionTab()
            handleIntent(intent)
            launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    core.sessions.active.collect { updateSessionChip(it) }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    core.sessions.sessions.collect { if (drawerLayout.isDrawerOpen(sessionsDrawerView)) sessionsDrawer.refresh() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (uiReady) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_HANDLED, false)) return
        intent.putExtra(EXTRA_HANDLED, true)
        // Tap on a website notification: bring the browser up and report the click to the page.
        intent.getStringExtra(WebNotifications.EXTRA_TAG)?.let { tag -> core.engine.notifications.click(tag); return }
        val data = intent.data ?: return
        if (intent.action == Intent.ACTION_VIEW && UrlUtils.isWebUrl(data.toString())) {
            openUrl(data.toString(), newTab = true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!uiReady) return
        core.tabs.host = this
        core.extensions.host = this
        core.tabs.reapplySettings()
        val session = core.sessions.active.value
        val tab = currentTab
        if (session != null && tab != null && tab.sessionId == session.id && isDisplayed(tab)) {
            // Same tab is still on screen: leave the GeckoView and its session exactly as they are.
            // Re-attaching/reloading here would move Android focus to the URL bar and open the keyboard
            // (the OTP-paste bug). The page keeps its own focus (e.g. the OTP input).
            core.tabs.setDisplayed(tab)
            updateToolbar(tab)
        } else {
            showActiveSessionTab()
        }
    }

    /** True when [tab]'s content (its GeckoSession in the GeckoView, or the native start page) is what is on screen. */
    private fun isDisplayed(tab: Tab): Boolean {
        val gv = geckoView
        val gs = tab.geckoSession
        return if (gs != null) gv != null && gv.parent === webContainer && gv.isVisible && gv.session === gs
        else tab.isStartPage && startPage?.view?.parent === webContainer
    }

    override fun onStop() {
        super.onStop()
        if (!uiReady) return
        // The displayed session stays ACTIVE in the background on purpose: audio / voice calls
        // (Discord) keep running and the page keeps its input focus for when the user returns.
        currentTab?.let { captureThumbnail(it) }
        core.tabs.persistAll()
        if (core.tabs.host === this) core.tabs.host = null
        if (core.extensions.host === this) core.extensions.host = null
    }

    override fun onDestroy() {
        if (uiReady) {
            core.tabs.removeListener(this)
            core.tabs.detachFromActivity()
        }
        // Give the session back so a recreated Activity can attach it to its own GeckoView.
        try { geckoView?.releaseSession() } catch (t: Throwable) { AppLog.w(TAG, "releaseSession failed", t) }
        promptQueue.forEach { it.cancel() }
        promptQueue.clear()
        activePermissionDialog?.dismiss()
        extensionPopup?.dismiss()
        if (::suggestionPopup.isInitialized) suggestionPopup.dismiss()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        core.engine.onConfigurationChanged()
    }

    // ================================================================== view binding

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        root = findViewById(R.id.root)
        browserRoot = findViewById(R.id.browserRoot)
        isolationBanner = findViewById(R.id.isolationBanner)
        topBar = findViewById(R.id.topBar)
        sessionDot = findViewById(R.id.sessionDot)
        sessionName = findViewById(R.id.sessionName)
        focusHolder = findViewById(R.id.focusHolder)
        urlInput = findViewById(R.id.urlInput)
        securityIcon = findViewById(R.id.securityIcon)
        reloadStopButton = findViewById(R.id.reloadStopButton)
        progressBar = findViewById(R.id.progressBar)
        webContainer = findViewById(R.id.webContainer)
        errorPage = findViewById(R.id.errorPage)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        tabCountView = findViewById(R.id.tabCount)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        sessionsDrawerView = findViewById(R.id.sessionsDrawer)
        menuDrawerView = findViewById(R.id.menuDrawer)
        sessionsDrawer = SessionsDrawer(this, core, sessionsDrawerView)
        appMenu = AppMenu(menuDrawerView, core)
        hud = HudController(findViewById<LinearLayout>(R.id.hud), findViewById(R.id.hudNub), this)
        // Drawers are opened only through their buttons (a swipe from the edge would fight with page gestures).
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView === sessionsDrawerView) sessionsDrawer.refresh() else if (drawerView === menuDrawerView) renderMenu()
            }
            override fun onDrawerClosed(drawerView: View) { drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED) }
        })

        findViewById<View>(R.id.sessionsButton).setOnClickListener { exitSearchMode(); openDrawer(sessionsDrawerView) }
        findViewById<View>(R.id.menuButton).setOnClickListener { exitSearchMode(); openDrawer(menuDrawerView) }
        isolationBanner.setOnClickListener { showIsolationInfo() }
        securityIcon.setOnClickListener { currentTab?.let { openSitePermissions(it) } }

        urlInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(v.text.toString()); true
            } else false
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                urlInput.post { if (urlInput.hasFocus()) urlInput.selectAll() }
            } else {
                // Search mode ended: suggestions are only valid while the bar is focused.
                suggestionPopup.dismiss()
                currentTab?.let { updateToolbar(it) }
            }
        }
        urlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Only user typing (bar focused) may open suggestions. Programmatic setText() from
                // updateToolbar() must never do so.
                if (!isSearchActive()) { suggestionPopup.dismiss(); return }
                val input = s?.toString() ?: return
                if (UrlUtils.isJavaScriptUrl(input)) { suggestionPopup.dismiss(); return }   // bookmarklets are never "searched"
                val session = core.sessions.get(currentTab?.sessionId ?: return) ?: return
                suggestionPopup.showSuggestions(input, session.id)
            }
        })
        reloadStopButton.setOnClickListener {
            val tab = currentTab ?: return@setOnClickListener
            if (tab.isLoading) tab.geckoSession?.stop() else reload(tab)
        }
        backButton.setOnClickListener { goBack() }
        forwardButton.setOnClickListener { goForward() }
        findViewById<View>(R.id.newTabButton).setOnClickListener { newTab() }
        findViewById<View>(R.id.tabsButton).setOnClickListener { exitSearchMode(); TabsSheet().show(supportFragmentManager, "tabs") }

        findViewById<View>(R.id.errorRetry).setOnClickListener { currentTab?.let { reload(it) } }
        findViewById<View>(R.id.errorBack).setOnClickListener {
            val tab = currentTab ?: return@setOnClickListener
            if (tab.canGoBack && tab.geckoSession != null) { tab.error = null; tab.geckoSession?.goBack() } else loadInTab(tab, UrlUtils.START_PAGE)
        }
        findViewById<View>(R.id.errorExternal).setOnClickListener { currentTab?.error?.url?.let { openExternal(it) } }

        suggestionPopup = SuggestionPopup(this, core.repo, lifecycleScope, ::isSearchActive) { item ->
            navigate(item.url)
        }
        suggestionPopup.anchor(findViewById(R.id.urlBox))
    }

    private fun openDrawer(view: View) {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerLayout.openDrawer(view)
    }

    fun closeDrawers() = drawerLayout.closeDrawers()

    /** The single GeckoView of this Activity (created on first use). */
    @SuppressLint("ClickableViewAccessibility")
    private fun obtainGeckoView(): GeckoView = geckoView ?: GeckoView(this).also { gv ->
        gv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        gv.setOnTouchListener(webTouchListener)
        geckoView = gv
    }

    /**
     * Shows [gs] in the GeckoView. The view is added to the hierarchy only once it has an OPEN
     * session (GeckoView would otherwise create its own session/runtime on attach). Switching tabs
     * only swaps the session - nothing is destroyed or recreated.
     */
    private fun attachSession(tab: Tab, gs: GeckoSession) {
        if (!gs.isOpen) {
            AppLog.w(TAG, "attachSession skipped: session of ${tab.id.take(8)} is not open yet")
            return
        }
        val gv = obtainGeckoView()
        try {
            if (gv.session !== gs) {
                gv.releaseSession()
                gv.setSession(gs)
            }
            gs.selectionActionDelegate = BasicSelectionActionDelegate(this)
        } catch (t: Throwable) {
            AppLog.e(TAG, "attachSession failed for ${tab.id.take(8)}", t)
        }
        if (gv.parent !== webContainer) {
            (gv.parent as? ViewGroup)?.removeView(gv)
            webContainer.addView(gv, 0)
        }
        gv.isVisible = true
    }

    private fun detachGeckoSession() {
        val gv = geckoView ?: return
        try { gv.releaseSession() } catch (t: Throwable) { AppLog.w(TAG, "releaseSession failed", t) }
        gv.isVisible = false
    }

    // ================================================================== search / focus state

    /** The single source of truth for "search mode": the address bar has input focus in a started Activity. */
    private fun isSearchActive(): Boolean =
        ::urlInput.isInitialized && urlInput.hasFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Leaves search mode explicitly: hides suggestions and the keyboard and parks focus on the
     * invisible holder so the framework cannot hand it back to the EditText.
     */
    /** TEMPORARY (2.1.4, see BUGFIX_TABSSHEET_CRASH.md): posts the stack trace captured by BrowserApp's
     *  exception handler after the previous process crashed as a notification with Copy/Share actions,
     *  so the exact throwing frame can be reported without adb. Purely observational - the trace file
     *  is consumed once Android accepts the notification (the old dialog consumed it on display); the
     *  crash itself was never altered. If notifications are blocked or the permission is denied, the
     *  file is kept and posting is retried on the next launch (never a dialog). */
    private fun postCrashTraceIfAny() {
        val f = java.io.File(filesDir, "crash_trace.txt")
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        if (text.isBlank()) { runCatching { f.delete() }; return }
        fun tryPost() {
            if (CrashTraceNotifier.post(this, text)) runCatching { f.delete() }
        }
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) tryPost()
        else requestAndroidPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) { tryPost() }
    }

    private fun exitSearchMode() {
        suggestionPopup.dismiss()
        if (urlInput.hasFocus()) {
            hideKeyboard()
            focusHolder.requestFocus()
        }
        currentTab?.let { updateToolbar(it) }
    }

    /** Touching the page always ends search mode and gives focus to the page (dismisses suggestions). */
    private val webTouchListener = View.OnTouchListener { v, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (urlInput.hasFocus()) exitSearchMode()
            if (!v.hasFocus()) v.requestFocus()
        }
        false // never consume: the GeckoView handles the event normally
    }

    // ================================================================== session / tab display

    private fun showActiveSessionTab() {
        val session = core.sessions.active.value ?: return
        val tab = core.tabs.activeTab(session.id) ?: core.tabs.createTab(session.id, homeUrl(), select = true)
        showTab(tab)
    }

    private fun homeUrl(): String = core.localContent.bundledAppUrl() ?: Prefs.homepage

    @SuppressLint("ClickableViewAccessibility")
    fun showTab(tab: Tab) {
        if (tab.archived) core.tabs.unarchiveTabs(listOf(tab.id))   // showing an archived tab brings it back first
        val previous = currentTab
        if (previous != null && previous.id != tab.id) {
            captureThumbnail(previous)
            if (inFullScreen) {
                previous.geckoSession?.exitFullScreen()
                onFullScreenChanged(previous, false)
            }
        }
        currentTabId = tab.id
        core.tabs.selectTab(tab)
        exitSearchMode()

        if (tab.isStartPage && tab.geckoSession == null) {
            detachGeckoSession()
            core.tabs.setDisplayed(null)
            showStartPage(tab)
        } else {
            val gs = try {
                core.tabs.ensureSession(tab)
            } catch (t: Throwable) {
                AppLog.e(TAG, "ensureSession failed", t)
                tab.error = PageError(TabManager.ERROR_RENDERER_GONE, 0, t.message ?: "", tab.url)
                null
            }
            startPage?.view?.let { if (it.parent === webContainer) webContainer.removeView(it) }
            if (gs != null) {
                attachSession(tab, gs)
                core.tabs.setDisplayed(tab)
            }
        }
        updateToolbar(tab)
        updateErrorPage(tab)
        updateTabCount()
        core.tabs.enforceLiveLimit(tab.id)
    }

    private fun showStartPage(tab: Tab) {
        val controller = startPage ?: StartPageController(
            LayoutInflater.from(this).inflate(R.layout.view_start_page, webContainer, false), core, this
        ).also { startPage = it }
        if (controller.view.parent !== webContainer) {
            (controller.view.parent as? ViewGroup)?.removeView(controller.view)
            webContainer.addView(controller.view)
        }
        lifecycleScope.launch { controller.refresh(tab.sessionId) }
    }

    override fun switchSession(sessionId: String) {
        lifecycleScope.launch {
            currentTab?.let { captureThumbnail(it) }
            core.sessions.switchTo(sessionId) ?: return@launch
            currentTabId = null
            showActiveSessionTab()
            updateIsolationBanner()
        }
    }

    /** The active session was deleted: show whatever session is active now. */
    fun onActiveSessionReplaced() { currentTabId = null; showActiveSessionTab() }

    /** Re-shows the current tab (used after its session data was cleared). */
    fun refreshCurrentTab() { currentTab?.let { showTab(it) } }

    fun newTab(url: String = homeUrl(), groupId: String? = null) {
        val session = core.sessions.active.value ?: return
        val tab = core.tabs.createTab(session.id, url, select = true, groupId = groupId)
        showTab(tab)
        if (tab.isStartPage) focusUrlBar()
    }

    fun closeTab(tab: Tab) {
        val wasCurrent = tab.id == currentTabId
        val next = core.tabs.closeTab(tab.id)
        if (wasCurrent) {
            currentTabId = null
            showTab(next ?: core.tabs.createTab(tab.sessionId, homeUrl(), select = true))
        }
        updateTabCount()
    }

    /** Several tabs were closed by TabManager (group actions): make sure something is displayed. */
    fun onTabsClosed(closed: List<Tab>) {
        val sid = core.sessions.activeId ?: return
        if (closed.any { it.id == currentTabId } || currentTab == null) {
            currentTabId = null
            val next = core.tabs.activeTab(sid) ?: core.tabs.createTab(sid, homeUrl(), select = true)
            showTab(next)
        }
        updateTabCount()
    }

    fun closeAllTabs(sessionId: String) {
        core.tabs.closeAllTabs(sessionId)
        currentTabId = null
        showActiveSessionTab()
    }

    private fun navigate(input: String) {
        val tab = currentTab ?: return
        exitSearchMode()
        if (UrlUtils.isJavaScriptUrl(input)) { runJavaScript(tab, input); return }
        loadInTab(tab, UrlUtils.resolveInput(input))
    }

    /** javascript: URL / bookmarklet typed or pasted into the address bar: executed in the current page, never searched. */
    private fun runJavaScript(tab: Tab, source: String) {
        if (tab.isStartPage || tab.geckoSession?.isOpen != true) { snack(getString(R.string.js_no_page)); updateToolbar(tab); return }
        if (core.tabs.runScript(tab, source)) snack(getString(R.string.js_executed))
        updateToolbar(tab)   // the address bar shows the page URL again, not the script
    }

    private fun loadInTab(tab: Tab, url: String) {
        tab.error = null
        if (UrlUtils.isStartPage(url)) {
            core.tabs.hibernate(tab)
            tab.savedState = null
            tab.url = UrlUtils.START_PAGE
            tab.title = ""
            tab.canGoBack = false
            tab.canGoForward = false
            core.tabs.persistTab(tab)
        } else {
            // A hibernated tab gets its saved history restored first (loadContent = true, no-op for a live session),
            // so the page typed now is appended to that history and Back keeps working exactly as in a live tab.
            val gs = core.tabs.ensureSession(tab, loadContent = tab.savedState != null)
            tab.savedState = null
            tab.url = url
            tab.title = ""
            gs.load(GeckoSession.Loader().uri(url))
        }
        if (tab.id == currentTabId) showTab(tab) else core.tabs.persistTab(tab)
    }

    override fun openUrl(url: String, newTab: Boolean) {
        val tab = currentTab
        if (newTab || tab == null) newTab(url) else loadInTab(tab, url)
    }

    /** Only called from explicit user actions (start-page search box, "new tab" button). */
    override fun focusUrlBar() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        urlInput.requestFocus()
        urlInput.post {
            if (urlInput.hasFocus()) getSystemService(InputMethodManager::class.java).showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun openSessions() = openDrawer(sessionsDrawerView)

    private fun reload(tab: Tab) {
        tab.error = null
        if (tab.isStartPage) { showTab(tab); return }
        val gs = tab.geckoSession
        if (gs == null || !gs.isOpen) {
            showTab(tab)   // re-opens the session and restores state / loads the URL
        } else {
            gs.reload()
            updateErrorPage(tab)
        }
    }

    private fun goBack() {
        val tab = currentTab ?: return
        val gs = tab.geckoSession
        if (gs != null && tab.canGoBack) { tab.error = null; gs.goBack(); updateErrorPage(tab) }
    }

    private fun goForward() {
        val tab = currentTab ?: return
        if (tab.canGoForward) tab.geckoSession?.goForward()
    }

    private fun handleBack() {
        val tab = currentTab
        when {
            drawerLayout.isDrawerOpen(sessionsDrawerView) || drawerLayout.isDrawerOpen(menuDrawerView) -> drawerLayout.closeDrawers()
            inFullScreen -> {
                val gs = tab?.geckoSession
                if (gs != null) gs.exitFullScreen()          // Gecko answers with onFullScreen(false)
                else if (tab != null) onFullScreenChanged(tab, false)
                else inFullScreen = false
            }
            toolbarHidden -> setToolbarHidden(false)
            urlInput.hasFocus() -> exitSearchMode()
            tab?.geckoSession != null && tab.canGoBack -> goBack()
            tab?.openerTabId != null -> closeTab(tab)
            tab != null && !tab.isStartPage && tab.geckoSession == null -> loadInTab(tab, UrlUtils.START_PAGE)
            else -> moveTaskToBack(true) // never kill the app on back; state is kept
        }
    }

    // ================================================================== toolbar state

    private fun updateSessionChip(session: SessionEntity?) {
        session ?: return
        sessionName.text = session.name
        val dot = DrawableCompat.wrap(ContextCompat.getDrawable(this, R.drawable.bg_dot)!!.mutate())
        DrawableCompat.setTint(dot, session.color)
        sessionDot.background = dot
        findViewById<View>(R.id.sessionsButton).contentDescription = getString(R.string.cd_session_chip, session.name)
        updateTabCount()
    }

    private fun updateToolbar(tab: Tab) {
        if (!urlInput.hasFocus()) {
            // Runs on every progress tick: only touch the EditText (layout + text watcher) when the text changed.
            val text = if (tab.isStartPage) "" else tab.url
            if (urlInput.text?.toString() != text) urlInput.setText(text)
        }
        securityIcon.setImageResource(
            when {
                tab.isStartPage -> R.drawable.ic_search
                UrlUtils.isLocalContent(tab.url) -> R.drawable.ic_lock
                UrlUtils.isSecure(tab.url) && (tab.isSecure || tab.isLoading) -> R.drawable.ic_lock
                else -> R.drawable.ic_info
            }
        )
        reloadStopButton.setImageResource(if (tab.isLoading) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadStopButton.contentDescription = getString(if (tab.isLoading) R.string.stop else R.string.reload)
        progressBar.isVisible = tab.isLoading && tab.progress < 100 && !inFullScreen
        progressBar.progress = tab.progress
        val live = tab.geckoSession != null
        backButton.isEnabled = live && tab.canGoBack
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.isEnabled = live && tab.canGoForward
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
    }

    private fun updateTabCount() {
        val sid = core.sessions.activeId ?: return
        val count = core.tabs.countFor(sid)
        tabCountView.text = if (count > 99) "99+" else count.toString()
    }

    private fun updateIsolationBanner() {
        isolationBanner.isVisible = !core.isolation.isIsolated && !inFullScreen && !toolbarHidden
    }

    private fun showIsolationInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.isolation_title)
            .setMessage(core.isolation.describe(this))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Permissions manager for the site in [tab] (app menu -> Site permissions, or tap the lock/info icon in the URL bar). */
    fun openSitePermissions(tab: Tab) {
        if (tab.isStartPage || SitePermissionStore.originOf(tab.url) == null) { snack(getString(R.string.perm_no_site)); return }
        SitePermissionsDialog(this, core, tab).show()
    }

    private fun updateErrorPage(tab: Tab) {
        val err = tab.error
        if (err == null) { errorPage.isVisible = false; return }
        val (title, message) = describeError(err)
        errorPage.findViewById<TextView>(R.id.errorTitle).text = title
        errorPage.findViewById<TextView>(R.id.errorMessage).text = message
        errorPage.findViewById<TextView>(R.id.errorUrl).text = err.url
        errorPage.findViewById<View>(R.id.errorExternal).isVisible = UrlUtils.isWebUrl(err.url)
        errorPage.isVisible = true
    }

    private fun describeError(err: PageError): Pair<String, String> {
        val offline = isOffline()
        return when {
            err.code == TabManager.ERROR_RENDERER_GONE || err.code == WebRequestError.ERROR_CONTENT_CRASHED ->
                getString(R.string.err_crash_title) to getString(R.string.err_crash_msg)
            err.code == WebRequestError.ERROR_UNKNOWN_HOST || err.code == WebRequestError.ERROR_OFFLINE ->
                if (offline) getString(R.string.err_offline_title) to getString(R.string.err_offline_msg)
                else getString(R.string.err_host_title) to getString(R.string.err_host_msg)
            err.code == WebRequestError.ERROR_CONNECTION_REFUSED || err.code == WebRequestError.ERROR_NET_TIMEOUT ||
                err.code == WebRequestError.ERROR_NET_INTERRUPT || err.code == WebRequestError.ERROR_NET_RESET ->
                if (offline) getString(R.string.err_offline_title) to getString(R.string.err_offline_msg)
                else getString(R.string.err_unavailable_title) to getString(R.string.err_unavailable_msg)
            err.category == WebRequestError.ERROR_CATEGORY_SECURITY ->
                getString(R.string.err_ssl_title) to getString(R.string.err_ssl_msg)
            err.category == WebRequestError.ERROR_CATEGORY_URI || err.code == WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
                getString(R.string.err_unsupported_title) to getString(R.string.err_unsupported_msg)
            else -> getString(R.string.err_generic_title) to getString(R.string.err_generic_msg, err.description)
        }
    }

    private fun isOffline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ================================================================== TabManager.Listener

    override fun onTabUpdated(tab: Tab) {
        if (tab.id != currentTabId) return
        updateToolbar(tab)
        updateErrorPage(tab)
        // A start-page tab that gained a session (e.g. popup target) must show it.
        if (tab.geckoSession != null && !isDisplayed(tab) && !tab.awaitingDisplay && startPage?.view?.parent != null) showTab(tab)
    }

    override fun onTabsChanged(sessionId: String) {
        if (sessionId == core.sessions.activeId) updateTabCount()
    }

    // ================================================================== app menu (right drawer) + HUD

    private fun renderMenu() {
        val tab = currentTab
        val session = core.sessions.active.value
        val hasPage = tab != null && !tab.isStartPage
        val sid = core.sessions.activeId
        val entries = mutableListOf<MenuEntry>()
        fun entry(icon: Int, title: Int, enabled: Boolean = true, checked: Boolean? = null, badge: String? = null, run: () -> Unit) {
            entries += MenuEntry(icon, getString(title), enabled, checked, badge) { closeDrawers(); run() }
        }
        entry(R.drawable.ic_add, R.string.new_tab) { newTab() }
        entry(R.drawable.ic_label, R.string.new_group) { sid?.let { s -> app.multisession.browser.ui.tabs.GroupEditDialog.show(root, null) { n, c -> core.tabs.createGroup(s, n.ifBlank { getString(R.string.group_default_name) }, c); TabsSheet().show(supportFragmentManager, "tabs") } } }
        entry(R.drawable.ic_history, R.string.reopen_closed, enabled = sid?.let { core.tabs.hasRecentlyClosed(it) } == true) { sid?.let { s -> core.tabs.reopenClosedTab(s)?.let { showTab(it) } } }
        entry(R.drawable.ic_menu, R.string.new_session) {
            SessionEditDialog.show(this, null) { name, color, isPrivate -> lifecycleScope.launch { core.sessions.create(name, color, isPrivate); currentTabId = null; showActiveSessionTab() } }
        }
        entry(R.drawable.ic_star, R.string.add_remove_bookmark, enabled = hasPage) { tab?.let { toggleBookmark(it) } }
        entry(R.drawable.ic_share, R.string.share, enabled = hasPage) { tab?.let { shareUrl(it.url, it.title) } }
        entry(R.drawable.ic_open_in_new, R.string.open_external, enabled = hasPage) { tab?.let { openExternal(it.url) } }
        entry(R.drawable.ic_search, R.string.find_in_page, enabled = hasPage) { hudFind() }
        entry(R.drawable.ic_desktop, R.string.desktop_site, enabled = hasPage, checked = tab?.desktopMode == true) { tab?.let { toggleDesktop(it) } }
        entry(R.drawable.ic_lock, R.string.site_permissions, enabled = hasPage) { tab?.let { openSitePermissions(it) } }
        entry(R.drawable.ic_hud, R.string.hud_show, checked = hud.isVisible) { hud.setVisible(!hud.isVisible) }
        entry(R.drawable.ic_tune, R.string.hud_customize) { hud.customize() }
        entry(R.drawable.ic_fullscreen, if (toolbarHidden) R.string.toolbar_show else R.string.hud_item_fullscreen) { setToolbarHidden(!toolbarHidden) }
        entry(R.drawable.ic_star, R.string.bookmarks) { openUrlLauncher.launch(Intent(this, BookmarksActivity::class.java)) }
        entry(R.drawable.ic_history, R.string.history) { openUrlLauncher.launch(Intent(this, HistoryActivity::class.java)) }
        val active = core.downloads.activeCount
        entry(R.drawable.ic_download, R.string.downloads, badge = if (active > 0) active.toString() else null) { openUrlLauncher.launch(Intent(this, DownloadsActivity::class.java)) }
        entry(R.drawable.ic_extension, R.string.extensions, badge = core.extensions.extensions.value.size.takeIf { it > 0 }?.toString()) { openUrlLauncher.launch(Intent(this, ExtensionsActivity::class.java)) }
        entry(R.drawable.ic_folder, R.string.local_projects) { openUrlLauncher.launch(Intent(this, ProjectsActivity::class.java)) }
        entry(R.drawable.ic_delete, R.string.clear_site_data) { confirmClearSessionData() }
        entry(R.drawable.ic_tune, R.string.settings) { startActivity(Intent(this, SettingsActivity::class.java)) }
        val title = session?.name ?: getString(R.string.menu_app)
        val subtitle = if (hasPage) UrlUtils.displayHost(tab!!.url) else ""
        appMenu.render(title, subtitle, entries, core.extensions.actions.value.values) { a -> closeDrawers(); try { a.action.click() } catch (t: Throwable) { AppLog.w(TAG, "action click failed", t) } }
    }

    /** HUD "fullscreen": hide the whole toolbar so the page gets the full screen; HUD stays usable. */
    private fun setToolbarHidden(hidden: Boolean) {
        toolbarHidden = hidden
        topBar.isVisible = !hidden && !inFullScreen
        updateIsolationBanner()
        hud.onFullscreenChanged(hidden)
        if (hidden) snack(getString(R.string.toolbar_hidden_hint))
    }

    private fun pageScript(tab: Tab?, js: String) {
        val t = tab ?: currentTab ?: return
        if (t.isStartPage || t.geckoSession?.isOpen != true) return
        core.tabs.runScript(t, js)
    }

    override fun hudBack() = goBack()
    override fun hudForward() = goForward()
    override fun hudReload() { currentTab?.let { reload(it) } }
    override fun hudScrollTop() { pageScript(currentTab, "window.scrollTo({top:0,behavior:'smooth'})") }
    override fun hudScrollBottom() { pageScript(currentTab, "window.scrollTo({top:document.documentElement.scrollHeight,behavior:'smooth'})") }
    override fun hudDesktop() { currentTab?.let { toggleDesktop(it) } }
    override fun hudToggleToolbar() { setToolbarHidden(!toolbarHidden) }
    override fun hudNewTab() = newTab()
    override fun hudCloseTab() { currentTab?.let { closeTab(it) } }
    override fun hudFind() {
        val tab = currentTab ?: return
        val gs = tab.geckoSession?.takeIf { it.isOpen } ?: return
        val input = EditText(this).apply { hint = getString(R.string.find_hint); setSingleLine() }
        MaterialAlertDialogBuilder(this).setTitle(R.string.find_in_page).setView(input)
            .setPositiveButton(R.string.find_next) { _, _ ->
                val q = input.text.toString(); if (q.isNotBlank()) try { gs.finder.find(q, 0) } catch (t: Throwable) { AppLog.w(TAG, "find failed", t) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> try { gs.finder.clear() } catch (_: Throwable) {} }
            .show()
    }

    /** Desktop mode toggle: applied to the tab now AND remembered per site + session (Desktop site rule). */
    private fun toggleDesktop(tab: Tab) {
        tab.desktopMode = !tab.desktopMode
        core.sessions.get(tab.sessionId)?.let { SessionFactory.applyTabSettings(tab, it) }
        SitePermissionStore.originOf(tab.url)?.let { origin ->
            sitePermissions.set(tab.sessionId, origin, SitePermissionType.DESKTOP_SITE, if (tab.desktopMode) PermissionValue.ALLOW else PermissionValue.ASK)
        }
        tab.geckoSession?.reload()
        core.tabs.persistTab(tab)
        snack(getString(if (tab.desktopMode) R.string.desktop_on else R.string.desktop_off))
    }

    private fun shareUrl(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title\n$url")
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun openExternal(url: String): Boolean = try {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.open_external))); true
    } catch (e: ActivityNotFoundException) {
        snack(getString(R.string.no_app_found)); false
    }

    private fun toggleBookmark(tab: Tab) {
        val sid = tab.sessionId
        lifecycleScope.launch {
            val exists = core.repo.bookmarks.exists(sid, tab.url)
            if (exists) {
                core.persistNow { core.repo.bookmarks.deleteByUrl(sid, tab.url) }
                snack(getString(R.string.bookmark_removed))
            } else {
                MaterialAlertDialogBuilder(this@BrowserActivity)
                    .setTitle(R.string.add_bookmark)
                    .setItems(arrayOf(getString(R.string.bookmark_global), getString(R.string.bookmark_session))) { _, which ->
                        lifecycleScope.launch {
                            core.persistNow {
                                core.repo.bookmarks.insert(BookmarkEntity(sessionId = if (which == 1) sid else null, url = tab.url,
                                    title = tab.displayTitle(this@BrowserActivity), createdAt = System.currentTimeMillis()))
                            }
                            snack(getString(R.string.bookmark_added))
                        }
                    }.show()
            }
        }
    }

    private fun confirmClearSessionData() {
        val session = core.sessions.active.value ?: return
        val labels = arrayOf(getString(R.string.clear_cookies), getString(R.string.clear_storage), getString(R.string.clear_cache), getString(R.string.clear_history))
        val checked = booleanArrayOf(true, true, true, false)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_session_title, session.name))
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(R.string.clear) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.reset_session_confirm)
                    .setPositiveButton(R.string.clear) { _, _ ->
                        lifecycleScope.launch {
                            core.sessions.clearData(session.id, checked[0], checked[1], checked[2], checked[3])
                            currentTab?.let { showTab(it) }
                            snack(getString(R.string.session_data_cleared))
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ================================================================== ExtensionHost

    override fun onExtensionInstallPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, dataCollection: List<String>, onDecision: (Boolean) -> Unit) {
        var decided = false
        val decide: (Boolean) -> Unit = { if (!decided) { decided = true; onDecision(it) } }
        val perms = permissions.ifEmpty { listOf(getString(R.string.ext_permissions_none)) }.joinToString("\n") { "• $it" }
        val hosts = if (origins.isNotEmpty()) "\n\n" + getString(R.string.ext_host_permissions, origins.joinToString("\n") { "• $it" }) else ""
        val data = if (dataCollection.isNotEmpty()) "\n\nData collection:\n" + dataCollection.joinToString("\n") { "• $it" } else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ext_install_prompt_title, ext.metaData.name ?: ext.id))
            .setMessage(getString(R.string.ext_install_prompt_msg, ext.metaData.version ?: "?", perms) + hosts + data)
            .setPositiveButton(R.string.import_action) { _, _ -> decide(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> decide(false) }
            .setOnDismissListener { decide(false) }
            .show()
    }

    override fun onExtensionOptionalPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, onDecision: (Boolean) -> Unit) {
        var decided = false
        val decide: (Boolean) -> Unit = { if (!decided) { decided = true; onDecision(it) } }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ext_optional_prompt_title, ext.metaData.name ?: ext.id))
            .setMessage((permissions + origins).joinToString("\n") { "• $it" })
            .setPositiveButton(R.string.allow) { _, _ -> decide(true) }
            .setNegativeButton(R.string.block) { _, _ -> decide(false) }
            .setOnDismissListener { decide(false) }
            .show()
    }

    /** browserAction popup: rendered in an app-owned bottom sheet holding its own GeckoView. */
    override fun showExtensionPopup(ext: WebExtension, popupSession: GeckoSession) {
        extensionPopup?.dismiss()
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_extension_popup, null)
        view.findViewById<TextView>(R.id.popupTitle).text = ext.metaData.name ?: getString(R.string.ext_popup)
        val gv = GeckoView(this)
        view.findViewById<FrameLayout>(R.id.popupContainer).addView(gv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { sheet.dismiss() }
        }
        popupSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                // Links out of the popup open as normal tabs in the active session; the popup itself only shows extension pages.
                if (request.uri.startsWith("moz-extension://") || request.uri.startsWith("about:")) return GeckoResult.allow()
                sheet.dismiss(); openUrl(request.uri, newTab = true); return GeckoResult.deny()
            }
        }
        gv.setSession(popupSession)
        sheet.setContentView(view)
        sheet.setOnDismissListener {
            try { gv.releaseSession() } catch (_: Throwable) {}
            try { popupSession.close() } catch (_: Throwable) {}
            if (extensionPopup === sheet) extensionPopup = null
        }
        extensionPopup = sheet
        sheet.show()
    }

    override fun onExtensionsChanged() {
        if (drawerLayout.isDrawerOpen(menuDrawerView)) renderMenu()
    }

    override fun onExtensionNewTab(ext: WebExtension, url: String?, active: Boolean): Tab? {
        val sid = core.sessions.activeId ?: return null
        val tab = core.tabs.createExtensionTab(sid, url) ?: return null   // shown as soon as Gecko starts loading it (onPageStart)
        updateTabCount()
        return tab
    }

    override fun onExtensionOpenOptions(ext: WebExtension, url: String) = openUrl(url, newTab = true)

    // ================================================================== BrowserHost

    override fun onTabOpenedByPage(tab: Tab) = showTab(tab)

    override fun onPageRequestedClose(tab: Tab) {
        if (tab.openerTabId != null || core.tabs.get(tab.id) != null) closeTab(tab)
    }

    override fun onSessionClosing(tab: Tab) {
        val gv = geckoView ?: return
        val gs = tab.geckoSession ?: return
        if (gv.session === gs) {
            try { gv.releaseSession() } catch (t: Throwable) { AppLog.w(TAG, "releaseSession failed", t) }
            if (tab.id == currentTabId) gv.isVisible = false
        }
    }

    override fun onContentProcessGone(tab: Tab, crashed: Boolean) {
        if (tab.id != currentTabId) return
        if (crashed) {
            updateErrorPage(tab)
            updateToolbar(tab)
        } else {
            showTab(tab)
        }
    }

    override fun onFullScreenChanged(tab: Tab, fullScreen: Boolean) {
        if (tab.id != currentTabId) return
        inFullScreen = fullScreen
        topBar.isVisible = !fullScreen && !toolbarHidden
        updateIsolationBanner()
        progressBar.isVisible = !fullScreen && tab.isLoading && tab.progress < 100
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- file upload -------------------------------------------------------------------------

    override fun pickFiles(mimeTypes: Array<String>?, multiple: Boolean, capture: Boolean, onResult: (Array<Uri>?) -> Unit) {
        fileResultCallback?.invoke(null)
        fileResultCallback = onResult
        cameraOutputUri = null
        val types = mimeTypes?.filter { it.isNotBlank() }.orEmpty()
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types[0] else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        val acceptsImages = types.isEmpty() || types.any { it == "*/*" || it.startsWith("image") }
        val extras = mutableListOf<Intent>()
        if (acceptsImages && hasPermission(Manifest.permission.CAMERA)) createCameraIntent()?.let { extras += it }
        val chooser = Intent.createChooser(contentIntent, getString(R.string.choose_file))
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            fileResultCallback = null
            snack(getString(R.string.no_app_found))
            onResult(null)
        }
    }

    private fun createCameraIntent(): Intent? {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (intent.resolveActivity(packageManager) == null) return null
        cameraOutputUri = uri
        return intent
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val cb = fileResultCallback ?: return
        fileResultCallback = null
        if (resultCode != RESULT_OK) { cb(null); cameraOutputUri = null; return }
        val clip: ClipData? = data?.clipData
        val results: Array<Uri>? = when {
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraOutputUri != null -> arrayOf(cameraOutputUri!!)
            else -> null
        }
        cameraOutputUri = null
        cb(results)
    }

    // ---- permissions --------------------------------------------------------------------------

    /** Gecko needs Android runtime permissions (CAMERA / RECORD_AUDIO / location) before it can ask the page-level question. */
    override fun onAndroidPermissionsRequest(tab: Tab, permissions: Array<String>, callback: GeckoSession.PermissionDelegate.Callback) {
        if (permissions.isEmpty()) { callback.grant(); return }
        requestAndroidPermissions(permissions.toList()) { grants ->
            val ok = permissions.all { grants[it] == true || hasPermission(it) }
            if (ok) callback.grant() else { callback.reject(); snack(getString(R.string.perm_denied)) }
        }
    }

    // Site permission model (v2.0.1, unchanged in v2.0.2 apart from the media revocation in SitePermissionStore.set)
    //  * SitePermissionStore (Room, per browser session + origin) holds the user's Allow/Block answers for
    //    camera, microphone and every content permission. getUserMedia answers live ONLY here - Gecko never
    //    persists media permissions.
    //  * Gecko's permission manager (per contextId) receives VALUE_ALLOW / VALUE_DENY and is edited with
    //    StorageController.setPermission from the Site permissions dialog, so both stores agree.
    //  * Android runtime permissions are requested only when a site is actually allowed to use the device.

    private enum class Decision { ALLOW, BLOCK, DISMISS }
    private class PendingPrompt(val show: () -> Unit, val cancel: () -> Unit)

    override fun onContentPermissionRequest(tab: Tab, perm: ContentPermission): GeckoResult<Int> {
        when (perm.permission) {
            SitePermissionType.GECKO_AUTOPLAY_INAUDIBLE_TYPE, SitePermissionType.GECKO_PERSISTENT_STORAGE_TYPE ->
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            SitePermissionType.GECKO_TRACKING_TYPE ->
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
        val type = SitePermissionType.fromGecko(perm.permission)
        val origin = SitePermissionStore.originOf(perm.uri) ?: SitePermissionStore.originOf(tab.url)
        if (type == null || origin == null) return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)

        when (sitePermissions.get(tab.sessionId, origin, type)) {
            PermissionValue.ALLOW -> return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            PermissionValue.BLOCK -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
        if (perm.value == ContentPermission.VALUE_ALLOW || perm.value == ContentPermission.VALUE_DENY) {
            sitePermissions.set(tab.sessionId, origin, type, perm.value)
            return GeckoResult.fromValue(perm.value)
        }
        if (type == SitePermissionType.AUTOPLAY_AUDIBLE) {
            return GeckoResult.fromValue(if (Prefs.mediaAutoplay) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_PROMPT)
        }
        if (tab.id != currentTabId || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
        }
        val result = GeckoResult<Int>()
        AppLog.i(TAG, "Content permission ${type.key} requested by $origin")
        promptPermission(originLabel(origin), permissionMessage(type)) { decision ->
            when (decision) {
                Decision.ALLOW -> {
                    sitePermissions.set(tab.sessionId, origin, type, PermissionValue.ALLOW); result.complete(ContentPermission.VALUE_ALLOW)
                    if (type == SitePermissionType.NOTIFICATIONS) ensureNotificationPermission()
                }
                Decision.BLOCK -> { sitePermissions.set(tab.sessionId, origin, type, PermissionValue.BLOCK); result.complete(ContentPermission.VALUE_DENY) }
                Decision.DISMISS -> result.complete(ContentPermission.VALUE_PROMPT)
            }
        }
        return result
    }

    /**
     * getUserMedia (WebRTC voice/video calls): remembered per site + session, granted after the Android permission check.
     * Device selection is independent of the UA mode (mobile / desktop): the first real microphone and the front camera
     * (or any camera) are handed to Gecko; SOURCE_OTHER audio sources are accepted too (virtual/USB inputs).
     */
    override fun onMediaPermissionRequest(
        tab: Tab,
        uri: String,
        video: Array<MediaSource>?,
        audio: Array<MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        val wantsVideo = !video.isNullOrEmpty()
        val wantsAudio = !audio.isNullOrEmpty()
        if (!wantsVideo && !wantsAudio) { callback.reject(); return }
        if (video?.all { it.source == MediaSource.SOURCE_SCREEN } == true && !wantsAudio) {
            callback.reject(); snack(getString(R.string.perm_screen_share_unsupported)); return
        }
        val camera = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA && it.name?.contains("front", true) == true }
            ?: video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA }
            ?: video?.firstOrNull { it.source != MediaSource.SOURCE_SCREEN }
        val microphone = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
            ?: audio?.firstOrNull { it.source != MediaSource.SOURCE_AUDIOCAPTURE }
            ?: audio?.firstOrNull()
        if (camera == null && microphone == null) { callback.reject(); return }

        val origin = SitePermissionStore.originOf(uri) ?: SitePermissionStore.originOf(tab.url)
        val camState = if (camera != null && origin != null) sitePermissions.get(tab.sessionId, origin, SitePermissionType.CAMERA) else PermissionValue.ASK
        val micState = if (microphone != null && origin != null) sitePermissions.get(tab.sessionId, origin, SitePermissionType.MICROPHONE) else PermissionValue.ASK
        val undecided = buildList {
            if (camera != null && camState == PermissionValue.ASK) add(SitePermissionType.CAMERA)
            if (microphone != null && micState == PermissionValue.ASK) add(SitePermissionType.MICROPHONE)
        }
        val finish: (Boolean, Boolean) -> Unit = { camAllowed, micAllowed ->
            grantMedia(tab, if (camAllowed) camera else null, if (micAllowed) microphone else null, callback)
        }
        if (undecided.isEmpty()) {
            AppLog.i(TAG, "Media permission for $origin answered from stored rules (camera=$camState mic=$micState)")
            finish(camState == PermissionValue.ALLOW, micState == PermissionValue.ALLOW)
            return
        }
        // Background tab of the SAME session (e.g. a call started, user switched tabs): answer from rules only, never prompt.
        if (tab.id != currentTabId || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { callback.reject(); return }
        val what = undecided.joinToString(", ") { getString(it.labelRes).lowercase() }
        val label = origin?.let { originLabel(it) } ?: UrlUtils.displayHost(uri).ifBlank { uri }
        AppLog.i(TAG, "Media permission request from $label: $what")
        promptPermission(label, getString(R.string.perm_request_msg, what) + "\n\n" + getString(R.string.perm_change_hint)) { decision ->
            if (decision == Decision.DISMISS) { callback.reject(); return@promptPermission }
            val allowed = decision == Decision.ALLOW
            if (origin != null) undecided.forEach { sitePermissions.set(tab.sessionId, origin, it, if (allowed) PermissionValue.ALLOW else PermissionValue.BLOCK) }
            finish(
                camera != null && (camState == PermissionValue.ALLOW || (camState == PermissionValue.ASK && allowed)),
                microphone != null && (micState == PermissionValue.ALLOW || (micState == PermissionValue.ASK && allowed)),
            )
        }
    }

    /** The site is allowed: make sure the APP holds the Android permission(s), then hand the devices to Gecko. */
    private fun grantMedia(tab: Tab, camera: MediaSource?, microphone: MediaSource?, callback: GeckoSession.PermissionDelegate.MediaCallback) {
        if (camera == null && microphone == null) { callback.reject(); return }
        val androidPerms = buildList {
            if (camera != null) add(Manifest.permission.CAMERA)
            if (microphone != null) add(Manifest.permission.RECORD_AUDIO)
        }
        requestAndroidPermissions(androidPerms) { grants ->
            val camOk = camera != null && (grants[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA))
            val micOk = microphone != null && (grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO))
            if (!camOk && !micOk) { callback.reject(); snack(getString(R.string.perm_android_denied)) }
            else {
                callback.grant(if (camOk) camera else null, if (micOk) microphone else null)
                tab.hasMediaCapture = true   // a page holding a camera/mic stream (call) is never hibernated automatically
            }
        }
    }

    private fun originLabel(origin: String): String = UrlUtils.displayHost(origin).ifBlank { origin }

    private fun permissionMessage(type: SitePermissionType): String {
        val body = when (type) {
            SitePermissionType.LOCATION -> getString(R.string.perm_location_msg)
            SitePermissionType.NOTIFICATIONS -> getString(R.string.perm_notifications_msg)
            SitePermissionType.DRM -> getString(R.string.perm_drm_msg)
            else -> getString(R.string.perm_generic_msg, getString(type.labelRes).lowercase())
        }
        return body + "\n\n" + getString(R.string.perm_change_hint)
    }

    private fun promptPermission(title: String, message: String, onDecision: (Decision) -> Unit) {
        var decided = false
        val decide: (Decision) -> Unit = { d -> if (!decided) { decided = true; onDecision(d) } }
        promptQueue.addLast(PendingPrompt(
            show = {
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.allow) { _, _ -> decide(Decision.ALLOW) }
                    .setNegativeButton(R.string.block) { _, _ -> decide(Decision.BLOCK) }
                    .create()
                dialog.setOnDismissListener {
                    decide(Decision.DISMISS)
                    if (activePermissionDialog === dialog) activePermissionDialog = null
                    promptShowing = false
                    showNextPrompt()
                }
                activePermissionDialog = dialog
                promptShowing = true
                dialog.show()
            },
            cancel = { decide(Decision.DISMISS) },
        ))
        showNextPrompt()
    }

    private fun showNextPrompt() {
        if (promptShowing || isFinishing || isDestroyed) return
        val next = promptQueue.removeFirstOrNull() ?: return
        next.show()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** A site may now notify: on Android 13+ the APP additionally needs POST_NOTIFICATIONS, otherwise nothing is ever displayed. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        requestAndroidPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) { grants ->
            if (grants[Manifest.permission.POST_NOTIFICATIONS] != true && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) snack(getString(R.string.perm_android_denied))
        }
    }

    private fun requestAndroidPermissions(perms: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { !hasPermission(it) }
        if (missing.isEmpty()) { onResult(perms.associateWith { true }); return }
        pendingPermissionAction?.invoke(emptyMap())
        pendingPermissionAction = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    // ---- navigation to other apps ---------------------------------------------------------------

    override fun onExternalScheme(tab: Tab, uri: Uri, hasGesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "file") { snack(getString(R.string.file_urls_blocked)); return true }
        if (!Prefs.openExternalApps) { snack(getString(R.string.external_apps_disabled)); return true }
        if (!hasGesture) { AppLog.i(TAG, "Blocked non-gesture external navigation ($scheme)"); return true }
        val intent = try {
            if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
        } catch (e: URISyntaxException) {
            return true
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.`package` == packageName) return true
        return try {
            startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && UrlUtils.isWebUrl(fallback)) tab.geckoSession?.load(GeckoSession.Loader().uri(fallback))
            else snack(getString(R.string.no_app_found))
            true
        }
    }

    // ---- downloads (app-owned manager) -------------------------------------------------------------

    override fun onDownloadRequested(tab: Tab, response: WebResponse) {
        val req = AppDownloadManager.Request(response)
        val emptyPopup = tab.openerTabId != null && (tab.url.isBlank() || tab.url == "about:blank" || tab.url == req.url)
        if (response.requestExternalApp && Prefs.openExternalApps && UrlUtils.isWebUrl(req.url)) {
            if (openExternal(req.url)) { core.downloads.discard(req); if (emptyPopup) closeTab(tab); return }
        }
        val sourcePage = tab.url.takeIf { UrlUtils.isWebUrl(it) && it != req.url }
        val start = {
            ensureStoragePermission { ok ->
                if (!ok) { core.downloads.discard(req); snack(getString(R.string.perm_storage_denied)); return@ensureStoragePermission }
                core.downloads.start(req, tab.sessionId, sourcePage)
                Snackbar.make(root, getString(R.string.dl_started, req.fileName), Snackbar.LENGTH_LONG)
                    .setAction(R.string.downloads) { openUrlLauncher.launch(Intent(this, DownloadsActivity::class.java)) }.show()
            }
        }
        if (Prefs.askBeforeDownload) {
            val size = if (req.contentLength > 0) Formatter.formatFileSize(this, req.contentLength) else getString(R.string.unknown_size)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_title)
                .setMessage(getString(R.string.download_message, req.fileName, size, UrlUtils.displayHost(req.url)) + "\n" + req.mimeType)
                .setPositiveButton(R.string.download) { _, _ -> start() }
                .setNeutralButton(R.string.open_external) { _, _ -> core.downloads.discard(req); openExternal(req.url) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> core.downloads.discard(req) }
                .setOnCancelListener { core.downloads.discard(req) }
                .show()
        } else start()
        if (emptyPopup) closeTab(tab)
    }

    /** Fetches [url] through Gecko's network stack (default cookie jar) and saves it - used for "download image". */
    private fun downloadUrl(url: String) {
        if (!UrlUtils.isWebUrl(url)) { snack(getString(R.string.download_failed)); return }
        try {
            GeckoWebExecutor(core.engine.runtime).fetch(WebRequest.Builder(url).build())
                .accept({ response ->
                    if (response == null) { snack(getString(R.string.download_failed)); return@accept }
                    onDownloadRequested(currentTab ?: return@accept, response)
                }, { AppLog.w(TAG, "fetch failed", it); snack(getString(R.string.download_failed)) })
        } catch (t: Throwable) {
            AppLog.w(TAG, "fetch failed", t); snack(getString(R.string.download_failed))
        }
    }

    private fun ensureStoragePermission(onResult: (Boolean) -> Unit) {
        if (!core.downloads.needsStoragePermission(this)) { onResult(true); return }
        requestAndroidPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { onResult(it[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) }
    }

    // ---- link / image context menu -----------------------------------------------------------------

    override fun onContextMenu(tab: Tab, element: GeckoSession.ContentDelegate.ContextElement) {
        val link = element.linkUri
        val src = element.srcUri
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
        val isMedia = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO || element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO
        val target = link ?: src ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            items += getString(R.string.open_in_new_tab) to { core.tabs.createTab(tab.sessionId, link, select = false, openerTabId = tab.id); updateTabCount(); snack(getString(R.string.tab_opened_background)) }
            items += getString(R.string.copy_link) to { copyToClipboard(link) }
            items += getString(R.string.share_link) to { shareUrl(link, element.title ?: "") }
        }
        if (src != null && (isImage || isMedia)) {
            items += getString(R.string.open_image_new_tab) to { core.tabs.createTab(tab.sessionId, src, select = false, openerTabId = tab.id); updateTabCount() }
            items += getString(R.string.download_image) to { downloadUrl(src) }
            if (link == null) items += getString(R.string.copy_link) to { copyToClipboard(src) }
        }
        items += getString(R.string.open_external) to { openExternal(target) }
        MaterialAlertDialogBuilder(this)
            .setTitle(element.title?.takeIf { it.isNotBlank() } ?: UrlUtils.displayHost(target).ifBlank { target })
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    // ================================================================== helpers

    /** Async screenshot of the displayed session for the tab list (GeckoView cannot be drawn to a Canvas). */
    private fun captureThumbnail(tab: Tab) {
        val gv = geckoView ?: return
        val gs = tab.geckoSession ?: return
        if (gv.session !== gs || gv.parent == null || !gv.isVisible || gv.width <= 0 || gv.height <= 0) return
        try {
            gv.capturePixels().accept({ bmp ->
                if (bmp == null) return@accept
                core.scope.launch(Dispatchers.Default) {
                    // Grid card size (~360 px wide) instead of a third of the screen: ~0.4 MB per thumbnail, and TabManager
                    // keeps only the most recent ones (LRU) so hundreds of tabs cannot exhaust memory.
                    val scale = (THUMBNAIL_WIDTH_PX / bmp.width.toFloat()).coerceAtMost(1f)
                    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    if (scaled !== bmp) bmp.recycle()
                    withContext(Dispatchers.Main) { if (core.tabs.get(tab.id) != null) core.tabs.storeThumbnail(tab, scaled) else scaled.recycle() }
                }
            }, { AppLog.d(TAG, "thumbnail failed: ${it?.message}") })
        } catch (t: Throwable) {
            AppLog.d(TAG, "thumbnail not available: ${t.message}")
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", text))
        snack(getString(R.string.copied))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    fun snack(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "BrowserActivity"
        private const val EXTRA_HANDLED = "app.multisession.browser.HANDLED"
        private const val THUMBNAIL_WIDTH_PX = 360f
    }
}
