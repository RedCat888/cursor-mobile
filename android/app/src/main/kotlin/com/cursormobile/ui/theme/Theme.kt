package com.cursormobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Cursor-style palette. Deep neutral background, single accent.
 * We don't follow Material You — the IDE itself doesn't, so neither do we.
 */
private val CursorDark = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    onPrimary = Color(0xFF13183A),
    primaryContainer = Color(0xFF2A3170),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFF9FE0C9),
    onSecondary = Color(0xFF0E2A20),
    tertiary = Color(0xFFE5A87C),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFE9EAF0),
    surface = Color(0xFF101116),
    onSurface = Color(0xFFE9EAF0),
    surfaceVariant = Color(0xFF1A1B22),
    onSurfaceVariant = Color(0xFFB6B8C2),
    outline = Color(0xFF2A2B33),
    outlineVariant = Color(0xFF1F2027),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF330000),
)

private val CursorLight = lightColorScheme(
    primary = Color(0xFF3D4FCC),
    onPrimary = Color.White,
    background = Color(0xFFF7F7FA),
    surface = Color.White,
    onSurface = Color(0xFF0B0B0F),
)

@Composable
fun CursorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) CursorDark else CursorLight
    MaterialTheme(
        colorScheme = colors,
        typography = CursorTypography,
        shapes = CursorShapes,
        content = content,
    )
}
