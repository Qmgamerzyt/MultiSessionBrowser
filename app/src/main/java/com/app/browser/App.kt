package com.app.browser

import android.app.Application
import com.app.browser.data.AppDatabase
import com.app.browser.data.initializeDatabase
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database
        initializeDatabase(this)
        
        // Initialize GeckoView runtime will be done in MainActivity
    }
}