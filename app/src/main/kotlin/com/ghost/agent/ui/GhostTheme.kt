package com.ghost.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF17B98A)
private val AccentDark = Color(0xFF7CF5C4)
private val Danger = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF04150F),
    secondary = Color(0xFF9BA1A6),
    background = Color(0xFF0B0C0E),
    surface = Color(0xFF14161A),
    surfaceVariant = Color(0xFF1D2024),
    error = Danger,
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF5A6169),
    background = Color(0xFFF7F8F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF2),
    error = Danger,
)

@Composable
fun GhostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
