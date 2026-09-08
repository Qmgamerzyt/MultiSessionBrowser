package app.multisession.browser.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.tabs.PageError
import app.multisession.browser.tabs.Tab

class BrowserWebViewClient(private val core: BrowserCore, private val tab: Tab) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false // let the real WebView navigate
        return core.tabs.host?.onExternalScheme(tab, uri, request.hasGesture()) ?: true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // Serves local HTML projects / bundled assets over https://appassets.androidplatform.net (no file://).
        return core.localContent.intercept(request.url)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        tab.error = null
        tab.isLoading = true
        tab.progress = 0
        tab.favicon = favicon
        tab.url = url
        core.tabs.notifyTabUpdated(tab)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Also fires for pushState/replaceState navigations in SPAs.
        tab.url = url
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        tab.isLoading = false
        tab.progress = 100
        core.tabs.onNavigated(tab, url, view.title)
        core.tabs.recordHistory(tab)
        core.sessions.get(tab.sessionId)?.let { core.isolation.flushCookies(it) }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val description = error.description?.toString() ?: ""
        if (description.contains("ERR_ABORTED") || description.contains("ERR_BLOCKED_BY_CLIENT")) return
        AppLog.w(TAG, "Main-frame error ${error.errorCode} $description")
        tab.isLoading = false
        tab.error = PageError(error.errorCode, description, request.url.toString())
        core.tabs.notifyTabUpdated(tab)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = core.tabs.host
        if (host == null) {
            handler.cancel()
        } else {
            host.onSslError(tab, handler, error)
        }
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        val ui = core.tabs.host
        if (ui == null) handler.cancel() else ui.onHttpAuthRequest(tab, handler, host, realm)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        AppLog.w(TAG, "onRenderProcessGone crashed=${detail.didCrash()}")
        core.tabs.onRenderProcessGone(tab)
        core.tabs.host?.onRenderProcessGone(tab)
        return true // we handled it; do not kill the app
    }

    private companion object {
        const val TAG = "WebViewClient"
    }
}
