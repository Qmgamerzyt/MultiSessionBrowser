package app.multisession.browser.webview

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.tabs.Tab

class BrowserChromeClient(private val core: BrowserCore, private val tab: Tab) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        tab.isLoading = newProgress < 100
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) {
            tab.title = title
            core.tabs.persistTab(tab)
            core.tabs.notifyTabUpdated(tab)
        }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        tab.favicon = icon
        core.tabs.notifyTabUpdated(tab)
    }

    /**
     * window.open() / target="_blank": open a new tab IN THE SAME SESSION.
     * The new WebView is attached to the same profile, so popups share the session's login state.
     * Popups without a user gesture are blocked (popup spam protection).
     */
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) {
            AppLog.i(TAG, "Popup blocked (no user gesture)")
            return false
        }
        val host = core.tabs.host ?: return false
        val newTab = core.tabs.createTab(tab.sessionId, UrlUtils.START_PAGE, select = true, openerTabId = tab.id)
        val newWebView = core.tabs.ensureWebView(newTab, host.activity, loadContent = false)
        (resultMsg.obj as WebView.WebViewTransport).webView = newWebView
        resultMsg.sendToTarget()
        host.onTabOpenedByPage(newTab)
        return true
    }

    override fun onCloseWindow(window: WebView) {
        val t = core.tabs.findByWebView(window) ?: return
        core.tabs.host?.onPageRequestedClose(t)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        return core.tabs.host?.onShowFileChooser(filePathCallback, fileChooserParams) ?: false
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val host = core.tabs.host
        if (host == null) request.deny() else host.onPermissionRequest(tab, request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        core.tabs.host?.onPermissionRequestCanceled(request)
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        val host = core.tabs.host
        if (host == null) callback.invoke(origin, false, false) else host.onGeolocationPrompt(tab, origin, callback)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        val host = core.tabs.host
        if (host == null) callback.onCustomViewHidden() else host.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        core.tabs.host?.onHideCustomView()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        AppLog.d(TAG, "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
        return true
    }

    private companion object {
        const val TAG = "ChromeClient"
    }
}
