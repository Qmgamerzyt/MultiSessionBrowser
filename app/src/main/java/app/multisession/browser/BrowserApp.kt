package app.multisession.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs

class BrowserApp : Application() {

    lateinit var core: BrowserCore
        private set

    /** False in GeckoView's child processes (":tab0", ":gpu", ":socket", ...). */
    var isMainProcess: Boolean = true
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val processName = getProcessName()
        isMainProcess = processName == packageName
        if (!isMainProcess) {
            // GeckoView's content / GPU / socket child processes are Android services of this app, so this
            // Application class runs in each of them. They must NOT open the Room database, write
            // SharedPreferences (active session!) or build a BrowserCore: doing so raced the main process
            // (private sessions deleted, FK failures on tab writes) and delayed every content-process
            // start-up - which is what made page loads slow or look stuck.
            AppLog.i(TAG, "Child process $processName: skipping app initialisation")
            return
        }
        Prefs.init(this)
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode())
        core = BrowserCore(this)
        // Boot the Firefox engine immediately (asynchronously on Gecko's thread) instead of on the first
        // page load, so the first tab does not sit blank while Gecko initialises.
        core.engine.warmUp()
        AppLog.i(TAG, "Application created (debug=${BuildConfig.DEBUG})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess || !::core.isInitialized) return
        AppLog.d(TAG, "onTrimMemory level=$level")
        core.tabs.onTrimMemory(level)
    }

    companion object {
        private const val TAG = "App"
        lateinit var instance: BrowserApp
            private set

        fun core(): BrowserCore = instance.core
    }
}
