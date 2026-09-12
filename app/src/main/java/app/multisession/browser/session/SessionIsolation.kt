package app.multisession.browser.session

import android.content.Context
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.data.db.contextId
import app.multisession.browser.engine.GeckoEngine

/**
 * Genuine per-session isolation with GeckoView's engine-native mechanism: **session contexts**
 * (`GeckoSessionSettings.contextId`, Gecko "contextual identities" / containers).
 *
 * Every browser session of this app maps to exactly one contextId (derived from the session id).
 * Gecko partitions cookies (incl. HttpOnly/Secure/SameSite), localStorage, IndexedDB, service
 * workers, cache entries and site permissions by that id inside its own network stack - nothing is
 * copied or faked in JavaScript. Two GeckoSessions (tabs) with the same contextId share state;
 * different contextIds never see each other's data. Unlike the former WebView Profile API this
 * works on every device, so there is no "reduced isolation" fallback any more.
 *
 * Private sessions additionally run in Gecko private mode (memory-only storage).
 */
class SessionIsolation(private val engine: GeckoEngine) {

    /** Always true with GeckoView: isolation does not depend on a system component version. */
    val isIsolated: Boolean get() = true

    fun contextId(session: SessionEntity): String = session.contextId

    /** Kept for call-site compatibility: the human readable partition name of a session. */
    fun profileName(session: SessionEntity): String = session.contextId

    /** Cookies are part of the context's site data; Gecko has no cookie-only clear per context. */
    fun clearCookies(session: SessionEntity) = clearSiteData(session)

    /** Removes cookies, storage, service workers and permissions of this session only. */
    fun clearSiteData(session: SessionEntity) {
        engine.clearSessionContext(session.contextId)
        AppLog.i(TAG, "Site data cleared for ${session.id.take(8)}")
    }

    /** Gecko's HTTP/image cache is one shared store (never identity data): clearing it is global. */
    fun clearCache(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") session: SessionEntity) {
        engine.clearCaches()
    }

    /** Deletes ALL data of the session's context (used when a session is deleted). */
    fun deleteProfileData(session: SessionEntity): Boolean {
        engine.clearSessionContext(session.contextId)
        return true
    }

    fun engineVersion(): String = "GeckoView " + GeckoEngine.geckoViewVersion()

    fun describe(context: Context): String = context.getString(R.string.isolation_ok, engineVersion())

    private companion object {
        const val TAG = "Isolation"
    }
}
