package app.multisession.browser.webview

import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import app.multisession.browser.tabs.Tab

/** Everything a WebView needs from the foreground UI. Implemented by BrowserActivity. */
interface BrowserHost {
    val activity: AppCompatActivity

    fun onTabOpenedByPage(tab: Tab)
    /** window.close() called by a page on a tab it opened. */
    fun onPageRequestedClose(tab: Tab)
    fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onPermissionRequest(tab: Tab, request: PermissionRequest)
    fun onPermissionRequestCanceled(request: PermissionRequest)
    fun onGeolocationPrompt(tab: Tab, origin: String, callback: GeolocationPermissions.Callback)
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onSslError(tab: Tab, handler: SslErrorHandler, error: SslError)
    fun onHttpAuthRequest(tab: Tab, handler: HttpAuthHandler, host: String, realm: String)
    /** Return true when the navigation was consumed (handled externally or blocked). */
    fun onExternalScheme(tab: Tab, uri: Uri, hasGesture: Boolean): Boolean
    fun onDownloadRequested(tab: Tab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long)
    fun onLinkLongPressed(tab: Tab, webView: WebView): Boolean
    fun onRenderProcessGone(tab: Tab)
}
