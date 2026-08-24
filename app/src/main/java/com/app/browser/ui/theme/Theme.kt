package com.app.browser.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    primaryContainer = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC5),
    secondaryContainer = Color(0xFF018786),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2C2C2C),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    outline = Color(0xFF404040)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF018786),
    secondaryContainer = Color(0xFF03DAC5),
    background = Color.White,
    surface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFFE0E0E0),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    outline = Color(0xFF757575)
)

@Composable
fun MultiSessionBrowserTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}