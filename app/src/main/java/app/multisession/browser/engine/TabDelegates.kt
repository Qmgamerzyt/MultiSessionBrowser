package app.multisession.browser.engine

import android.net.Uri
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.permissions.PermissionValue
import app.multisession.browser.permissions.SitePermissionStore
import app.multisession.browser.permissions.SitePermissionType
import app.multisession.browser.tabs.PageError
import app.multisession.browser.tabs.Tab
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * All GeckoSession delegates of one tab (navigation, progress, content, permissions).
 * Replaces the former WebViewClient + WebChromeClient pair. UI work is forwarded to the
 * foreground [BrowserHost]; when no Activity is in the foreground requests are denied safely.
 */
class TabDelegates(private val core: BrowserCore, private val tab: Tab) :
    GeckoSession.NavigationDelegate,
    GeckoSession.ProgressDelegate,
    GeckoSession.ContentDelegate,
    GeckoSession.PermissionDelegate {

    private val host: BrowserHost? get() = core.tabs.host

    // ================================================================== NavigationDelegate

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean,
    ) {
        // Also fires for pushState/replaceState navigations in SPAs and for redirects.
        val u = url ?: return
        tab.sitePermissions = perms.toList()   // Gecko's stored permissions for this page (Site permissions dialog)
        if (u == "about:blank" && tab.awaitingDisplay) return   // popup placeholder, real URL follows
        tab.url = u
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        tab.canGoBack = canGoBack
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        tab.canGoForward = canGoForward
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        val uri = request.uri
        val scheme = uri.substringBefore(':', "").lowercase()
        return when (scheme) {
            "http", "https", "about", "data", "blob", "resource", "moz-extension" -> {
                if ((scheme == "http" || scheme == "https") && !request.isRedirect) applyDesktopSiteRule(uri)
                if (scheme == "resource" && !uri.startsWith(LocalContentLoader.BUNDLED_BASE)) GeckoResult.deny() else GeckoResult.allow()
            }
            "javascript" -> {
                // Only the ONE javascript: load this app itself issued (bookmarklet / HUD command, see TabManager.runScript)
                // may run; every page-initiated javascript: navigation stays denied as before.
                val pending = tab.pendingScript
                tab.pendingScript = null
                if (pending != null && (request.isDirectNavigation || request.uri == pending)) GeckoResult.allow()
                else { AppLog.i(TAG, "Blocked page-initiated javascript: navigation"); GeckoResult.deny() }
            }
            "file" -> if (core.localContent.isAllowedLocalUri(uri)) GeckoResult.allow() else {
                AppLog.i(TAG, "Blocked file:// navigation outside the projects folder"); GeckoResult.deny()
            }
            else -> {
                // intent:, mailto:, tel:, market:, custom app schemes -> the host decides (user gesture required).
                val consumed = host?.onExternalScheme(tab, Uri.parse(uri), request.hasUserGesture) ?: true
                if (consumed) GeckoResult.deny() else GeckoResult.allow()
            }
        }
    }

    /**
     * window.open() / target="_blank": Gecko asks us for a session to host the new window.
     * We create a tab IN THE SAME BROWSER SESSION (same contextId -> shares its login state) and
     * hand back its (still closed) GeckoSession; Gecko opens it. The tab is shown once it starts
     * loading (see [onPageStart]), so it is never displayed before the engine attached it.
     * Popups without a user gesture are blocked by Gecko's own popup blocker (PromptDelegate.onPopupPrompt).
     */
    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        if (host == null) {
            AppLog.i(TAG, "Popup refused: no foreground UI"); return null
        }
        val newTab = core.tabs.createPopupTab(tab, uri) ?: return null
        return GeckoResult.fromValue(newTab.geckoSession)
    }

    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
        AppLog.w(TAG, "Load error category=${error.category} code=${error.code} for ${uri?.take(80)}")
        tab.isLoading = false
        tab.progress = 100
        tab.error = PageError(error.code, error.category, describe(error), uri ?: tab.url)
        core.tabs.notifyTabUpdated(tab)
        return null // the native error page (BrowserActivity) is shown instead of an engine error page
    }

    // ================================================================== ProgressDelegate

    override fun onPageStart(session: GeckoSession, url: String) {
        tab.error = null
        tab.isLoading = true
        tab.progress = 0
        if (url != "about:blank") tab.url = url
        core.tabs.notifyTabUpdated(tab)
        if (tab.awaitingDisplay) {
            tab.awaitingDisplay = false
            host?.onTabOpenedByPage(tab)
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        tab.progress = progress
        tab.isLoading = progress < 100
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        tab.isLoading = false
        tab.progress = 100
        if (success && tab.error == null) {
            core.tabs.onNavigated(tab, tab.url, null)
            core.tabs.recordHistory(tab)
        } else {
            core.tabs.notifyTabUpdated(tab)
        }
    }

    override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
        tab.isSecure = securityInfo.isSecure
        core.tabs.notifyTabUpdated(tab)
    }

    /** Gecko reports the serialisable history/scroll/form state; it is what we persist and restore. */
    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
        tab.savedState = sessionState
    }

    // ================================================================== ContentDelegate

    override fun onTitleChange(session: GeckoSession, title: String?) {
        if (!title.isNullOrBlank() && title != tab.title) {
            tab.title = title
            core.tabs.persistTab(tab)
            core.tabs.notifyTabUpdated(tab)
        }
    }

    override fun onFocusRequest(session: GeckoSession) {
        // window.focus(): intentionally ignored - the engine must never move Android view focus
        // (that is what caused the "URL bar steals focus / keyboard opens" bug in the WebView era).
    }

    override fun onCloseRequest(session: GeckoSession) {
        host?.onPageRequestedClose(tab)
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        tab.isFullScreen = fullScreen
        host?.onFullScreenChanged(tab, fullScreen)
    }

    override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
        host?.onContextMenu(tab, element)
    }

    /** Downloads: content Gecko cannot render. The body stream is already authenticated in the session context. */
    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        val h = host
        if (h == null) {
            AppLog.i(TAG, "Download ignored (no foreground UI)")
            try { response.body?.close() } catch (_: Throwable) {}
            return
        }
        h.onDownloadRequested(tab, response)
    }

    override fun onCrash(session: GeckoSession) {
        AppLog.w(TAG, "Content process crashed for tab ${tab.id.take(8)}")
        core.tabs.onContentProcessGone(tab, crashed = true)
    }

    override fun onKill(session: GeckoSession) {
        AppLog.w(TAG, "Content process killed (low memory) for tab ${tab.id.take(8)}")
        core.tabs.onContentProcessGone(tab, crashed = false)
    }

    // ================================================================== PermissionDelegate

    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback) {
        val h = host
        if (h == null) callback.reject() else h.onAndroidPermissionsRequest(tab, permissions ?: emptyArray(), callback)
    }

    override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? {
        return host?.onContentPermissionRequest(tab, perm)
            ?: GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        val h = host
        if (h == null) callback.reject() else h.onMediaPermissionRequest(tab, uri, video, audio, callback)
    }

    // ================================================================== helpers

    /** Per-site "Desktop site" rule (Site permissions dialog / desktop toggle): switch UA + viewport before the load starts. */
    private fun applyDesktopSiteRule(uri: String) {
        val origin = SitePermissionStore.originOf(uri) ?: return
        val want = when (core.sitePermissions.get(tab.sessionId, origin, SitePermissionType.DESKTOP_SITE)) {
            PermissionValue.ALLOW -> true
            PermissionValue.BLOCK -> false
            else -> return
        }
        if (tab.desktopMode == want) return
        tab.desktopMode = want
        core.sessions.get(tab.sessionId)?.let { SessionFactory.applyTabSettings(tab, it) }
        core.tabs.persistTab(tab)
        AppLog.i(TAG, "Desktop site rule applied ($want) for $origin")
    }

    private fun describe(error: WebRequestError): String = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> "Server not found"
        WebRequestError.ERROR_CONNECTION_REFUSED -> "Connection refused"
        WebRequestError.ERROR_NET_TIMEOUT -> "Connection timed out"
        WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_NET_RESET -> "Connection interrupted"
        WebRequestError.ERROR_OFFLINE -> "Offline"
        WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_SECURITY_BAD_CERT -> "Secure connection failed"
        WebRequestError.ERROR_MALFORMED_URI -> "Malformed address"
        WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "Unsupported address scheme"
        WebRequestError.ERROR_REDIRECT_LOOP -> "Redirect loop"
        WebRequestError.ERROR_CONTENT_CRASHED -> "Content process crashed"
        else -> "Error ${error.category}/${error.code}"
    }

    private companion object {
        const val TAG = "TabDelegates"
    }
}
