package com.app.browser.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.browser.data.AppDatabase
import com.app.browser.data.initializeDatabase
import com.app.browser.engine.EngineProvider
import com.app.browser.engine.ProfileManager
import com.app.browser.engine.StorageCleaner
import com.app.browser.extension.ExtensionManager
import com.app.browser.omnibox.OmniboxParser
import com.app.browser.omnibox.OmniboxViewModel
import com.app.browser.overlay.FloatingBallService
import com.app.browser.session.SessionManager
import com.app.browser.tab.TabManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material3.isSystemInDarkTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var engineProvider: EngineProvider
    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var storageCleaner: StorageCleaner
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var tabManager: TabManager
    @Inject lateinit var extensionManager: ExtensionManager
    
    private val omniboxViewModel: OmniboxViewModel by viewModel()
    
    private var currentSessionId: String? = null
    private var isToolbarHidden = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize database
        initializeDatabase(this)
        
        // Set theme
        val darkMode = db.settingDao().getValue("dark_mode") ?: "system"
        when (darkMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        // Initialize engine with default profile
        lifecycleScope.launch {
            val defaultSession = getOrCreateDefaultSession()
            val profileDir = profileManager.getProfileDir(defaultSession.id)
            engineProvider.getOrCreateRuntime(profileDir)
            extensionManager.initializeBuiltInExtensions()
        }
        
        setContent {
            MultiSessionBrowserTheme(darkTheme = isSystemInDarkTheme()) {
                Surface {
                    BrowserScreen(
                        sessionManager = sessionManager,
                        tabManager = tabManager,
                        extensionManager = extensionManager,
                        omniboxViewModel = omniboxViewModel,
                        onSessionSwitch = { sessionId ->
                            currentSessionId = sessionId
                            switchSession(sessionId)
                        },
                        onToolbarHidden = { hidden ->
                            isToolbarHidden = hidden
                            if (hidden) startFloatingBall()
                        },
                        onNewTab = { tabManager.openTab(currentSessionId!!) },
                        onTabSwitch = { tabId ->
                            tabManager.switchToTab(tabId)
                        },
                        onCloseTab = { tabId ->
                            tabManager.closeTab(tabId)
                        },
                        onRunScript = { scriptCode ->
                            // Run via JsRunner
                        }
                    )
                }
            }
        }
        
        // Register for toolbar restore broadcast
        registerReceiver(restoreToolbarReceiver, 
            androidx.core.content.ContextCompat.getMainExecutor(this),
            IntentFilter("com.app.browser.RESTORE_TOOLBAR"))
    }
    
    private suspend fun getOrCreateDefaultSession(): com.app.browser.session.Session {
        val sessions = sessionManager.getAllSessionsOnce()
        return if (sessions.isEmpty()) {
            sessionManager.createSession("Default")
        } else {
            sessions.first()
        }
    }
    
    private suspend fun switchSession(sessionId: String) {
        val sessionWithTabs = sessionManager.switchToSession(sessionId)
        currentSessionId = sessionId
        tabManager.restoreTabs(sessionId)
        // TODO: Update UI state
    }
    
    private fun startFloatingBall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                return
            }
        }
        
        val intent = Intent(this, FloatingBallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private val restoreToolbarReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isToolbarHidden = false
            // TODO: Update UI to show toolbar
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(restoreToolbarReceiver)
        tabManager.destroy()
        engineProvider.destroyRuntime()
    }
    
    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
    }
}