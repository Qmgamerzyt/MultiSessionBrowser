package app.multisession.browser.session

import android.content.Context
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.data.db.profileName

/**
 * Genuine per-session isolation using the AndroidX WebKit multi-profile API.
 *
 * Every session owns a WebView [Profile]. A profile has its own cookie jar, localStorage /
 * IndexedDB / WebSQL (WebStorage), HTTP cache, service workers and geolocation permissions.
 * Cookies (including HttpOnly / Secure / SameSite) are handled entirely by the WebView's own
 * network stack - nothing is copied or faked in JavaScript.
 *
 * Facts that drive this design:
 *  - Multiple WebView objects in one process share ONE default profile. Creating more WebViews
 *    does not isolate anything.
 *  - WebView.setDataDirectorySuffix() is per *process*, so it cannot give per-session isolation
 *    inside a single-process app.
 *  - The profile must be attached with WebViewCompat.setProfile() BEFORE the WebView loads anything.
 *
 * When the device's Android System WebView is too old to support MULTI_PROFILE we fall back to the
 * shared default profile and *tell the user* (banner) instead of pretending sessions are isolated.
 */
class SessionIsolation {

    enum class Mode { PROFILES, SHARED }

    val mode: Mode = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) Mode.PROFILES else Mode.SHARED
    } catch (t: Throwable) {
        AppLog.w(TAG, "WebView feature check failed, assuming shared profile", t)
        Mode.SHARED
    }

    val isIsolated: Boolean get() = mode == Mode.PROFILES

    private fun store(): ProfileStore = ProfileStore.getInstance()

    fun profileName(session: SessionEntity): String =
        if (isIsolated) session.profileName else Profile.DEFAULT_PROFILE_NAME

    /** Returns (creating if needed) the profile for this session, or null in shared mode. */
    fun profile(session: SessionEntity): Profile? =
        if (isIsolated) store().getOrCreateProfile(session.profileName) else null

    /** Must be called on a freshly constructed WebView before any load. */
    fun attach(webView: WebView, session: SessionEntity) {
        if (!isIsolated) return
        profile(session) // ensure it exists
        WebViewCompat.setProfile(webView, session.profileName)
    }

    fun cookieManager(session: SessionEntity): CookieManager =
        profile(session)?.cookieManager ?: CookieManager.getInstance()

    fun webStorage(session: SessionEntity): WebStorage =
        profile(session)?.webStorage ?: WebStorage.getInstance()

    fun geolocation(session: SessionEntity): GeolocationPermissions =
        profile(session)?.geolocationPermissions ?: GeolocationPermissions.getInstance()

    fun flushCookies(session: SessionEntity) {
        try {
            cookieManager(session).flush()
        } catch (t: Throwable) {
            AppLog.w(TAG, "cookie flush failed", t)
        }
    }

    fun clearCookies(session: SessionEntity) {
        val cm = cookieManager(session)
        cm.removeAllCookies(null)
        cm.flush()
        AppLog.i(TAG, "Cookies cleared for ${session.id.take(8)} (mode=$mode)")
    }

    fun clearSiteData(session: SessionEntity) {
        webStorage(session).deleteAllData()
        geolocation(session).clearAll()
        AppLog.i(TAG, "Site storage cleared for ${session.id.take(8)} (mode=$mode)")
    }

    /**
     * Clears the HTTP cache of the session's profile using a throw-away WebView bound to it.
     * Callers should have destroyed the session's live WebViews first.
     */
    fun clearCache(context: Context, session: SessionEntity) {
        val wv = WebView(context.applicationContext)
        try {
            attach(wv, session)
            wv.clearCache(true)
        } catch (t: Throwable) {
            AppLog.w(TAG, "clearCache failed", t)
        } finally {
            wv.destroy()
        }
    }

    /**
     * Deletes ALL data of the session's profile. Every WebView of the session must already be
     * destroyed, otherwise the WebView refuses (IllegalStateException). Returns false in shared
     * mode because deleting the default profile would wipe every session.
     */
    fun deleteProfileData(session: SessionEntity): Boolean {
        if (!isIsolated) return false
        return try {
            val deleted = store().deleteProfile(session.profileName)
            AppLog.i(TAG, "Profile delete for ${session.id.take(8)} -> $deleted")
            deleted
        } catch (t: Throwable) {
            AppLog.w(TAG, "deleteProfile failed", t)
            false
        }
    }

    fun webViewVersion(context: Context): String =
        WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"

    fun describe(context: Context): String = when (mode) {
        Mode.PROFILES -> context.getString(R.string.isolation_ok, webViewVersion(context))
        Mode.SHARED -> context.getString(R.string.isolation_unavailable, webViewVersion(context))
    }

    private companion object {
        const val TAG = "Isolation"
    }
}
