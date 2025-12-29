package com.vndict.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * VNDict dark theme colors.
 * Background matches the overlay popup for visual consistency.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2196F3),              // Blue (default accent)
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1976D2),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF018786),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF5722),             // Orange (matches cursor dot)
    onTertiary = Color.White,
    background = Color(0xFF121215),           // Near-black (matches overlay popup)
    onBackground = Color.White,
    surface = Color(0xFF121215),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3D3D42),
    outlineVariant = Color(0xFF2A2A2E),
)

/**
 * Create a dark color scheme with a custom accent color.
 */
fun createColorScheme(accentColor: Color) = darkColorScheme(
    primary = accentColor,
    onPrimary = Color.White,
    primaryContainer = accentColor.copy(alpha = 0.7f),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF018786),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF5722),
    onTertiary = Color.White,
    background = Color(0xFF121215),
    onBackground = Color.White,
    surface = Color(0xFF121215),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3D3D42),
    outlineVariant = Color(0xFF2A2A2E),
)

/**
 * VNDict app theme with dark mode by default.
 */
@Composable
fun VNDictTheme(
    accentColor: Color = Color(0xFF2196F3),
    content: @Composable () -> Unit
) {
    val colorScheme = createColorScheme(accentColor)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
