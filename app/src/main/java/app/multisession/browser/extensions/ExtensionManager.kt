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
}

/** A browserAction as last reported by an extension (default action, i.e. not tab-specific). */
data class ExtensionAction(val extension: WebExtension, val action: WebExtension.Action)

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
 *  - browserAction / pageAction: icon, title, badge -> app menu; click -> action.click(); popups rendered in a
 *    GeckoSession the app displays (ActionDelegate.onTogglePopup / onOpenPopup);
 *  - browser.tabs.create / remove / update (TabDelegate + per-session SessionTabDelegate) -> tabs of the ACTIVE
 *    browser session; browser.runtime.openOptionsPage.
 *  - background scripts, content scripts, webRequest, storage, cookies, etc. run inside Gecko exactly as in Firefox.
 *
 * Documented limitations (Android/GeckoView architecture, not app bugs - see docs/EXTENSIONS.md):
 *  - Extensions are runtime-wide. Their storage (browser.storage) and background pages are NOT partitioned by our
 *    browser sessions (contextId); an extension can see tabs of every session (browser.tabs) and, with host
 *    permissions, read cookies of every contextual identity (browser.cookies with storeId). There is no per-session
 *    enable/disable in GeckoView.
 *  - No sidebar, no devtools panels, no native messaging, no browser.windows UI (one window), no keyboard commands UI,
 *    no context-menu items (menus API renders nothing in GeckoView), no omnibox keywords, no downloads.open().
 *  - Install of unsigned extensions is refused by Gecko in release builds (Mozilla signing is required).
 */
class ExtensionManager(private val core: BrowserCore) {

    private val _extensions = MutableStateFlow<List<WebExtension>>(emptyList())
    val extensions: StateFlow<List<WebExtension>> = _extensions.asStateFlow()

    private val _actions = MutableStateFlow<Map<String, ExtensionAction>>(emptyMap())
    /** Default browserActions keyed by extension id. */
    val actions: StateFlow<Map<String, ExtensionAction>> = _actions.asStateFlow()

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

    // ------------------------------------------------------------------ delegates

    private fun wire(ext: WebExtension) {
        try {
            ext.setActionDelegate(actionDelegate)
            ext.setTabDelegate(tabDelegate)
        } catch (t: Throwable) {
            AppLog.w(TAG, "wire failed for ${ext.id}", t)
        }
    }

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            val h = host ?: return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            h.onExtensionInstallPrompt(extension, permissions.toList(), origins.toList(), dataCollectionPermissions.toList()) { allow ->
                result.complete(WebExtension.PermissionPromptResponse(allow, false, false))
            }
            return result
        }

        override fun onUpdatePrompt(
            extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            val h = host ?: return GeckoResult.deny()
            val result = GeckoResult<AllowOrDeny>()
            h.onExtensionOptionalPrompt(extension, newPermissions.toList(), newOrigins.toList()) { allow -> result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
            return result
        }

        override fun onOptionalPrompt(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            val h = host ?: return GeckoResult.deny()
            val result = GeckoResult<AllowOrDeny>()
            h.onExtensionOptionalPrompt(extension, permissions.toList(), origins.toList()) { allow -> result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
            return result
        }
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
            if (session != null) return   // tab-specific override; the menu shows the default action
            _actions.value = _actions.value + (extension.id to ExtensionAction(extension, action))
            host?.onExtensionsChanged()
        }

        override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session != null) return
            if (!_actions.value.containsKey(extension.id)) {
                _actions.value = _actions.value + (extension.id to ExtensionAction(extension, action))
                host?.onExtensionsChanged()
            }
        }

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = openPopup(extension)

        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = openPopup(extension)

        private fun openPopup(extension: WebExtension): GeckoResult<GeckoSession>? {
            val h = host ?: return null
            val popup = GeckoSession()
            popup.open(core.engine.runtime)
            h.showExtensionPopup(extension, popup)
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
