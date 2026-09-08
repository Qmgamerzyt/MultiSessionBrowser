package app.multisession.browser

import android.app.Application
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs

class BrowserApp : Application() {

    lateinit var core: BrowserCore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode())
        if (BuildConfig.DEBUG) {
            // Chrome DevTools remote debugging (chrome://inspect) for debug builds only.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        core = BrowserCore(this)
        AppLog.i(TAG, "Application created (debug=${BuildConfig.DEBUG})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
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
