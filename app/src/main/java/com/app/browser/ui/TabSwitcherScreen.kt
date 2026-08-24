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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.browser.tab.Tab
import com.app.browser.ui.theme.Typography
import com.coil.compose.AsyncImage
import com.coil.compose.requestBuilder

@Composable
fun TabSwitcherScreen(
    tabs: List<Tab>,
    activeTabId: String?,
    onTabSelect: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
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
        
        // Tab grid card
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
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
                        text = stringResource(com.app.browser.R.string.tabs),
                        style = Typography.titleLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onNewTab) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_add),
                                contentDescription = "New Tab"
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
                
                // Tab grid
                LazyVerticalGrid(
                    cells = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    items(tabs) { tab ->
                        TabCard(
                            tab = tab,
                            isActive = tab.id == activeTabId,
                            onClick = { onTabSelect(tab.id) },
                            onClose = { onCloseTab(tab.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TabCard(
    tab: Tab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .fillMaxWidth(),
        onClick = onClick,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 4.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(Color(0xFF2C2C2C))
            ) {
                // TODO: Show actual thumbnail from GeckoSession
                Text(
                    text = tab.title.take(20),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    style = Typography.bodyLarge,
                    color = Color.White
                )
            }
            
            // Favicon and title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.BottomStart)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favicon placeholder
                    Box(
                        modifier = Modifier.size(16.dp)
                            .background(Color.Gray)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Text(
                        text = tab.title,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.TextOverflow.Ellipsis,
                        style = Typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
            
            // Close button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Close tab",
                        tint = Color.White
                    )
                }
            }
            
            // Active indicator
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0xFFBB86FC))
                        .align(Alignment.BottomStart),
                    contentAlignment = Alignment.BottomStart
                )
            }
        }
    }
}