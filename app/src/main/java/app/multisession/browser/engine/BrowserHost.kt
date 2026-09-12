package app.multisession.browser.engine

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import app.multisession.browser.tabs.Tab
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/** Everything the engine delegates need from the foreground UI. Implemented by BrowserActivity. */
interface BrowserHost {
    val activity: AppCompatActivity

    /** A tab created by web content (window.open / target=_blank) is ready to be displayed. */
    fun onTabOpenedByPage(tab: Tab)
    /** window.close() called by a page on a tab it opened. */
    fun onPageRequestedClose(tab: Tab)
    /** Page entered/left HTML5 fullscreen (video). The GeckoView renders it; the host hides its chrome. */
    fun onFullScreenChanged(tab: Tab, fullScreen: Boolean)
    /** Return true when the navigation was consumed (handled externally or blocked). */
    fun onExternalScheme(tab: Tab, uri: Uri, hasGesture: Boolean): Boolean
    /** Content the engine cannot display (Content-Disposition: attachment, unknown types, blob downloads). */
    fun onDownloadRequested(tab: Tab, response: WebResponse)
    /** Long-press on a link / image / media element. */
    fun onContextMenu(tab: Tab, element: GeckoSession.ContentDelegate.ContextElement)
    /** The tab's GeckoSession is about to be closed: release it from the GeckoView if it is displayed. */
    fun onSessionClosing(tab: Tab)
    /** The content process of the tab crashed or was killed; its GeckoSession is already closed. */
    fun onContentProcessGone(tab: Tab, crashed: Boolean)

    /** <input type=file>: run the system picker; [onResult] gets the chosen URIs (null = cancelled). */
    fun pickFiles(mimeTypes: Array<String>?, multiple: Boolean, capture: Boolean, onResult: (Array<Uri>?) -> Unit)

    // ---- permissions (PermissionDelegate) ----
    fun onAndroidPermissionsRequest(tab: Tab, permissions: Array<String>, callback: GeckoSession.PermissionDelegate.Callback)
    fun onContentPermissionRequest(tab: Tab, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>
    fun onMediaPermissionRequest(
        tab: Tab,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    )
}
