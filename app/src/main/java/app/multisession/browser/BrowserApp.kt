package app.multisession.browser

import android.app.Application
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
        // The GeckoRuntime (Firefox engine) is created lazily by BrowserCore.engine on first web load;
        // remote debugging / console output are enabled there for debug builds only.
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
