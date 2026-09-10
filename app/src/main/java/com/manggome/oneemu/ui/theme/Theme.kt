package com.manggome.oneemu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Visual language shared by every screen: dark charcoal surfaces like My Boy / melonDS with a
 * mint accent. Emulator overlays reuse [Accent] and [Surface] so the pad matches the library.
 */
object OneEmuColors {
    val Accent = Color(0xFF7FD1C8)
    val AccentDark = Color(0xFF4FA89F)
    val Background = Color(0xFF1E1E1E)
    val Surface = Color(0xFF2B2B2B)
    val SurfaceHigh = Color(0xFF363636)
    val OnSurface = Color(0xFFF2F2F2)
    val OnSurfaceMuted = Color(0xFFA5A5A5)
    val Divider = Color(0xFF444444)
    val Danger = Color(0xFFE06060)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = OneEmuColors.Accent,
    onPrimary = Color(0xFF10201E),
    primaryContainer = OneEmuColors.AccentDark,
    onPrimaryContainer = Color.White,
    secondary = OneEmuColors.Accent,
    onSecondary = Color(0xFF10201E),
    background = OneEmuColors.Background,
    onBackground = OneEmuColors.OnSurface,
    surface = OneEmuColors.Surface,
    onSurface = OneEmuColors.OnSurface,
    surfaceVariant = OneEmuColors.SurfaceHigh,
    onSurfaceVariant = OneEmuColors.OnSurfaceMuted,
    surfaceContainer = OneEmuColors.Surface,
    surfaceContainerHigh = OneEmuColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF404040),
    outline = OneEmuColors.Divider,
    outlineVariant = OneEmuColors.Divider,
    error = OneEmuColors.Danger,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = OneEmuColors.AccentDark,
    secondary = OneEmuColors.AccentDark,
    background = Color(0xFFF4F4F4),
    surface = Color.White,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun OneEmuTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme || isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
