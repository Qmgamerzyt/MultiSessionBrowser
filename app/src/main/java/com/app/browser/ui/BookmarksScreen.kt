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
import com.app.browser.data.entities.BookmarkEntity
import com.app.browser.ui.theme.Typography

@Composable
fun BookmarksScreen(
    db: AppDatabase,
    sessionId: String,
    onDismiss: () -> Unit
) {
    val bookmarks by db.bookmarkDao().getBySessionId(sessionId).collectAsState(initial = emptyList())
    
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
                Text(stringResource(com.app.browser.R.string.bookmarks), style = Typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { /* Add bookmark */ }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_add),
                            contentDescription = "Add Bookmark"
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
            
            // Bookmarks list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        onClick = { /* Navigate to URL */ }
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarkItem(
    bookmark: BookmarkEntity,
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
                Text(bookmark.title, style = Typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.TextOverflow.Ellipsis)
                IconButton(onClick = { /* Delete */ }) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_delete),
                        contentDescription = "Delete"
                    )
                }
            )
            Text(bookmark.url, style = Typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = androidx.compose.ui.text.TextOverflow.Ellipsis)
        }
    }
}