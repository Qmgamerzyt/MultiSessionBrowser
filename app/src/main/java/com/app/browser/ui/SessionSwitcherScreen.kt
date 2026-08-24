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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.browser.session.Session
import com.app.browser.ui.theme.Typography

@Composable
fun SessionSwitcherScreen(
    sessions: List<Session>,
    activeSessionId: String?,
    onSessionSelect: (String) -> Unit,
    onNewSession: () -> Unit,
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
        
        // Session list card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
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
                        text = stringResource(com.app.browser.R.string.sessions),
                        style = Typography.titleLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onNewSession) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_add),
                                contentDescription = "New Session"
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
                
                // Session list
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    sessions.forEach { session ->
                        SessionCard(
                            session = session,
                            isActive = session.id == activeSessionId,
                            onClick = { onSessionSelect(session.id) },
                            onLongClick = { /* TODO: Show rename/delete menu */ }
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SessionCard(
    session: Session,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .fillMaxWidth(),
        onClick = onClick,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = if (isActive) 8.dp else 2.dp
        ),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isActive) session.color.copy(alpha = 0.2f) 
                else androidx.compose.material3.MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(session.color)
                    .clip(RoundedCornerShape(6.dp))
            )
            
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
            
            // Session info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = Typography.titleMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Session ${session.id.take(8)}",
                    style = Typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            // Active indicator
            if (isActive) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.checkbox_on_background),
                    contentDescription = "Active",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}