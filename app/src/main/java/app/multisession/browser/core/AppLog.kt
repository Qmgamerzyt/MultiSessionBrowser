package app.multisession.browser.core

import android.util.Log
import app.multisession.browser.BuildConfig

/**
 * Development logging. Verbose logs are compiled out of release builds.
 * NEVER log cookies, tokens, passwords, form data or full request headers through this class.
 */
object AppLog {
    private const val PREFIX = "MSB."

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(PREFIX + tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(PREFIX + tag, msg, t)
    }
}
