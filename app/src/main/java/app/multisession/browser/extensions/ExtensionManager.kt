package app.multisession.browser.extensions

import android.content.Context
import android.net.Uri
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.R
import app.multisession.browser.tabs.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.util.UUID

/** UI the extension system needs from the foreground Activity (implemented by BrowserActivity). */
interface ExtensionHost {
    /** Install prompt: show the requested permissions; [onDecision] true = install. */
    fun onExtensionInstallPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, dataCollection: List<String>, onDecision: (Boolean) -> Unit)
    /** permissions.request() at runtime. */
    fun onExtensionOptionalPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, onDecision: (Boolean) -> Unit)
    /** browserAction popup: render [popupSession] (already open) in a small app-owned surface; close it when dismissed. */
    fun showExtensionPopup(ext: WebExtension, popupSession: GeckoSession)
    /** Installed set / an action (icon, badge, title) changed: refresh the app menu. */
    fun onExtensionsChanged()
    /** browser.tabs.create(): create a tab in the active browser session; return its Tab (with a configured, unopened GeckoSession). */
    fun onExtensionNewTab(ext: WebExtension, url: String?, active: Boolean): Tab?
    fun onExtensionOpenOptions(ext: WebExtension, url: String)
    /** An install that did not originate from the Extensions screen finished (page `.xpi` link, AMO
     *  add-on page). Default is a no-op so only hosts able to surface it have to implement it. */
    fun onExtensionInstallResult(r: Result<WebExtension>) {}

    // ---- v2.1.8 popup lifecycle ----
    /** True while the browserAction popup surface from a previous click is still on screen. Lets a
     *  second click toggle it closed instead of always spawning a fresh popup session. */
    fun isExtensionPopupOpen(): Boolean = false
    /** Close the browserAction popup if it is showing: a different tab was displayed, or the app went
     *  to the background. Default is a no-op so only hosts with a surface have to implement it. */
    fun dismissExtensionPopup() {}
}

/** A browserAction as last reported by an extension (default action, i.e. not tab-specific). */
data class ExtensionAction(val extension: WebExtension, val action: WebExtension.Action)

/**
 * Temporary v2.1.8 extension diagnostic switch. While it is false every `EXTDBG` line is compiled
 * out of the logging path, so a release build writes none of it; flip it to true for one diagnostic
 * build when tracing an add-on problem. The switch and its call sites are removed in 2.1.9.
 *
 * Never logs cookies, tokens or a URL beyond `scheme://host`.
 */
internal const val EXTDBG = false
internal const val EXTDBG_TAG = "EXTDBG"

/**
 * Localized, human-readable reason for a failed extension install. Shared by the Extensions screen
 * and by installs started from a page / the AMO client so both report the same wording. Only
 * Gecko's structured [WebExtension.InstallException] codes are mapped; anything else falls back to
 * the platform message so nothing is ever swallowed.
 */
fun installErrorMessage(context: Context, t: Throwable): String {
    val ie = t as? WebExtension.InstallException ?: return t.message ?: t.javaClass.simpleName
    val c = context
    return when (ie.code) {
        WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> c.getString(R.string.ext_err_network)
        WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH -> c.getString(R.string.ext_err_hash)
        WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> c.getString(R.string.ext_err_corrupt)
        WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS -> c.getString(R.string.ext_err_file)
        WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> c.getString(R.string.ext_err_unsigned)
        WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE -> c.getString(R.string.ext_err_type)
        WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_VERSION -> c.getString(R.string.ext_err_version)
        WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_ID -> c.getString(R.string.ext_err_id)
        WebExtension.InstallException.ErrorCodes.ERROR_INVALID_DOMAIN -> c.getString(R.string.ext_err_domain)
        WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED -> c.getString(R.string.ext_err_blocked)
        WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> c.getString(R.string.ext_err_incompatible)
        WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE -> c.getString(R.string.ext_err_unsupported)
        WebExtension.InstallException.ErrorCodes.ERROR_ADMIN_INSTALL_ONLY -> c.getString(R.string.ext_err_admin)
        WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED -> c.getString(R.string.ext_err_softblocked)
        WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> c.getString(R.string.ext_err_canceled)
        WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED -> c.getString(R.string.ext_err_postponed)
        else -> c.getString(R.string.ext_err_code, ie.code)
    }
}

/**
 * Real GeckoView WebExtension support (v2.0.2 foundation).
 *
 * What genuinely works through GeckoView's WebExtensionController and is wired here:
 *  - install from an https XPI URL or a local .xpi file (signature + manifest are validated by Gecko), the install
 *    permission prompt (PromptDelegate), enable / disable / uninstall, optional-permission prompts;
 *  - persistence: Gecko stores installed extensions and their enabled state in the profile - nothing to redo in the app;
 *  - browserAction / pageAction: icon, title, badge -> app menu and the "Extension actions" sheet; click ->
 *    action.click() (toggle-closes an open popup); popups rendered in a GeckoSession the app displays
 *    (ActionDelegate.onTogglePopup / onOpenPopup);
 *  - browser.tabs.create / remove / update (TabDelegate + per-session SessionTabDelegate) -> tabs of the ACTIVE
 *    browser session; browser.runtime.openOptionsPage.
 *  - downloads.download(): fetched by the app and written straight to the public Downloads folder, with
 *    progress and STATE_COMPLETE / STATE_INTERRUPTED reported back (ExtensionDownloadRunner).
 *  - background scripts, content scripts, webRequest, storage, etc. run inside Gecko exactly as in Firefox.
 *  - cookies (v2.1.9): addressable PER browser session. Stock GeckoView only partitions cookies by userContextId /
 *    privateBrowsingId, so browser.cookies could not reach our jars at all (that was a real, documented defect, not a
 *    permission problem). tools/patch_omni_cookies.py rewrites three files inside assets/omni.ja - GeckoViewTab
 *    exposes the SAFE "gvctx"+hex id next to the raw userContextId, ext-toolkit.js gains the stores
 *    "firefox-gvctx-<id>" / "firefox-gvctxp-<id>", ext-cookies.js maps them onto the geckoViewSessionContextId
 *    origin attribute in both directions - so get/getAll/set/remove/onChanged and tab.cookieStoreId all address
 *    exactly one session's jar. Isolation is untouched: CookieStorage looks cookies up by a hash that includes
 *    mGeckoViewSessionContextId, so a wrong or missing context matches NOTHING rather than another session's jar.
 *    The committed asset is re-hashed out of every built APK in CI, so a bad asset merge fails the run instead of
 *    silently shipping broken cookies. See docs/EXTENSIONS.md for the two remaining documented limitations
 *    (an add-on must pass storeId explicitly; getAllCookieStores() can enumerate every session's store).
 *
 * Documented limitations (Android/GeckoView architecture, not app bugs - see docs/EXTENSIONS.md):
 *  - Extensions are runtime-wide. Their storage (browser.storage) and background pages are NOT partitioned by our
 *    browser sessions (contextId); an extension can see tabs of every session (browser.tabs). There is no per-session
 *    enable/disable in GeckoView.
 *  - No sidebar, no devtools panels, no native messaging, no browser.windows UI (one window), no keyboard commands UI,
 *    no context-menu items (menus API renders nothing in GeckoView), no omnibox keywords, no downloads.open();
 *    downloads.saveAs shows no file chooser and downloads.pause/remove issued by the add-on are not observable.
 *  - Install of unsigned extensions is refused by Gecko in release builds (Mozilla signing is required).
 */
class ExtensionManager(private val core: BrowserCore) {

    private val _extensions = MutableStateFlow<List<WebExtension>>(emptyList())
    val extensions: StateFlow<List<WebExtension>> = _extensions.asStateFlow()

    private val _actions = MutableStateFlow<Map<String, ExtensionAction>>(emptyMap())
    /** Default browserActions keyed by extension id. */
    val actions: StateFlow<Map<String, ExtensionAction>> = _actions.asStateFlow()

    /**
     * Tab-specific action overrides Gecko reported with a non-null session (v2.1.8, Q8a). Keyed by
     * extension id, then by the GeckoSession the override belongs to; consulted through [actionsFor]
     * which merges the override over the default with [WebExtension.Action.withDefault].
     *
     * UI-thread only (ActionDelegate callbacks and menu rendering both run there), so no locking.
     * Entries are dropped in [forgetSession] when a tab's session closes so the map cannot grow.
     */
    private val sessionActions = HashMap<String, HashMap<GeckoSession, WebExtension.Action>>()

    var host: ExtensionHost? = null
    private var started = false

    private val controller: WebExtensionController get() = core.engine.runtime.webExtensionController

    fun start() {
        if (started) return
        started = true
        try {
            controller.setPromptDelegate(promptDelegate)
            controller.setAddonManagerDelegate(addonDelegate)
            refresh()
        } catch (t: Throwable) {
            AppLog.e(TAG, "WebExtensionController setup failed", t)
        }
    }

    fun refresh() {
        try {
            controller.list().accept({ list ->
                val exts = list ?: emptyList()
                exts.forEach { wire(it) }
                _extensions.value = exts
                _actions.value = _actions.value.filterKeys { id -> exts.any { it.id == id } }
                AppLog.i(TAG, "${exts.size} extension(s) installed")
                host?.onExtensionsChanged()
                // v2.1.8 (defect 2): sessions created before the list arrived never got their
                // delegates, and the first active-tab marker may have been dispatched too early.
                reattachAll()
            }, { AppLog.w(TAG, "list() failed", it) })
        } catch (t: Throwable) {
            AppLog.w(TAG, "list() not available", t)
        }
    }

    fun get(id: String): WebExtension? = _extensions.value.firstOrNull { it.id == id }

    /** Installs from an https:// XPI URL (e.g. addons.mozilla.org "download file" link). */
    fun install(uri: String, onResult: (Result<WebExtension>) -> Unit) {
        val u = uri.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://") && !u.startsWith("file://")) {
            onResult(Result.failure(IllegalArgumentException("Extension URL must be https:// (an .xpi file)"))); return
        }
        try {
            controller.install(u).accept({ ext ->
                if (ext == null) { onResult(Result.failure(IllegalStateException("Install returned nothing"))); return@accept }
                wire(ext)
                refresh()
                onResult(Result.success(ext))
            }, { t -> onResult(Result.failure(t ?: IllegalStateException("Install failed"))) })
        } catch (t: Throwable) {
            onResult(Result.failure(t))
        }
    }

    /** Installs a local .xpi the user picked: it is copied into app storage first (Gecko needs a stable file:// path). */
    fun installFromFile(source: Uri, onResult: (Result<WebExtension>) -> Unit) {
        core.scope.launch {
            val copied = withContext(Dispatchers.IO) {
                try {
                    val dir = File(core.app.filesDir, "extensions").apply { mkdirs() }
                    val target = File(dir, UUID.randomUUID().toString() + ".xpi")
                    core.app.contentResolver.openInputStream(source)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Cannot read the selected file")
                    Result.success(target)
                } catch (t: Throwable) { Result.failure(t) }
            }
            copied.onFailure { onResult(Result.failure(it)) }
                .onSuccess { f -> install(Uri.fromFile(f).toString()) { r -> onResult(r) } }
        }
    }

    fun uninstall(ext: WebExtension, onDone: (Throwable?) -> Unit = {}) {
        controller.uninstall(ext).accept({ _actions.value = _actions.value - ext.id; refresh(); onDone(null) }, { onDone(it) })
    }

    fun setEnabled(ext: WebExtension, enabled: Boolean, onDone: (Throwable?) -> Unit = {}) {
        val r = if (enabled) controller.enable(ext, WebExtensionController.EnableSource.USER) else controller.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ refresh(); onDone(null) }, { onDone(it) })
    }

    /** Called for every new tab GeckoSession so browser.tabs.remove/update can address it. */
    fun attachToSession(gs: GeckoSession, tab: Tab) {
        _extensions.value.forEach { ext ->
            try { gs.webExtensionController.setTabDelegate(ext, sessionTabDelegate) } catch (_: Throwable) {}
            try { gs.webExtensionController.setActionDelegate(ext, actionDelegate) } catch (_: Throwable) {}
        }
    }

    /**
     * v2.1.8 (defect 2): [attachToSession] only ever ran while a session was created, and at that
     * moment `_extensions` is still empty - `list()` answers asynchronously after `ready.complete`,
     * and [SessionFactory] creates sessions from the same startup path. Every session that existed
     * before the list came back therefore had no SessionTabDelegate / ActionDelegate, so
     * `browser.tabs.remove`, `browser.tabs.update` and tab-scoped action updates were dropped
     * without a trace.
     *
     * Re-runs the attach for every session that still exists and re-applies the active-tab marker
     * Gecko needs for `tabs.query({active:true})` and for `action.click()` to reach the add-on at
     * all. Cheap (a handful of sessions, one delegate call each) and called from [refresh], which
     * already runs on install / enable / disable / update check.
     */
    fun reattachAll() {
        // core.scope is Main.immediate: runs inline when we are already on the UI thread and hops
        // there otherwise, so TabManager's tabs map is only read from the thread that mutates it.
        core.scope.launch {
            try {
                val live = core.tabs.liveSessions()
                live.forEach { (tab, gs) -> attachToSession(gs, tab) }
                val shown = core.tabs.displayedSession()
                live.forEach { (_, gs) -> if (gs.isOpen) setTabActive(gs, gs === shown) }
                if (EXTDBG) AppLog.d(EXTDBG_TAG, "reattachAll sessions=${live.size} shown=${shown != null}")
            } catch (t: Throwable) {
                AppLog.w(TAG, "reattachAll failed", t)
            }
        }
    }

    /**
     * v2.1.8 (root cause of "tapping an add-on does nothing"): Gecko tracks which tab is active in
     * `tabTracker.activeTab`, which is only ever set from here. Without it
     * `ExtensionActions.triggerClickOrPopup(null)` throws a TypeError while handling
     * `GeckoView:BrowserAction:Click`, so `Action.click()`'s `GeckoResult` never resolves: no
     * popup, no `browserAction.onClicked`, no error - a silent no-op. `tabs.query({active:true})`
     * (Cookie-Editor's popup calls it nine times) returned an empty list for the same reason.
     *
     * Own try/catch by design: this is a best-effort marker on a background tab switch, so a
     * Gecko-side failure must never abort the switch itself. The failure is logged, never swallowed.
     *
     * Distinct from `GeckoSession.setActive()`, which only toggles `docShellIsActive`.
     */
    fun setTabActive(gs: GeckoSession, active: Boolean) {
        try {
            // Runtime-wide controller: `GeckoSession.getWebExtensionController()` is
            // WebExtension.SessionController, which only carries the per-session delegate setters
            // and has no setTabActive at all.
            controller.setTabActive(gs, active)
            if (EXTDBG) AppLog.d(EXTDBG_TAG, "setTabActive active=$active open=${gs.isOpen}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "setTabActive failed active=$active", t)
        }
    }

    /**
     * The actions to render for [displayedSession]: the default action of every add-on that exposes
     * one, with the tab-specific override Gecko reported for that session merged over it by
     * [WebExtension.Action.withDefault] (v2.1.8, Q8a). Add-ons with no action at all are not here -
     * a background/ad-block add-on has nothing to trigger and stays in the Extensions screen only.
     */
    fun actionsFor(displayedSession: GeckoSession?): Collection<ExtensionAction> =
        _actions.value.values.map { d ->
            val tabAction = displayedSession?.let { sessionActions[d.extension.id]?.get(it) }
                ?: return@map d
            ExtensionAction(d.extension, tabAction.withDefault(d.action))
        }

    /** The tab's session is closing: drop its action overrides so the map cannot grow. */
    fun forgetSession(gs: GeckoSession) {
        if (sessionActions.isEmpty()) return
        sessionActions.values.forEach { it.remove(gs) }
        sessionActions.entries.removeAll { it.value.isEmpty() }
    }

    // ------------------------------------------------------------------ delegates

    private fun wire(ext: WebExtension) {
        try {
            ext.setActionDelegate(actionDelegate)
            ext.setTabDelegate(tabDelegate)
            // v2.1.8 (Q6a): without this Gecko parks `downloads.download()` in `mPendingDownload`
            // forever, so the add-on's promise never settled. The runner answers it honestly -
            // a rejected promise when the fetch cannot start, never a silent hang.
            ext.setDownloadDelegate(downloadDelegate)
            // Baseline of what Gecko granted this add-on, so a prompt-time grant can be compared
            // against it later without ever printing anything but permission names.
            logGrants("wire", ext)
        } catch (t: Throwable) {
            AppLog.w(TAG, "wire failed for ${ext.id}", t)
        }
    }

    /** Owns every add-on initiated download (v2.1.8). See [ExtensionDownloadRunner]. */
    private val downloadRunner = ExtensionDownloadRunner(core)
    private val downloadDelegate = object : WebExtension.DownloadDelegate {
        override fun onDownload(
            extension: WebExtension, request: WebExtension.DownloadRequest,
        ): GeckoResult<WebExtension.DownloadInitData>? = downloadRunner.start(extension, request)
    }

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            logGrants("install-prompt", extension, permissions, origins, dataCollectionPermissions)
            val h = host
            if (h == null) {
                // Deliberate deny, not an omission: with no host there is no dialog to ask with.
                AppLog.w(TAG, "Install prompt auto-denied: no host (id=${extension.id})")
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
            }
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            h.onExtensionInstallPrompt(extension, permissions.toList(), origins.toList(), dataCollectionPermissions.toList()) { allow ->
                if (EXTDBG) AppLog.d(EXTDBG_TAG, "install-prompt answered allow=$allow id=${extension.id}")
                result.complete(WebExtension.PermissionPromptResponse(allow, false, false))
            }
            return result
        }

        override fun onUpdatePrompt(
            extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            logGrants("update-prompt", extension, newPermissions, newOrigins, newDataCollectionPermissions)
            val h = host
            if (h == null) {
                // Deny is the only safe answer: an update that adds access must never be accepted
                // silently, and `GeckoResult.deny()` is GeckoView's documented rejection value.
                AppLog.w(TAG, "Update prompt auto-denied: no host (id=${extension.id})")
                return GeckoResult.deny()
            }
            val result = GeckoResult<AllowOrDeny>()
            h.onExtensionOptionalPrompt(extension, newPermissions.toList(), newOrigins.toList()) { allow ->
                if (EXTDBG) AppLog.d(EXTDBG_TAG, "update-prompt answered allow=$allow id=${extension.id}")
                result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            return result
        }

        override fun onOptionalPrompt(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            logGrants("optional-prompt", extension, permissions, origins, dataCollectionPermissions)
            val h = host
            if (h == null) {
                AppLog.w(TAG, "Optional-permission prompt auto-denied: no host (id=${extension.id})")
                return GeckoResult.deny()
            }
            val result = GeckoResult<AllowOrDeny>()
            h.onExtensionOptionalPrompt(extension, permissions.toList(), origins.toList()) { allow ->
                if (EXTDBG) AppLog.d(EXTDBG_TAG, "optional-prompt answered allow=$allow id=${extension.id}")
                result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            return result
        }
    }

    /**
     * The six `WebExtension.MetaData` grant arrays, which are the only place Gecko reports the
     * difference between what an add-on *asked* for ([permissions]/[origins] at prompt time) and
     * what it actually *holds* (`requiredPermissions`, `grantedOptionalPermissions`, ...).
     * Permission and origin **names only** — never a cookie, a value or a URL beyond a pattern —
     * and never emitted unless [EXTDBG] is on.
     */
    private fun logGrants(
        stage: String, ext: WebExtension,
        permissions: Array<String>? = null, origins: Array<String>? = null, dataCollection: Array<String>? = null,
    ) {
        if (!EXTDBG) return
        fun names(values: Array<String>?) = if (values.isNullOrEmpty()) "-" else values.joinToString(",")
        val m = ext.metaData
        AppLog.d(
            EXTDBG_TAG,
            "$stage id=${ext.id} askedP=${names(permissions)} askedO=${names(origins)} askedDC=${names(dataCollection)} " +
                "requiredP=${names(m.requiredPermissions)} requiredO=${names(m.requiredOrigins)} " +
                "grantedOptP=${names(m.grantedOptionalPermissions)} grantedOptO=${names(m.grantedOptionalOrigins)} " +
                "grantedDC=${names(m.grantedOptionalDataCollectionPermissions)}",
        )
    }

    /**
     * Whether the extension may run in private sessions (our private sessions use
     * `usePrivateMode(session.isPrivate)`), so this is what keeps an add-on out of private browsing
     * unless the user opts in. Gecko's default is false. Mirrors [setEnabled]: main-thread safe
     * (`assertOnHandlerThread` only requires a Looper, and the popup click handler has one).
     */
    fun setAllowedInPrivateBrowsing(ext: WebExtension, allowed: Boolean, onDone: (Throwable?) -> Unit = {}) {
        controller.setAllowedInPrivateBrowsing(ext, allowed).accept({ refresh(); onDone(null) }, { onDone(it) })
    }

    /**
     * v2.1.7 (issue C): manual "Check for updates". This is `WebExtensionController.update(ext)`
     * (verified present in GeckoView 155), which resolves with the extension after Gecko has looked
     * for a newer build on AMO. When one exists Gecko downloads and installs it itself, so the
     * Mozilla signature check and every other install guarantee run unchanged - this call can only
     * ever move an add-on to a version Mozilla already signed.
     *
     * The result carries the refreshed [WebExtension] (null is reported as a failure, never as
     * success), so the caller can compare `metaData.version` before and after.
     */
    fun checkUpdate(ext: WebExtension, onDone: (Result<WebExtension>) -> Unit) {
        try {
            controller.update(ext).accept(
                { updated ->
                    refresh()
                    if (updated == null) onDone(Result.failure(IllegalStateException("update check returned nothing")))
                    else onDone(Result.success(updated))
                },
                { t -> onDone(Result.failure(t ?: IllegalStateException("update check failed"))) },
            )
        } catch (t: Throwable) {
            onDone(Result.failure(t))
        }
    }

    private val addonDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalled(extension: WebExtension) { wire(extension); refresh() }
        override fun onUninstalled(extension: WebExtension) { _actions.value = _actions.value - extension.id; refresh() }
        override fun onEnabled(extension: WebExtension) { refresh() }
        override fun onDisabled(extension: WebExtension) { _actions.value = _actions.value - extension.id; refresh() }

        /**
         * A Gecko-side install failed. Installs started by the app already report through their
         * install() promise (the user-facing surface), so this logs the structured error code and
         * refreshes the list only - one failure never produces two notifications, and failures Gecko
         * raises on its own are no longer silently dropped.
         */
        override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
            AppLog.w(TAG, "Installation failed: code=${installException.code} addon=${extension?.id ?: "?"}")
            refresh()
        }
    }

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            // v2.1.8 (defect 3): a session-specific override used to be discarded here, which threw
            // away every tab-scoped badge/title/icon. The default still lands in `_actions`; the
            // override is kept per session and merged back by actionsFor().
            if (session != null) {
                sessionActions.getOrPut(extension.id) { HashMap() }[session] = action
                host?.onExtensionsChanged()
                return
            }
            _actions.value = _actions.value + (extension.id to ExtensionAction(extension, action))
            host?.onExtensionsChanged()
        }

        override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session != null) {
                sessionActions.getOrPut(extension.id) { HashMap() }[session] = action
                host?.onExtensionsChanged()
                return
            }
            if (!_actions.value.containsKey(extension.id)) {
                _actions.value = _actions.value + (extension.id to ExtensionAction(extension, action))
                host?.onExtensionsChanged()
            }
        }

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = openPopup(extension)

        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = openPopup(extension)

        /**
         * v2.1.8 (Q3a): this used to always build a fresh popup session, so a second click stacked
         * another sheet on top of the open one and there was no way to dismiss it by clicking again.
         *
         * Returning null is the documented contract - both `onTogglePopup` and `onOpenPopup` are
         * annotated `@return ... null if no popup will be displayed`, and the package-private
         * `WebExtension.Action.openPopup(popup, uri)` starts with `if (popup == null) return`. Gecko
         * therefore shows nothing, and our side has already closed the surface that was up.
         */
        private fun openPopup(extension: WebExtension): GeckoResult<GeckoSession>? {
            val h = host ?: return null
            if (h.isExtensionPopupOpen()) {
                h.dismissExtensionPopup()
                if (EXTDBG) AppLog.d(EXTDBG_TAG, "popup toggled closed ext=${extension.id}")
                return null
            }
            val popup = GeckoSession()
            popup.open(core.engine.runtime)
            h.showExtensionPopup(extension, popup)
            if (EXTDBG) AppLog.d(EXTDBG_TAG, "popup opened ext=${extension.id}")
            return GeckoResult.fromValue(popup)
        }
    }

    private val tabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
            val h = host ?: return null
            val tab = h.onExtensionNewTab(source, createDetails.url, createDetails.active != false) ?: return null
            val gs = tab.geckoSession ?: return null
            return GeckoResult.fromValue(gs)
        }

        override fun onOpenOptionsPage(source: WebExtension) {
            val url = source.metaData.optionsPageUrl ?: return
            host?.onExtensionOpenOptions(source, url)
        }
    }

    private val sessionTabDelegate = object : WebExtension.SessionTabDelegate {
        override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
            val tab = core.tabs.findBySession(session) ?: return GeckoResult.deny()
            core.scope.launch { core.tabs.host?.onPageRequestedClose(tab) }
            return GeckoResult.allow()
        }

        override fun onUpdateTab(source: WebExtension, session: GeckoSession, details: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
            val tab = core.tabs.findBySession(session) ?: return GeckoResult.deny()
            if (details.active == true) core.scope.launch { core.tabs.host?.onTabOpenedByPage(tab) }
            return GeckoResult.allow()
        }
    }

    private companion object {
        const val TAG = "Extensions"
    }
}
