package app.multisession.browser.engine

import android.app.Application
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import app.multisession.browser.BuildConfig
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.Prefs
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import java.io.File
import kotlin.system.exitProcess

/**
 * Owns the single [GeckoRuntime] of the process (Mozilla Gecko: the Firefox engine).
 *
 * - Exactly ONE runtime per process, created on the main thread and alive until the process dies.
 * - Global engine preferences (JavaScript, cookies, tracking protection, colour scheme, zoom ...)
 *   are applied here from [Prefs]; per-session/per-tab settings live in [SessionFactory].
 * - Gecko-level preferences that GeckoRuntimeSettings does not expose (WebRTC device handling) are passed
 *   through a GeckoView configuration file written by [writeConfigFile] (GeckoRuntimeSettings.configFilePath;
 *   read in release builds too because the path is set explicitly).
 * - Site data is partitioned by GeckoSession *contextId* (see [app.multisession.browser.session.SessionIsolation]);
 *   this class exposes the [StorageController] operations used to clear it.
 */
class GeckoEngine(private val app: Application) {

    @Volatile private var _runtime: GeckoRuntime? = null

    /** Web Notification API -> Android notifications (registered on the runtime in [create]). */
    val notifications = WebNotifications(app)

    val isCreated: Boolean get() = _runtime != null

    /** The runtime if it has been created, without creating it. */
    val runtimeOrNull: GeckoRuntime? get() = _runtime

    /**
     * Starts the engine now (main process only, from Application.onCreate). GeckoRuntime.create is cheap on
     * the main thread - Gecko boots on its own thread - so the first page load no longer waits seconds for
     * the engine while showing a blank, "stuck" tab.
     */
    fun warmUp() {
        if (_runtime != null) return
        Handler(Looper.getMainLooper()).post {
            try {
                runtime
            } catch (t: Throwable) {
                AppLog.e(TAG, "GeckoRuntime warm-up failed", t)
            }
        }
    }

    /** The process-wide runtime, created on first access (main thread). */
    val runtime: GeckoRuntime
        get() = _runtime ?: synchronized(this) { _runtime ?: create().also { _runtime = it } }

    private fun create(): GeckoRuntime {
        val start = System.currentTimeMillis()
        val builder = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(Prefs.javaScriptEnabled)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)   // about:debugging / DevTools over USB in debug builds only
            // Web console -> logcat: always in debug builds, opt-in via Settings -> Diagnostics in
            // release (v2.1.10: capturing a site's CSP/JS errors is how Plan 2 diagnoses quest-claim
            // and other page-policy failures that are invisible otherwise).
            .consoleOutput(BuildConfig.DEBUG || Prefs.consoleLog)
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
        writeConfigFile()?.let { builder.configFilePath(it.absolutePath) }
        val rt = GeckoRuntime.create(app, builder.build())
        rt.setWebNotificationDelegate(notifications)   // without it Gecko drops every web notification silently
        rt.delegate = GeckoRuntime.Delegate {
            // The runtime cannot be recreated in this process once it has shut down.
            AppLog.w(TAG, "GeckoRuntime shut down - exiting process")
            exitProcess(0)
        }
        AppLog.i(TAG, "GeckoRuntime created in ${System.currentTimeMillis() - start} ms (GeckoView ${geckoViewVersion()})")
        return rt
    }

    /**
     * WebRTC / media device preferences (v2.0.2, "mobile mode reports no microphone / output"):
     *  - media.setsinkid.enabled: Firefox for Android ships this OFF, so enumerateDevices() never lists any
     *    "audiooutput" device and HTMLMediaElement.setSinkId is missing. Sites that build their device pickers from
     *    enumerateDevices() (Discord-like voice apps, WebRTC test pages) then report "no output device" - their desktop
     *    code paths mostly ignore outputs, which is why desktop mode looked fine. Enabling it exposes the outputs.
     *  - media.navigator.audio.full_duplex: capture + playback through one full-duplex cubeb stream (echo
     *    cancellation and stable mic input while audio plays); default on desktop, off on Android.
     *  - media.getusermedia.audio.max_channels / media.peerconnection.*: keep WebRTC available in every UA mode.
     * These are Gecko preferences, identical for mobile and desktop UA mode, so desktop mode is unaffected.
     */
    private fun writeConfigFile(): File? {
        val file = File(app.filesDir, "geckoview-config.yaml")
        return try {
            val prefs = buildString {
                appendLine("prefs:")
                if (Prefs.webrtcCompat) {
                    appendLine("  media.setsinkid.enabled: true")
                    appendLine("  media.navigator.audio.full_duplex: true")
                    appendLine("  media.getusermedia.audio.max_channels: 2")
                }
                appendLine("  media.peerconnection.enabled: true")
                appendLine("  media.navigator.enabled: true")
                appendLine("  media.navigator.permission.disabled: false")
                appendLine("  dom.webnotifications.enabled: true")
            }
            if (!file.exists() || file.readText() != prefs) file.writeText(prefs)
            file
        } catch (t: Throwable) {
            AppLog.w(TAG, "Could not write GeckoView config file", t); null
        }
    }

    /** Re-applies the user's global preferences (called after the Settings screen changes something). */
    fun applyGlobalSettings() {
        val rt = _runtime ?: return
        val s = rt.settings
        s.javaScriptEnabled = Prefs.javaScriptEnabled
        s.forceUserScalableEnabled = Prefs.zoomEnabled
        s.preferredColorScheme = colorScheme()
        s.consoleOutputEnabled = BuildConfig.DEBUG || Prefs.consoleLog
        s.contentBlocking.setSafeBrowsing(safeBrowsing())
        s.contentBlocking.setCookieBehavior(cookieBehavior())
        writeConfigFile()   // takes effect at the next process start (Gecko reads it once)
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

    // ------------------------------------------------------------------ site permissions (Gecko's permission manager)

    /**
     * Changes a permission Gecko has stored for the origin/contextId of [perm]. VALUE_PROMPT removes the
     * stored decision ("Ask"). The app-side SitePermissionStore is updated by the caller.
     */
    fun setSitePermission(perm: ContentPermission, value: Int) {
        val rt = _runtime ?: return
        try {
            rt.storageController.setPermission(perm, value)
        } catch (t: Throwable) {
            AppLog.w(TAG, "setPermission failed", t)
        }
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
