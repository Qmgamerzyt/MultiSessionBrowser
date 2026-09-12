package app.multisession.browser.engine

import android.app.Application
import android.content.res.Configuration
import app.multisession.browser.BuildConfig
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.Prefs
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import kotlin.system.exitProcess

/**
 * Owns the single [GeckoRuntime] of the process (Mozilla Gecko: the Firefox engine).
 *
 * - Exactly ONE runtime per process. It is created lazily on first use (the start page is native
 *   and does not need Gecko), on the main thread, and lives until the process dies.
 * - Global engine preferences (JavaScript, cookies, tracking protection, colour scheme, zoom ...)
 *   are applied here from [Prefs]; per-session/per-tab settings live in [SessionFactory].
 * - Site data is partitioned by GeckoSession *contextId* (see [app.multisession.browser.session.SessionIsolation]);
 *   this class exposes the [StorageController] operations used to clear it.
 */
class GeckoEngine(private val app: Application) {

    @Volatile private var _runtime: GeckoRuntime? = null

    val isCreated: Boolean get() = _runtime != null

    /** The process-wide runtime, created on first access (main thread). */
    val runtime: GeckoRuntime
        get() = _runtime ?: synchronized(this) { _runtime ?: create().also { _runtime = it } }

    private fun create(): GeckoRuntime {
        val start = System.currentTimeMillis()
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(Prefs.javaScriptEnabled)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)   // about:debugging / DevTools over USB in debug builds only
            .consoleOutput(BuildConfig.DEBUG)             // web console -> logcat in debug builds only
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(colorScheme())
            .forceUserScalableEnabled(Prefs.zoomEnabled)
            .loginAutofillEnabled(false)                  // this app never collects or stores credentials
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .safeBrowsing(safeBrowsing())
                    .cookieBehavior(cookieBehavior())
                    .build()
            )
            .build()
        val rt = GeckoRuntime.create(app, settings)
        rt.delegate = GeckoRuntime.Delegate {
            // The runtime cannot be recreated in this process once it has shut down.
            AppLog.w(TAG, "GeckoRuntime shut down - exiting process")
            exitProcess(0)
        }
        AppLog.i(TAG, "GeckoRuntime created in ${System.currentTimeMillis() - start} ms (GeckoView ${geckoViewVersion()})")
        return rt
    }

    /** Re-applies the user's global preferences (called after the Settings screen changes something). */
    fun applyGlobalSettings() {
        val rt = _runtime ?: return
        val s = rt.settings
        s.javaScriptEnabled = Prefs.javaScriptEnabled
        s.forceUserScalableEnabled = Prefs.zoomEnabled
        s.preferredColorScheme = colorScheme()
        s.contentBlocking.setSafeBrowsing(safeBrowsing())
        s.contentBlocking.setCookieBehavior(cookieBehavior())
    }

    /** Called from Activity.onConfigurationChanged so Gecko re-evaluates the (system) colour scheme / orientation. */
    fun onConfigurationChanged() {
        val rt = _runtime ?: return
        rt.settings.preferredColorScheme = colorScheme()
        rt.orientationChanged()
    }

    // ------------------------------------------------------------------ storage

    /**
     * Deletes ALL site data (cookies, localStorage, IndexedDB, service workers, cache entries,
     * permissions) that belongs to one session context. Other contexts are untouched.
     */
    fun clearSessionContext(contextId: String) {
        val rt = runtime   // creates the runtime if needed: on-disk data of the context must really be removed
        try {
            rt.storageController.clearDataForSessionContext(contextId)
            AppLog.i(TAG, "Cleared site data for context ${contextId.take(8)}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "clearDataForSessionContext failed", t)
        }
    }

    /** Clears the HTTP + image caches. Gecko's cache is one shared store (not identity data), so this is global. */
    fun clearCaches(): GeckoResult<Void>? {
        val rt = _runtime ?: return null
        return rt.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
    }

    // ------------------------------------------------------------------ helpers

    private fun colorScheme(): Int = when (Prefs.nightMode()) {
        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        else -> {
            val night = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        }
    }

    private fun safeBrowsing(): Int =
        if (Prefs.safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE

    private fun cookieBehavior(): Int =
        if (Prefs.thirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_ALL
        else ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY

    companion object {
        private const val TAG = "GeckoEngine"

        /** Firefox/Gecko version compiled into the GeckoView AAR (e.g. "155.0"). */
        fun geckoViewVersion(): String = try {
            org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
        } catch (t: Throwable) {
            "unknown"
        }
    }
}
