package com.example.smartplantmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FE870),
    onPrimary = Color(0xFF193000),
    secondary = Color(0xFF9ED6A8),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF0B1512),
    onBackground = Color(0xFFE8F3EC),
    surface = Color(0xFF14231E),
    onSurface = Color(0xFFE8F3EC),
    surfaceVariant = Color(0xFF1D3029),
    onSurfaceVariant = Color(0xFFA8B7B0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3E6B1B),
    secondary = Color(0xFF426B48),
    tertiary = Color(0xFF8A4C00)
)

@Composable
fun SmartPlantMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
