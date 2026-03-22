package com.sysadmindoc.callshield.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// AMOLED Black + Catppuccin Mocha accents
val Black = Color(0xFF000000)
val Surface = Color(0xFF0A0A0A)
val SurfaceVariant = Color(0xFF1A1A1A)
val SurfaceBright = Color(0xFF252525)
val CatGreen = Color(0xFFA6E3A1)
val CatRed = Color(0xFFF38BA8)
val CatBlue = Color(0xFF89B4FA)
val CatYellow = Color(0xFFF9E2AF)
val CatMauve = Color(0xFFCBA6F7)
val CatPeach = Color(0xFFFAB387)
val CatText = Color(0xFFCDD6F4)
val CatSubtext = Color(0xFFBAC2DE)
val CatOverlay = Color(0xFF6C7086)

private val DarkColorScheme = darkColorScheme(
    primary = CatGreen,
    onPrimary = Black,
    primaryContainer = Color(0xFF1A3A1A),
    secondary = CatBlue,
    onSecondary = Black,
    secondaryContainer = Color(0xFF1A2A3A),
    tertiary = CatMauve,
    error = CatRed,
    onError = Black,
    background = Black,
    onBackground = CatText,
    surface = Surface,
    onSurface = CatText,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = CatSubtext,
    outline = CatOverlay,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222)
)

@Composable
fun CallShieldTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Black.toArgb()
            window.navigationBarColor = Black.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
