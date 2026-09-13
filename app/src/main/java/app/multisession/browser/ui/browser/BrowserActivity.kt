package app.multisession.browser.ui.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
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
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
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
import app.multisession.browser.engine.BrowserHost
import app.multisession.browser.engine.DownloadHandler
import app.multisession.browser.engine.SessionFactory
import app.multisession.browser.tabs.PageError
import app.multisession.browser.tabs.Tab
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.ui.library.BookmarksActivity
import app.multisession.browser.ui.library.HistoryActivity
import app.multisession.browser.ui.library.ProjectsActivity
import app.multisession.browser.ui.library.SimpleListActivity
import app.multisession.browser.ui.sessions.SessionEditDialog
import app.multisession.browser.ui.sessions.SessionsSheet
import app.multisession.browser.ui.settings.SettingsActivity
import app.multisession.browser.ui.tabs.TabsSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
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
 * Focus rules (v1.1.2 fix, preserved): the invisible focusHolder is the first focusable view, the
 * URL bar only gains focus from an explicit user tap, and returning to the app never rebuilds or
 * re-attaches the displayed content view - so a focused web input (e.g. OTP field) keeps focus.
 */
class BrowserActivity : AppCompatActivity(), BrowserHost, TabManager.Listener, StartPageController.Callbacks {

    private val core get() = BrowserApp.core()
    override val activity: AppCompatActivity get() = this

    // ---- views
    private lateinit var root: View
    private lateinit var browserRoot: View
    private lateinit var isolationBanner: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var sessionChip: View
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

    /** Created lazily and attached to [webContainer] only together with an OPEN session (see attachSession). */
    private var geckoView: GeckoView? = null

    private var startPage: StartPageController? = null
    private var currentTabId: String? = null
    private var uiReady = false
    private var inFullScreen = false
    private lateinit var suggestionPopup: SuggestionPopup

    private var fileResultCallback: ((Array<Uri>?) -> Unit)? = null
    private var cameraOutputUri: Uri? = null
    private var pendingPermissionAction: ((Map<String, Boolean>) -> Unit)? = null
    private var activePermissionDialog: AlertDialog? = null

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

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        bindViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })

        lifecycleScope.launch {
            core.awaitReady()
            uiReady = true
            core.tabs.addListener(this@BrowserActivity)
            core.tabs.host = this@BrowserActivity
            updateIsolationBanner()
            showActiveSessionTab()
            handleIntent(intent)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                core.sessions.active.collect { updateSessionChip(it) }
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
        val data = intent.data ?: return
        if (intent.action == Intent.ACTION_VIEW && UrlUtils.isWebUrl(data.toString())) {
            openUrl(data.toString(), newTab = true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!uiReady) return
        core.tabs.host = this
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
    }

    override fun onDestroy() {
        if (uiReady) {
            core.tabs.removeListener(this)
            core.tabs.detachFromActivity()
        }
        // Give the session back so a recreated Activity can attach it to its own GeckoView.
        try { geckoView?.releaseSession() } catch (t: Throwable) { AppLog.w(TAG, "releaseSession failed", t) }
        activePermissionDialog?.dismiss()
        if (::suggestionPopup.isInitialized) suggestionPopup.dismiss()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        core.engine.onConfigurationChanged()
    }

    // ================================================================== view binding

    private fun bindViews() {
        root = findViewById(R.id.root)
        browserRoot = findViewById(R.id.browserRoot)
        isolationBanner = findViewById(R.id.isolationBanner)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        sessionChip = findViewById(R.id.sessionChip)
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

        sessionChip.setOnClickListener { SessionsSheet().show(supportFragmentManager, "sessions") }
        isolationBanner.setOnClickListener { showIsolationInfo() }

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
                val session = core.sessions.get(currentTab?.sessionId ?: return) ?: return
                suggestionPopup.showSuggestions(input, session.id)
            }
        })
        reloadStopButton.setOnClickListener {
            val tab = currentTab ?: return@setOnClickListener
            if (tab.isLoading) tab.geckoSession?.stop() else reload(tab)
        }
        backButton.setOnClickListener { goBack() }
        forwardButton.setOnClickListener { currentTab?.let { t -> if (t.canGoForward) t.geckoSession?.goForward() } }
        findViewById<View>(R.id.newTabButton).setOnClickListener { newTab() }
        findViewById<View>(R.id.tabsButton).setOnClickListener { TabsSheet().show(supportFragmentManager, "tabs") }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }

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
            // Popup sessions are opened by Gecko itself right after onNewSession; they are shown from onPageStart.
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
        val previous = currentTab
        if (previous != null && previous.id != tab.id) {
            captureThumbnail(previous)
            if (inFullScreen) {
                // Leaving a fullscreen video by switching tabs: restore the chrome now (previous is still current).
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

    fun newTab(url: String = homeUrl()) {
        val session = core.sessions.active.value ?: return
        val tab = core.tabs.createTab(session.id, url, select = true)
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

    private fun navigate(input: String) {
        val tab = currentTab ?: return
        exitSearchMode()
        loadInTab(tab, UrlUtils.resolveInput(input))
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
            val gs = core.tabs.ensureSession(tab, loadContent = false)
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

    override fun openSessions() = SessionsSheet().show(supportFragmentManager, "sessions")

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

    private fun handleBack() {
        val tab = currentTab
        when {
            inFullScreen -> {
                val gs = tab?.geckoSession
                if (gs != null) gs.exitFullScreen()          // Gecko answers with onFullScreen(false)
                else if (tab != null) onFullScreenChanged(tab, false)
                else inFullScreen = false
            }
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
        sessionChip.contentDescription = getString(R.string.cd_session_chip, session.name)
        updateTabCount()
    }

    private fun updateToolbar(tab: Tab) {
        if (!urlInput.hasFocus()) urlInput.setText(if (tab.isStartPage) "" else tab.url)
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
        // GeckoView isolates every session via its contextId on every device: the banner only exists
        // for the (now impossible) shared-storage case.
        isolationBanner.isVisible = !core.isolation.isIsolated
    }

    private fun showIsolationInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.isolation_title)
            .setMessage(core.isolation.describe(this))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    // ================================================================== menu

    private fun showMenu(anchor: View) {
        val tab = currentTab
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_browser, popup.menu)
        popup.menu.findItem(R.id.action_desktop).isChecked = tab?.desktopMode == true
        popup.menu.findItem(R.id.action_reopen_closed).isVisible = core.sessions.activeId?.let { core.tabs.hasRecentlyClosed(it) } == true
        val hasPage = tab != null && !tab.isStartPage
        listOf(R.id.action_share, R.id.action_open_external, R.id.action_bookmark, R.id.action_desktop).forEach {
            popup.menu.findItem(it).isEnabled = hasPage
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> newTab()
                R.id.action_new_session -> SessionEditDialog.show(this, null) { name, color, isPrivate ->
                    lifecycleScope.launch { core.sessions.create(name, color, isPrivate); currentTabId = null; showActiveSessionTab() }
                }
                R.id.action_reopen_closed -> core.sessions.activeId?.let { sid -> core.tabs.reopenClosedTab(sid)?.let { showTab(it) } }
                R.id.action_desktop -> tab?.let { toggleDesktop(it) }
                R.id.action_share -> tab?.let { shareUrl(it.url, it.title) }
                R.id.action_open_external -> tab?.let { openExternal(it.url) }
                R.id.action_bookmark -> tab?.let { toggleBookmark(it) }
                R.id.action_bookmarks -> openUrlLauncher.launch(Intent(this, BookmarksActivity::class.java))
                R.id.action_history -> openUrlLauncher.launch(Intent(this, HistoryActivity::class.java))
                R.id.action_downloads -> openDownloads()
                R.id.action_projects -> openUrlLauncher.launch(Intent(this, ProjectsActivity::class.java))
                R.id.action_clear_site_data -> confirmClearSessionData()
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktop(tab: Tab) {
        tab.desktopMode = !tab.desktopMode
        core.sessions.get(tab.sessionId)?.let { SessionFactory.applyTabSettings(tab, it) }
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

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            snack(getString(R.string.no_app_found))
        }
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

    // ================================================================== BrowserHost

    override fun onTabOpenedByPage(tab: Tab) = showTab(tab)

    override fun onPageRequestedClose(tab: Tab) {
        if (tab.openerTabId != null) closeTab(tab)
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
            // Killed for memory while (probably) not visible: transparently re-open from saved state.
            showTab(tab)
        }
    }

    override fun onFullScreenChanged(tab: Tab, fullScreen: Boolean) {
        if (tab.id != currentTabId) return
        inFullScreen = fullScreen
        topBar.isVisible = !fullScreen
        bottomBar.isVisible = !fullScreen
        isolationBanner.isVisible = !fullScreen && !core.isolation.isIsolated
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

    /** Site permissions. GeckoView 155 removed the named PERMISSION_* int constants.
     *  We use VALUE_PROMPT for all types so Gecko shows its own default dialog. */
    override fun onContentPermissionRequest(tab: Tab, perm: ContentPermission): GeckoResult<Int> {
        return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
    }

    private fun askContentPermission(host: String, message: String, onDecision: (Boolean) -> GeckoResult<Int>): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        var decided = false
        fun decide(allowed: Boolean) {
            if (decided) return
            decided = true
            onDecision(allowed).accept { v -> result.complete(v ?: ContentPermission.VALUE_DENY) }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(host)
            .setMessage(message)
            .setPositiveButton(R.string.allow) { _, _ -> decide(true) }
            .setNegativeButton(R.string.block) { _, _ -> decide(false) }
            .setOnCancelListener { decide(false) }
            .create()
        dialog.setOnDismissListener { decide(false); if (activePermissionDialog === dialog) activePermissionDialog = null }
        activePermissionDialog?.dismiss()
        activePermissionDialog = dialog
        dialog.show()
        return result
    }

    /** getUserMedia (WebRTC voice/video, e.g. Discord calls): choose camera + microphone after user consent. */
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
        val microphone = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE } ?: audio?.firstOrNull()
        val what = listOfNotNull(
            if (camera != null) getString(R.string.perm_camera) else null,
            if (microphone != null) getString(R.string.perm_microphone) else null,
        ).joinToString(", ")
        if (what.isEmpty()) { callback.reject(); return }
        val origin = UrlUtils.displayHost(uri).ifBlank { uri }
        AppLog.i(TAG, "Media permission request from $origin: $what")
        var decided = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(origin)
            .setMessage(getString(R.string.perm_request_msg, what))
            .setPositiveButton(R.string.allow) { _, _ ->
                decided = true
                val androidPerms = buildList {
                    if (camera != null) add(Manifest.permission.CAMERA)
                    if (microphone != null) add(Manifest.permission.RECORD_AUDIO)
                }
                requestAndroidPermissions(androidPerms) { grants ->
                    val camOk = camera != null && (grants[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA))
                    val micOk = microphone != null && (grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO))
                    if (!camOk && !micOk) { callback.reject(); snack(getString(R.string.perm_denied)) }
                    else callback.grant(if (camOk) camera else null, if (micOk) microphone else null)
                }
            }
            .setNegativeButton(R.string.block) { _, _ -> decided = true; callback.reject() }
            .create()
        dialog.setOnDismissListener { if (!decided) { decided = true; callback.reject() }; if (activePermissionDialog === dialog) activePermissionDialog = null }
        activePermissionDialog?.dismiss()
        activePermissionDialog = dialog
        dialog.show()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

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
        // Safe external intent handling: only BROWSABLE activities, never our own or explicit components.
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

    // ---- downloads -------------------------------------------------------------------------------

    override fun onDownloadRequested(tab: Tab, response: WebResponse) {
        val req = DownloadHandler.Request(response)
        // A popup tab that only triggered a download is useless afterwards: close it.
        val emptyPopup = tab.openerTabId != null && (tab.url.isBlank() || tab.url == "about:blank" || tab.url == req.url)
        if (response.requestExternalApp && Prefs.openExternalApps && UrlUtils.isWebUrl(req.url)) {
            // Gecko asks for an external app (e.g. a scheme/type the user prefers to open elsewhere).
            if (openExternal(req.url)) { core.downloads.cancel(req); if (emptyPopup) closeTab(tab); return }
        }
        val start = {
            ensureStoragePermission { ok ->
                if (!ok) { core.downloads.cancel(req); snack(getString(R.string.perm_storage_denied)); return@ensureStoragePermission }
                snack(getString(R.string.downloading, req.fileName))
                core.downloads.start(req) { result ->
                    result.onSuccess { snack(getString(R.string.download_complete, it)) }
                        .onFailure { snack(getString(R.string.download_failed)) }
                }
            }
        }
        if (Prefs.askBeforeDownload) {
            val size = if (req.contentLength > 0) Formatter.formatFileSize(this, req.contentLength) else getString(R.string.unknown_size)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_title)
                .setMessage(getString(R.string.download_message, req.fileName, size, UrlUtils.displayHost(req.url)))
                .setPositiveButton(R.string.download) { _, _ -> start() }
                .setNeutralButton(R.string.open_external) { _, _ -> core.downloads.cancel(req); openExternal(req.url) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> core.downloads.cancel(req) }
                .setOnCancelListener { core.downloads.cancel(req) }
                .show()
        } else start()
        if (emptyPopup) closeTab(tab)
    }

    /** Fetches [url] through Gecko's network stack (no session cookies) and saves it - used for "download image". */
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

    /** Async screenshot of the displayed session for the tab grid (GeckoView cannot be drawn to a Canvas). */
    private fun captureThumbnail(tab: Tab) {
        val gv = geckoView ?: return
        val gs = tab.geckoSession ?: return
        if (gv.session !== gs || gv.parent == null || !gv.isVisible || gv.width <= 0 || gv.height <= 0) return
        try {
            gv.capturePixels().accept({ bmp ->
                if (bmp == null) return@accept
                val scale = 0.33f
                tab.thumbnail = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                if (tab.thumbnail !== bmp) bmp.recycle()
            }, { AppLog.d(TAG, "thumbnail failed: ${it.message}") })
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
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT).setAnchorView(findViewById<View>(R.id.bottomBar)).show()
    }

    companion object {
        private const val TAG = "BrowserActivity"
        private const val EXTRA_HANDLED = "app.multisession.browser.HANDLED"
    }
}
