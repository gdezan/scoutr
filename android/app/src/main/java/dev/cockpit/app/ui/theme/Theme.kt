package dev.cockpit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first palette (Radix-inspired, tuned for OLED screens).
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF1E3A66),
    onPrimaryContainer = Color(0xFFD3E5FF),
    secondary = Color(0xFF46A758),
    onSecondary = Color(0xFF06250F),
    tertiary = Color(0xFFF5A524),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF151A24),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1E2636),
    onSurfaceVariant = Color(0xFFA7B0C2),
    outline = Color(0xFF3A4357),
    error = Color(0xFFE5484D),
    onError = Color(0xFF3A0B0C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5FB8),
    secondary = Color(0xFF2F9E44),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF4C5567),
)

@Composable
fun CockpitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
