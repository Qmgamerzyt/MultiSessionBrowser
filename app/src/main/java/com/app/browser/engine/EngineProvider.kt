package com.app.browser.engine

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtensionController

class EngineProvider private constructor(private val context: Context) {
    
    private var runtime: GeckoRuntime? = null
    private var currentProfileDir: String? = null
    
    val webExtensionController: WebExtensionController by lazy {
        runtime?.webExtensionController ?: throw IllegalStateException("Runtime not initialized")
    }
    
    fun getOrCreateRuntime(profileDir: String): GeckoRuntime {
        if (runtime != null && currentProfileDir == profileDir) {
            return runtime!!
        }
        
        // Create new runtime with the specified profile directory
        val settings = GeckoRuntimeSettings.Builder(context)
            .profileDir(profileDir)
            .build()
        
        // Destroy old runtime if profile changed
        if (runtime != null && currentProfileDir != profileDir) {
            runtime!!.destroy()
            runtime = null
        }
        
        runtime = GeckoRuntime.create(context, settings)
        currentProfileDir = profileDir
        
        return runtime!!
    }
    
    fun getRuntime(): GeckoRuntime? = runtime
    
    fun destroyRuntime() {
        runtime?.destroy()
        runtime = null
        currentProfileDir = null
    }
    
    fun createSession(): GeckoSession {
        val rt = runtime ?: throw IllegalStateException("Runtime not initialized")
        return GeckoSession()
    }
    
    companion object {
        @Volatile private var INSTANCE: EngineProvider? = null
        
        fun getInstance(context: Context): EngineProvider {
            return INSTANCE ?: synchronized(this) {
                val instance = EngineProvider(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
        
        fun clearInstance() {
            INSTANCE?.destroyRuntime()
            INSTANCE = null
        }
    }
}