package com.app.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.app.browser.data.AppDatabase
import com.app.browser.engine.EngineProvider
import com.app.browser.ui.theme.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    db: AppDatabase,
    engineProvider: EngineProvider,
    onDismiss: () -> Unit
) {
    val darkMode by remember { mutableStateOf("system") }
    val javascriptEnabled by remember { mutableStateOf(true) }
    val homepage by remember { mutableStateOf("https://duckduckgo.com") }
    val searchEngine by remember { mutableStateOf("https://duckduckgo.com/?q=%s") }
    
    // Load settings
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.Dispatchers.IO.asCoroutineDispatcher().launch {
            darkMode = db.settingDao().getValue("dark_mode") ?: "system"
            javascriptEnabled = db.settingDao().getValue("javascript_enabled")?.toBoolean() ?: true
            homepage = db.settingDao().getValue("homepage") ?: "https://duckduckgo.com"
            searchEngine = db.settingDao().getValue("search_engine") ?: "https://duckduckgo.com/?q=%s"
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = Typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Close"
                    )
                }
            }
            
            // Settings list
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Dark mode
                SettingCard(
                    title = "Dark Mode",
                    subtitle = "Choose color scheme"
                ) {
                    androidx.compose.material3.DropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("System", "Light", "Dark").forEach { option ->
                            androidx.compose.material3.DropdownMenuItem(
                                onClick = {
                                    val value = option.lowercase()
                                    darkMode = value
                                    db.settingDao().insert(com.app.browser.data.entities.SettingEntity("dark_mode", value))
                                    // Apply theme
                                },
                                text = { Text(option) }
                            )
                        }
                    }
                }
                
                // JavaScript
                SettingCard(
                    title = "JavaScript",
                    subtitle = "Enable/disable JavaScript"
                ) {
                    Switch(
                        checked = javascriptEnabled,
                        onCheckedChange = { enabled ->
                            javascriptEnabled = enabled
                            db.settingDao().insert(com.app.browser.data.entities.SettingEntity("javascript_enabled", enabled.toString()))
                        }
                    )
                }
                
                // Homepage
                SettingCard(
                    title = "Homepage",
                    subtitle = "Set your homepage URL"
                ) {
                    androidx.compose.material3.TextField(
                        value = homepage,
                        onValueChange = { homepage = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Homepage URL") }
                    )
                }
                
                // Search engine
                SettingCard(
                    title = "Search Engine",
                    subtitle = "Default search engine (use %s for query)"
                ) {
                    androidx.compose.material3.TextField(
                        value = searchEngine,
                        onValueChange = { searchEngine = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search Engine URL") }
                    )
                }
                
                Divider()
                
                // Clear data
                SettingCard(
                    title = "Clear Browsing Data",
                    subtitle = "Clear history, cookies, cache for current session"
                ) {
                    androidx.compose.material3.Button(
                        onClick = { /* TODO: Clear data */ },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFCF6679)
                        )
                    ) {
                        Text("Clear Data")
                    }
                }
                
                // About
                SettingCard(
                    title = "About",
                    subtitle = "Multi-Session Browser v1.0"
                ) {
                    Text("Built with GeckoView & Jetpack Compose", style = Typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SettingCard(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = title, style = Typography.titleMedium)
                Text(text = subtitle, style = Typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            trailing()
        }
    )
}