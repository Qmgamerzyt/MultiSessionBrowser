package com.app.browser.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingBallView(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(Color(0xFF3700B3), CircleShape)
            .clip(CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onTap,
            onLongClick = onLongPress,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_manage),
                contentDescription = "Floating ball",
                tint = Color.White
            )
        }
    }
}