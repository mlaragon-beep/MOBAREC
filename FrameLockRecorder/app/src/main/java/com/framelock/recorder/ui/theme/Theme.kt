package com.framelock.recorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FrameLockDarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF06131F),
    secondary = Color(0xFF22C55E),
    tertiary = Color(0xFFF59E0B),
    error = Color(0xFFEF4444),
    background = Color(0xFF0B0F17),
    surface = Color(0xFF121824),
    surfaceVariant = Color(0xFF1A2233),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF283548)
)

@Composable
fun FrameLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrameLockDarkColors,
        content = content
    )
}
