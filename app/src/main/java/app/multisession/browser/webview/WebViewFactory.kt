package app.multisession.browser.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.tabs.Tab

/**
 * Creates fully configured WebViews. Order matters: the session profile is attached
 * first (before anything could trigger a load), then settings, then clients.
 */
object WebViewFactory {
    private const val TAG = "WebViewFactory"

    fun create(activity: Activity, tab: Tab, session: SessionEntity, core: BrowserCore): WebView {
        // MutableContextWrapper lets the WebView survive Activity recreation without leaking it.
        val webView = WebView(MutableContextWrapper(activity))
        core.isolation.attach(webView, session)
        applySettings(webView, session, tab, core)
        webView.webViewClient = BrowserWebViewClient(core, tab)
        webView.webChromeClient = BrowserChromeClient(core, tab)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            core.tabs.host?.onDownloadRequested(tab, url, userAgent, contentDisposition, mimeType, contentLength)
        }
        webView.setOnLongClickListener { core.tabs.host?.onLinkLongPressed(tab, webView) ?: false }
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun applySettings(webView: WebView, session: SessionEntity, tab: Tab, core: BrowserCore) {
        val s = webView.settings
        s.javaScriptEnabled = Prefs.javaScriptEnabled
        s.domStorageEnabled = true
        s.databaseEnabled = true

        // Secure defaults: no file:// access, no universal access, no mixed content.
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = Prefs.safeBrowsing

        // Modern web behaviour
        s.setSupportMultipleWindows(true)          // window.open / target=_blank -> new tab (filtered by user gesture)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = !Prefs.mediaAutoplay
        s.setGeolocationEnabled(true)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(Prefs.zoomEnabled)
        s.builtInZoomControls = Prefs.zoomEnabled
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = true

        applyUserAgent(webView, tab.desktopMode || session.desktopMode)
        applyDesktopScale(webView, tab.desktopMode || session.desktopMode)

        // Dark mode: let the WebView darken pages that don't provide a dark theme (when app is in dark mode).
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                val night = (webView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, night)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "algorithmic darkening not applied", t)
        }

        // Cookies are per session profile; third-party policy is applied per WebView.
        val cookies = core.isolation.cookieManager(session)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, Prefs.thirdPartyCookies)
    }

    fun applyUserAgent(webView: WebView, desktop: Boolean) {
        val s = webView.settings
        val defaultUa = WebSettings.getDefaultUserAgent(webView.context)
        s.userAgentString = when {
            desktop -> desktopUserAgent(defaultUa)
            Prefs.uaMode == "custom" && Prefs.customUserAgent.isNotBlank() -> Prefs.customUserAgent
            // "Mobile" = the same Chrome UA without the WebView marker; some sign-in pages reject "; wv".
            Prefs.uaMode == "mobile" -> defaultUa.replace("; wv", "")
            else -> defaultUa
        }
    }

    fun applyDesktopScale(webView: WebView, desktop: Boolean) {
        webView.setInitialScale(if (desktop) 50 else 100)
    }

    fun applyDesktopViewport(webView: WebView, desktop: Boolean) {
        if (!desktop) return
        val js = """(function(){var v=document.querySelector('meta[name="viewport"]');if(v){v.setAttribute('content','width=1024');}})();"""
        webView.evaluateJavascript(js, null)
    }

    private fun desktopUserAgent(mobileUa: String): String =
        mobileUa.replace(Regex("\\(Linux;.*?\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile Safari", " Safari")
            .replace("; wv", "")
}
