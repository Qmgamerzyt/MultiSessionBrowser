package com.app.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.browser.extension.ExtensionEntity
import com.app.browser.extension.ExtensionManager
import com.app.browser.extension.ExtensionState
import com.app.browser.session.SessionManager
import com.app.browser.ui.theme.Typography

@Composable
fun ExtensionManagerScreen(
    extensionManager: ExtensionManager,
    sessionManager: SessionManager,
    activeSessionId: String?,
    onDismiss: () -> Unit
) {
    val extensions by extensionManager.getInstalledExtensions().collectAsState(initial = emptyList())
    val sessions by sessionManager.getAllSessions().collectAsState(initial = emptyList())
    val matrix = remember { mutableStateOf<Map<String, Map<String, ExtensionState>>>(emptyMap()) }
    val showInstallDialog = remember { mutableStateOf(false) }
    val installInput = remember { mutableStateOf("") }
    val installType = remember { mutableStateOf(0) } // 0 = AMO, 1 = File, 2 = URL
    
    // Load matrix
    androidx.compose.runtime.LaunchedEffect(extensions, sessions) {
        // TODO: Load matrix from ExtensionSessionMatrix
    }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background dim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
        )
        
        // Extension manager card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(com.app.browser.R.string.extensions),
                        style = Typography.titleLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { showInstallDialog.value = true }) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_add),
                                contentDescription = "Install Extension"
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                                contentDescription = "Close"
                            )
                        }
                    }
                }
                
                // Extension matrix
                if (extensions.isNotEmpty() && sessions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Header row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Extension",
                                style = Typography.labelMedium,
                                modifier = Modifier.weight(2f)
                            )
                            sessions.forEach { session ->
                                Text(
                                    text = session.name,
                                    style = Typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text.TextAlign.Center
                                )
                            }
                        }
                        
                        // Extension rows
                        extensions.forEach { ext ->
                            ExtensionRow(
                                extension = ext,
                                sessions = sessions,
                                matrix = matrix.value,
                                onStateChange = { extId, sessionId, state ->
                                    // TODO: Update matrix
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No extensions installed",
                        style = Typography.bodyMedium,
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    )
                }
            }
        }
        
        // Install dialog
        if (showInstallDialog.value) {
            InstallExtensionDialog(
                onDismiss = { showInstallDialog.value = false },
                onInstall = { url, type ->
                    showInstallDialog.value = false
                    // TODO: Call extensionManager.installFromAMO/installFromFile/installFromUrl
                }
            )
        }
    }
}

@Composable
fun ExtensionRow(
    extension: ExtensionEntity,
    sessions: List<com.app.browser.session.Session>,
    matrix: Map<String, Map<String, ExtensionState>>,
    onStateChange: (String, String, ExtensionState) -> Unit
) {
    val extStates = matrix[extension.id] ?: emptyMap()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Extension icon and name
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon placeholder
                Box(
                    modifier = Modifier.size(24.dp)
                        .background(Color.Gray)
                        .clip(RoundedCornerShape(8.dp))
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = extension.name,
                    style = Typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.TextOverflow.Ellipsis
                )
            }
        }
        
        // State selectors for each session
        sessions.forEach { session ->
            val state = extStates[session.id] ?: ExtensionState.AUTO
            StateSelector(
                currentState = state,
                onChange = { newState ->
                    onStateChange(extension.id, session.id, newState)
                }
            )
        }
    }
}

@Composable
fun StateSelector(
    currentState: ExtensionState,
    onChange: (ExtensionState) -> Unit
) {
    val states = listOf(ExtensionState.AUTO, ExtensionState.OFF, ExtensionState.MANUAL)
    val labels = mapOf(
        ExtensionState.AUTO to "AUTO",
        ExtensionState.OFF to "OFF",
        ExtensionState.MANUAL to "MANUAL"
    )
    
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        states.forEach { state ->
            val isSelected = state == currentState
            androidx.compose.material3.TextButton(
                onClick = { onChange(state) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(horizontal = 4.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = if (isSelected) 
                        androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer 
                        else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = labels[state] ?: state.name,
                    style = Typography.labelSmall,
                    color = if (isSelected)
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                        else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InstallExtensionDialog(
    onDismiss: () -> Unit,
    onInstall: (String, Int) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var installType by remember { mutableStateOf(0) }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Install Extension", style = Typography.titleLarge)
            
            // Install type selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("AMO", "File", "URL").forEachIndexed { index, label ->
                    val isSelected = installType == index
                    androidx.compose.material3.TextButton(
                        onClick = { installType = index },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected)
                                androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(label, color = if (isSelected)
                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                            else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            
            // Input field
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(when (installType) {
                    0 -> "addons.mozilla.org URL or search term"
                    1 -> "Select .xpi file"
                    else -> ".xpi URL"
                }) },
                modifier = Modifier.fillMaxWidth()
            )
            
            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                androidx.compose.material3.Button(
                    onClick = { onInstall(input, installType) },
                    enabled = input.isNotBlank()
                ) {
                    Text("Install")
                }
            }
        }
    }
}