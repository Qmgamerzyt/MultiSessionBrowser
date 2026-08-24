package com.app.browser.engine

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController

class StorageCleaner(private val context: Context) {
    
    fun clearSessionData(runtime: GeckoRuntime, sessionId: String) {
        val storageController = runtime.storageController
        
        // Clear all web storage for the session
        storageController.clearAll().thenAccept {
            // Storage cleared
        }
        
        // Also clear cookies specifically
        storageController.clearCookies().thenAccept {
            // Cookies cleared
        }
        
        // Clear localStorage, sessionStorage, IndexedDB
        storageController.clearLocalStorage().thenAccept {
            // LocalStorage cleared
        }
    }
    
    fun clearAllData(runtime: GeckoRuntime) {
        val storageController = runtime.storageController
        storageController.clearAll().thenAccept {
            // All cleared
        }
    }
}