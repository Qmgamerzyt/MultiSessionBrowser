package com.app.browser.ui

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.keyboard.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ViewFactory
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.UUID

@Composable
fun BrowserScreen(
    sessionManager: SessionManager,
    tabManager: TabManager,
    extensionManager: ExtensionManager,
    omniboxViewModel: OmniboxViewModel,
    onSessionSwitch: (String) -> Unit,
    onToolbarHidden: (Boolean) -> Unit,
    onNewTab: () -> Unit,
    onTabSwitch: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRunScript: (String) -> Unit
) {
    val activeSession = sessionManager.getCurrentSession()
    val tabs = remember { mutableStateOf<List<Tab>>(emptyList()) }
    val activeTabId = remember { mutableStateOf<String?>(null) }
    val geckoSession = remember { mutableStateOf<GeckoSession?>(null) }
    val showTabSwitcher = remember { mutableStateOf(false) }
    val showSessionSwitcher = remember { mutableStateOf(false) }
    val showExtensionManager = remember { mutableStateOf(false) }
    val isToolbarHidden = remember { mutableStateOf(false) }
    
    // Load tabs for active session
    LaunchedEffect(activeSession?.id) {
        activeSession?.id?.let { sessionId ->
            lifecycleScope.launch {
                val sessionTabs = tabManager.getTabs(sessionId)
                tabs.value = sessionTabs
                if (sessionTabs.isNotEmpty()) {
                    val firstTab = sessionTabs.first()
                    activeTabId.value = firstTab.id
                    geckoSession.value = tabManager.getGeckoSession(firstTab.id)
                }
            }
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        if (!isToolbarHidden.value) {
            Toolbar(
                session = activeSession,
                omniboxViewModel = omniboxViewModel,
                onBack = { geckoSession.value?.goBack() },
                onForward = { geckoSession.value?.goForward() },
                onReload = { geckoSession.value?.reload() },
                onNewTab = onNewTab,
                onTabSwitcher = { showTabSwitcher.value = true },
                onSessionSwitcher = { showSessionSwitcher.value = true },
                onExtensionManager = { showExtensionManager.value = true },
                onHideToolbar = { isToolbarHidden.value = true; onToolbarHidden(true) },
                onUrlSubmit = { url ->
                    geckoSession.value?.loadUrl(url)
                },
                onRunScript = onRunScript
            )
        }
        
        // GeckoView or Tab Switcher
        Box(modifier = Modifier.fillMaxSize()) {
            if (showTabSwitcher.value) {
                TabSwitcherScreen(
                    tabs = tabs.value,
                    activeTabId = activeTabId.value,
                    onTabSelect = { tabId ->
                        onTabSwitch(tabId)
                        showTabSwitcher.value = false
                    },
                    onCloseTab = { tabId ->
                        onCloseTab(tabId)
                        tabs.value = tabs.value.filter { it.id != tabId }
                    },
                    onNewTab = { onNewTab(); showTabSwitcher.value = false },
                    onDismiss = { showTabSwitcher.value = false }
                )
            } else if (showSessionSwitcher.value) {
                SessionSwitcherScreen(
                    sessions = sessionManager.getAllSessionsOnce(),
                    activeSessionId = activeSession?.id,
                    onSessionSelect = { sessionId ->
                        onSessionSwitch(sessionId)
                        showSessionSwitcher.value = false
                    },
                    onNewSession = { /* TODO */ },
                    onDismiss = { showSessionSwitcher.value = false }
                )
            } else if (showExtensionManager.value) {
                ExtensionManagerScreen(
                    extensionManager = extensionManager,
                    sessionManager = sessionManager,
                    activeSessionId = activeSession?.id,
                    onDismiss = { showExtensionManager.value = false }
                )
            } else {
                // GeckoView
                GeckoViewContainer(
                    session = geckoSession.value,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun GeckoViewContainer(
    session: GeckoSession?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    AndroidView(
        factory = { ctx ->
            GeckoView(ctx).apply {
                session?.let { setGeckoSession(it) }
            }
        },
        update = { view ->
            session?.let { 
                if (view.geckoSession != it) {
                    view.geckoSession = it
                }
            }
        },
        modifier = modifier
    )
}

class GeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : org.mozilla.geckoview.GeckoView(context, attrs, defStyleAttr) {
    
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
}

@Composable
fun Toolbar(
    session: Session?,
    omniboxViewModel: OmniboxViewModel,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onTabSwitcher: () -> Unit,
    onSessionSwitcher: () -> Unit,
    onExtensionManager: () -> Unit,
    onHideToolbar: () -> Unit,
    onUrlSubmit: (String) -> Unit,
    onRunScript: (String) -> Unit
) {
    val text = omniboxViewModel.input.value
    var urlText by remember { mutableStateOf(text) }
    
    androidx.compose.material3.TopAppBar(
        title = {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_media_rew),
                        contentDescription = "Back"
                    )
                }
                IconButton(onClick = onForward) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_media_ff),
                        contentDescription = "Forward"
                    )
                }
                IconButton(onClick = onReload) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_popup_sync),
                        contentDescription = "Reload"
                    )
                }
                
                // Address bar
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    TextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text(stringResource(com.app.browser.R.string.search_or_type_url)) },
                        keyboardOptions = KeyboardOptions.Default,
                        onKeyboardAction = { action ->
                            if (action == androidx.compose.ui.text.input.ImeAction.Go ||
                                action == androidx.compose.ui.text.input.ImeAction.Done ||
                                action == androidx.compose.ui.text.input.ImeAction.Search) {
                                val action = omniboxViewModel.onSubmit("https://duckduckgo.com/?q=%s")
                                when (action) {
                                    is OmniboxAction.Navigate -> onUrlSubmit(action.url)
                                    is OmniboxAction.RunScript -> onRunScript(action.code)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                IconButton(onClick = onNewTab) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_add),
                        contentDescription = "New Tab"
                    )
                }
                IconButton(onClick = onTabSwitcher) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_revert),
                        contentDescription = "Tabs"
                    )
                }
                IconButton(onClick = onSessionSwitcher) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_agenda),
                        contentDescription = "Sessions"
                    )
                }
                IconButton(onClick = onExtensionManager) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_manage),
                        contentDescription = "Extensions"
                    )
                }
                IconButton(onClick = onHideToolbar) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Hide Toolbar"
                    )
                }
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
        )
    )
}