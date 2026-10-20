package com.pixelforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PixelDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFFAB40),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

private val PixelLightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EA),
    secondary = Color(0xFF00B8D4),
    tertiary = Color(0xFFFF6D00),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121)
)

@Composable
fun PixelForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PixelDarkColorScheme else PixelLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
