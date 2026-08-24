package com.app.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.browser.data.AppDatabase
import com.app.browser.data.entities.DownloadEntity
import com.app.browser.ui.theme.Typography
import kotlinx.coroutines.flow.Flow

@Composable
fun DownloadsScreen(
    db: AppDatabase,
    onDismiss: () -> Unit
) {
    val downloads: Flow<List<DownloadEntity>> = db.downloadDao().getBySessionId("global")
    val downloadsList by downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    
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
                Text(stringResource(com.app.browser.R.string.downloads), style = Typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Close"
                    )
                }
            }
            
            // Downloads list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloadsList) { download ->
                    DownloadItem(
                        download = download,
                        onClick = { /* Open file */ }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadItem(
    download: DownloadEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(download.fileName, style = Typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.TextOverflow.Ellipsis)
                Text("${(download.progress * 100).toInt()}%", style = Typography.bodySmall)
            }
            
            // Progress bar
            LinearProgressIndicator(
                progress = download.progress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}