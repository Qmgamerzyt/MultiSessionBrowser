package app.multisession.browser.ui.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import app.multisession.browser.webview.BrowserHost
import app.multisession.browser.webview.DownloadHandler
import app.multisession.browser.webview.WebViewFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import java.net.URISyntaxException

class BrowserActivity : AppCompatActivity(), BrowserHost, TabManager.Listener, StartPageController.Callbacks {

    private val core get() = BrowserApp.core()
    override val activity: AppCompatActivity get() = this

    // ---- views
    private lateinit var root: View
    private lateinit var browserRoot: View
    private lateinit var isolationBanner: TextView
    private lateinit var sessionChip: View
    private lateinit var sessionDot: View
    private lateinit var sessionName: TextView
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

    private var startPage: StartPageController? = null
    private var currentTabId: String? = null
    private var uiReady = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var pendingPermissionAction: ((Map<String, Boolean>) -> Unit)? = null
    private var activePermissionDialog: Pair<PermissionRequest, AlertDialog>? = null

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
        currentTab?.webView?.resumeTimers()
        showActiveSessionTab()
    }

    override fun onStop() {
        super.onStop()
        if (!uiReady) return
        currentTab?.let { tab ->
            captureThumbnail(tab)
            tab.webView?.onPause()
            tab.webView?.pauseTimers()
        }
        core.tabs.persistAll()
        if (core.tabs.host === this) core.tabs.host = null
    }

    override fun onDestroy() {
        if (uiReady) {
            core.tabs.removeListener(this)
            core.tabs.detachFromActivity()
        }
        activePermissionDialog?.second?.dismiss()
        super.onDestroy()
    }

    // ================================================================== view binding

    private fun bindViews() {
        root = findViewById(R.id.root)
        browserRoot = findViewById(R.id.browserRoot)
        isolationBanner = findViewById(R.id.isolationBanner)
        sessionChip = findViewById(R.id.sessionChip)
        sessionDot = findViewById(R.id.sessionDot)
        sessionName = findViewById(R.id.sessionName)
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
                currentTab?.let { if (!it.isStartPage) urlInput.setText(it.url) }
                urlInput.post { urlInput.selectAll() }
            } else {
                currentTab?.let { updateToolbar(it) }
            }
        }
        reloadStopButton.setOnClickListener {
            val tab = currentTab ?: return@setOnClickListener
            if (tab.isLoading) tab.webView?.stopLoading() else reload(tab)
        }
        backButton.setOnClickListener { goBack() }
        forwardButton.setOnClickListener { currentTab?.webView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.newTabButton).setOnClickListener { newTab() }
        findViewById<View>(R.id.tabsButton).setOnClickListener { TabsSheet().show(supportFragmentManager, "tabs") }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }

        findViewById<View>(R.id.errorRetry).setOnClickListener { currentTab?.let { reload(it) } }
        findViewById<View>(R.id.errorBack).setOnClickListener {
            val tab = currentTab ?: return@setOnClickListener
            if (tab.webView?.canGoBack() == true) { tab.error = null; tab.webView?.goBack() } else loadInTab(tab, UrlUtils.START_PAGE)
        }
        findViewById<View>(R.id.errorExternal).setOnClickListener { currentTab?.error?.url?.let { openExternal(it) } }
    }

    // ================================================================== session / tab display

    private fun showActiveSessionTab() {
        val session = core.sessions.active.value ?: return
        val tab = core.tabs.activeTab(session.id) ?: core.tabs.createTab(session.id, homeUrl(), select = true)
        showTab(tab)
    }

    private fun homeUrl(): String = core.localContent.bundledAppUrl() ?: Prefs.homepage

    fun showTab(tab: Tab) {
        val previous = currentTab
        if (previous != null && previous.id != tab.id) {
            captureThumbnail(previous)
            previous.webView?.onPause()
        }
        currentTabId = tab.id
        core.tabs.selectTab(tab)
        urlInput.clearFocus()

        webContainer.removeAllViews()
        if (tab.isStartPage && tab.webView == null) {
            showStartPage(tab)
        } else {
            val wv = core.tabs.ensureWebView(tab, this)
            (wv.parent as? ViewGroup)?.removeView(wv)
            webContainer.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            wv.onResume()
            wv.resumeTimers()
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
        (controller.view.parent as? ViewGroup)?.removeView(controller.view)
        webContainer.addView(controller.view)
        lifecycleScope.launch { controller.refresh(tab.sessionId) }
    }

    override fun switchSession(sessionId: String) {
        lifecycleScope.launch {
            currentTab?.let { captureThumbnail(it); it.webView?.onPause() }
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
        hideKeyboard()
        urlInput.clearFocus()
        loadInTab(tab, UrlUtils.resolveInput(input))
    }

    private fun loadInTab(tab: Tab, url: String) {
        tab.error = null
        if (UrlUtils.isStartPage(url)) {
            core.tabs.hibernate(tab)
            tab.savedState = null
            tab.url = UrlUtils.START_PAGE
            tab.title = ""
            core.tabs.persistTab(tab)
        } else {
            val wv = core.tabs.ensureWebView(tab, this, loadContent = false)
            tab.url = url
            wv.loadUrl(url)
        }
        if (tab.id == currentTabId) showTab(tab) else core.tabs.persistTab(tab)
    }

    override fun openUrl(url: String, newTab: Boolean) {
        val tab = currentTab
        if (newTab || tab == null) newTab(url) else loadInTab(tab, url)
    }

    override fun focusUrlBar() {
        urlInput.requestFocus()
        urlInput.post { getSystemService(InputMethodManager::class.java).showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun openSessions() = SessionsSheet().show(supportFragmentManager, "sessions")

    private fun reload(tab: Tab) {
        tab.error = null
        if (tab.isStartPage) showTab(tab) else {
            val wv = tab.webView
            if (wv == null) loadInTab(tab, tab.url) else { wv.reload(); updateErrorPage(tab) }
        }
    }

    private fun goBack() {
        val tab = currentTab ?: return
        val wv = tab.webView
        if (wv != null && wv.canGoBack()) { tab.error = null; wv.goBack(); updateErrorPage(tab) }
    }

    private fun handleBack() {
        val tab = currentTab
        when {
            customView != null -> onHideCustomView()
            urlInput.hasFocus() -> { urlInput.clearFocus(); hideKeyboard() }
            tab?.webView?.canGoBack() == true -> goBack()
            tab?.openerTabId != null -> closeTab(tab)
            tab != null && !tab.isStartPage && tab.webView == null -> loadInTab(tab, UrlUtils.START_PAGE)
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
                UrlUtils.isSecure(tab.url) || UrlUtils.isLocalContent(tab.url) -> R.drawable.ic_lock
                else -> R.drawable.ic_info
            }
        )
        reloadStopButton.setImageResource(if (tab.isLoading) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadStopButton.contentDescription = getString(if (tab.isLoading) R.string.stop else R.string.reload)
        progressBar.isVisible = tab.isLoading && tab.progress < 100
        progressBar.progress = tab.progress
        val wv = tab.webView
        backButton.isEnabled = wv?.canGoBack() == true
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.isEnabled = wv?.canGoForward() == true
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
    }

    private fun updateTabCount() {
        val sid = core.sessions.activeId ?: return
        val count = core.tabs.countFor(sid)
        tabCountView.text = if (count > 99) "99+" else count.toString()
    }

    private fun updateIsolationBanner() {
        isolationBanner.isVisible = !core.isolation.isIsolated
        if (!core.isolation.isIsolated) isolationBanner.text = getString(R.string.isolation_banner)
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
        return when (err.code) {
            TabManager.ERROR_RENDERER_GONE -> getString(R.string.err_crash_title) to getString(R.string.err_crash_msg)
            WebViewClient.ERROR_HOST_LOOKUP -> if (offline) getString(R.string.err_offline_title) to getString(R.string.err_offline_msg)
            else getString(R.string.err_host_title) to getString(R.string.err_host_msg)
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO ->
                if (offline) getString(R.string.err_offline_title) to getString(R.string.err_offline_msg)
                else getString(R.string.err_unavailable_title) to getString(R.string.err_unavailable_msg)
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> getString(R.string.err_ssl_title) to getString(R.string.err_ssl_msg)
            WebViewClient.ERROR_UNSUPPORTED_SCHEME, WebViewClient.ERROR_BAD_URL -> getString(R.string.err_unsupported_title) to getString(R.string.err_unsupported_msg)
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
        // A start-page tab that gained a WebView (e.g. popup target) must show it.
        if (tab.webView != null && tab.webView?.parent == null && startPage?.view?.parent != null) showTab(tab)
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
        tab.webView?.let {
            WebViewFactory.applyUserAgent(it, tab.desktopMode)
            WebViewFactory.applyDesktopScale(it, tab.desktopMode)
            WebViewFactory.applyDesktopViewport(it, tab.desktopMode)
            it.reload()
        }
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

    private fun openExternal(url: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.open_external)))
        } catch (e: ActivityNotFoundException) {
            snack(getString(R.string.no_app_found))
        }
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
                    .setMessage(if (core.isolation.isIsolated) R.string.reset_session_confirm else R.string.reset_session_confirm_shared)
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

    override fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        cameraOutputUri = null
        val contentIntent = params.createIntent()
        val acceptsImages = params.acceptTypes.any { it.isBlank() || it == "*/*" || it.startsWith("image") }
        val extras = mutableListOf<Intent>()
        if (acceptsImages && hasPermission(Manifest.permission.CAMERA)) createCameraIntent()?.let { extras += it }
        val chooser = Intent.createChooser(contentIntent, params.title ?: getString(R.string.choose_file))
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        return try {
            fileChooserLauncher.launch(chooser); true
        } catch (e: ActivityNotFoundException) {
            fileChooserCallback = null
            snack(getString(R.string.no_app_found)); false
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
        val cb = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode != RESULT_OK) { cb.onReceiveValue(null); cameraOutputUri = null; return }
        val clip: ClipData? = data?.clipData
        val results: Array<Uri>? = when {
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraOutputUri != null -> arrayOf(cameraOutputUri!!)
            else -> null
        }
        cameraOutputUri = null
        cb.onReceiveValue(results)
    }

    override fun onPermissionRequest(tab: Tab, request: PermissionRequest) {
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (wanted.isEmpty()) { request.deny(); return }
        val origin = request.origin.host ?: request.origin.toString()
        val what = wanted.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> getString(R.string.perm_camera)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> getString(R.string.perm_microphone)
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> getString(R.string.perm_drm)
                else -> null
            }
        }.joinToString(", ")
        AppLog.i(TAG, "Permission request from $origin: $what")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(origin)
            .setMessage(getString(R.string.perm_request_msg, what))
            .setPositiveButton(R.string.allow) { _, _ ->
                val androidPerms = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(Manifest.permission.RECORD_AUDIO)
                }
                requestAndroidPermissions(androidPerms) { grants ->
                    val granted = wanted.filter { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> grants[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
                            else -> true
                        }
                    }
                    if (granted.isEmpty()) { request.deny(); snack(getString(R.string.perm_denied)) } else request.grant(granted.toTypedArray())
                }
            }
            .setNegativeButton(R.string.block) { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .create()
        dialog.setOnDismissListener { if (activePermissionDialog?.first === request) activePermissionDialog = null }
        activePermissionDialog = request to dialog
        dialog.show()
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        activePermissionDialog?.let { (req, dlg) -> if (req === request) dlg.dismiss() }
    }

    override fun onGeolocationPrompt(tab: Tab, origin: String, callback: GeolocationPermissions.Callback) {
        MaterialAlertDialogBuilder(this)
            .setTitle(UrlUtils.displayHost(origin).ifBlank { origin })
            .setMessage(R.string.perm_location_msg)
            .setPositiveButton(R.string.allow) { _, _ ->
                requestAndroidPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { grants ->
                    val ok = grants.values.any { it } || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    callback.invoke(origin, ok, ok) // remembered per origin inside the session's profile
                }
            }
            .setNegativeButton(R.string.block) { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestAndroidPermissions(perms: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { !hasPermission(it) }
        if (missing.isEmpty()) { onResult(perms.associateWith { true }); return }
        pendingPermissionAction = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.isVisible = true
        browserRoot.isVisible = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideCustomView() {
        val v = customView ?: return
        fullscreenContainer.removeView(v)
        fullscreenContainer.isVisible = false
        browserRoot.isVisible = true
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onSslError(tab: Tab, handler: SslErrorHandler, error: SslError) {
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> R.string.ssl_expired
            SslError.SSL_IDMISMATCH -> R.string.ssl_mismatch
            SslError.SSL_UNTRUSTED -> R.string.ssl_untrusted
            SslError.SSL_NOTYETVALID -> R.string.ssl_notyet
            else -> R.string.ssl_generic
        }
        val host = UrlUtils.displayHost(error.url)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssl_title)
            .setMessage(getString(R.string.ssl_message, host, getString(reason)))
            .setPositiveButton(R.string.go_back) { _, _ ->
                handler.cancel()
                tab.error = PageError(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, getString(reason), error.url ?: tab.url)
                tab.isLoading = false
                core.tabs.notifyTabUpdated(tab)
            }
            .setNegativeButton(R.string.ssl_proceed) { _, _ -> handler.proceed() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    override fun onHttpAuthRequest(tab: Tab, handler: HttpAuthHandler, host: String, realm: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_http_auth, null)
        val user = view.findViewById<EditText>(R.id.authUser)
        val pass = view.findViewById<EditText>(R.id.authPassword)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.auth_title, host))
            .setMessage(realm)
            .setView(view)
            .setPositiveButton(R.string.sign_in) { _, _ -> handler.proceed(user.text.toString(), pass.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    override fun onExternalScheme(tab: Tab, uri: Uri, hasGesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        when (scheme) {
            "about", "data", "blob" -> return false          // let WebView handle
            "javascript" -> return true                      // never execute injected javascript: URLs
            "file" -> { snack(getString(R.string.file_urls_blocked)); return true }
        }
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
            if (fallback != null && UrlUtils.isWebUrl(fallback)) tab.webView?.loadUrl(fallback) else snack(getString(R.string.no_app_found))
            true
        }
    }

    override fun onDownloadRequested(tab: Tab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        val session = core.sessions.get(tab.sessionId) ?: return
        val req = DownloadHandler.Request(url, userAgent, contentDisposition, mimeType, contentLength)
        // A popup tab that only triggered a download is useless afterwards: close it.
        val emptyPopup = tab.openerTabId != null && tab.webView?.url.let { it.isNullOrBlank() || it == "about:blank" }
        val start = {
            ensureStoragePermission { ok ->
                if (!ok) { snack(getString(R.string.perm_storage_denied)); return@ensureStoragePermission }
                core.downloads.enqueue(session, req)
                    .onSuccess { snack(getString(R.string.downloading, it)) }
                    .onFailure { e ->
                        if (e is DownloadHandler.UnsupportedDownload && req.isBlob) {
                            snack(getString(R.string.download_blob_unsupported))
                        } else snack(getString(R.string.download_failed))
                    }
            }
        }
        if (req.isBlob) { snack(getString(R.string.download_blob_unsupported)); return }
        if (Prefs.askBeforeDownload) {
            val size = if (contentLength > 0) Formatter.formatFileSize(this, contentLength) else getString(R.string.unknown_size)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_title)
                .setMessage(getString(R.string.download_message, req.fileName, size, UrlUtils.displayHost(url)))
                .setPositiveButton(R.string.download) { _, _ -> start() }
                .setNeutralButton(R.string.open_external) { _, _ -> openExternal(url) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else start()
        if (emptyPopup) closeTab(tab)
    }

    private fun ensureStoragePermission(onResult: (Boolean) -> Unit) {
        if (!core.downloads.needsStoragePermission(this)) { onResult(true); return }
        requestAndroidPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { onResult(it[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) }
    }

    override fun onLinkLongPressed(tab: Tab, webView: WebView): Boolean {
        val result = webView.hitTestResult
        return when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val link = msg.data.getString("url")
                    val src = msg.data.getString("src")
                    showLinkMenu(tab, link ?: src, src); true
                }
                webView.requestFocusNodeHref(handler.obtainMessage()); true
            }
            WebView.HitTestResult.IMAGE_TYPE -> { showLinkMenu(tab, null, result.extra); true }
            else -> false
        }
    }

    private fun showLinkMenu(tab: Tab, link: String?, image: String?) {
        val target = link ?: image ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            items += getString(R.string.open_in_new_tab) to { core.tabs.createTab(tab.sessionId, link, select = false, openerTabId = tab.id); updateTabCount(); snack(getString(R.string.tab_opened_background)) }
            items += getString(R.string.copy_link) to { copyToClipboard(link) }
            items += getString(R.string.share_link) to { shareUrl(link, "") }
        }
        if (image != null) {
            items += getString(R.string.open_image_new_tab) to { core.tabs.createTab(tab.sessionId, image, select = false, openerTabId = tab.id); updateTabCount() }
            items += getString(R.string.download_image) to { onDownloadRequested(tab, image, tab.webView?.settings?.userAgentString, null, null, -1) }
        }
        items += getString(R.string.open_external) to { openExternal(target) }
        MaterialAlertDialogBuilder(this)
            .setTitle(UrlUtils.displayHost(target).ifBlank { target })
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    override fun onRenderProcessGone(tab: Tab) {
        if (tab.id == currentTabId) {
            webContainer.removeAllViews()
            updateErrorPage(tab)
            updateToolbar(tab)
        }
    }

    // ================================================================== helpers

    private fun captureThumbnail(tab: Tab) {
        val wv = tab.webView ?: return
        if (wv.width <= 0 || wv.height <= 0 || wv.parent == null) return
        try {
            val scale = 0.33f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt().coerceAtLeast(1), (wv.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            wv.draw(canvas)
            tab.thumbnail = bmp
        } catch (t: Throwable) {
            AppLog.w(TAG, "thumbnail failed", t)
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
