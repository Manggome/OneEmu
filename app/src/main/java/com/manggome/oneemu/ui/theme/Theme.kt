package com.manggome.oneemu.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.manggome.oneemu.OneEmuApp

/**
 * Colours of the active theme, for code that needs more than [MaterialTheme.colorScheme]
 * (pad overlays, in-game menus). Read through [OneEmuTheme.colors] inside composables.
 */
val LocalOneEmuColors = staticCompositionLocalOf { OneEmuColors.defaultPalette }

/**
 * Legacy static access kept so existing call sites (`OneEmuColors.Accent`, ...) keep compiling.
 * The properties are backed by snapshot state and mirror the palette installed by [OneEmuTheme],
 * so composables and draw scopes that read them recompose/redraw when the theme changes.
 * Non-composable code that copies a value at init time (e.g. an `object` field) keeps the colour
 * that was current at that moment.
 */
object OneEmuColors {
    /** Charcoal + mint: the look the app shipped with, used before any theme has been applied. */
    val defaultPalette: OneEmuPalette = OneEmuPalette(
        accent = Color(0xFF7FD1C8),
        accentDark = Color(0xFF4FA89F),
        background = Color(0xFF1E1E1E),
        surface = Color(0xFF2B2B2B),
        surfaceHigh = Color(0xFF363636),
        onSurface = Color(0xFFF2F2F2),
        onSurfaceMuted = Color(0xFFA5A5A5),
        divider = Color(0xFF444444),
        danger = Color(0xFFE06060),
        isDark = true,
    )

    private val state = mutableStateOf(defaultPalette)

    /** Palette currently installed by [OneEmuTheme]. */
    var current: OneEmuPalette
        get() = state.value
        // Written from inside OneEmuTheme's composition; skip read observation so the compare does
        // not register as a backwards write and force an extra recomposition.
        internal set(value) {
            Snapshot.withoutReadObservation { if (state.value != value) state.value = value }
        }

    val Accent: Color get() = current.accent
    val AccentDark: Color get() = current.accentDark
    val Background: Color get() = current.background
    val Surface: Color get() = current.surface
    val SurfaceHigh: Color get() = current.surfaceHigh
    val OnSurface: Color get() = current.onSurface
    val OnSurfaceMuted: Color get() = current.onSurfaceMuted
    val Divider: Color get() = current.divider
    val Danger: Color get() = current.danger
}

/** Companion-style accessors for the theme, e.g. `OneEmuTheme.colors.accent`. */
object OneEmuTheme {
    val colors: OneEmuPalette
        @Composable @ReadOnlyComposable get() = LocalOneEmuColors.current
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * App theme. Reads the user's preset / mode / AMOLED / dynamic choice from Settings
 * (keys in [ThemeSettings]) and applies it to Material, [LocalOneEmuColors], the legacy
 * [OneEmuColors] object and the system bar icon colours.
 *
 * [darkTheme] is kept for source compatibility; when non-null it forces light/dark regardless
 * of the stored mode (the emulator passes nothing and follows the user's choice).
 */
@Composable
fun OneEmuTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val settings = OneEmuApp.get().settings
    val flow = remember(settings) { ThemeSettings.observe(settings) }
    val choice by flow.collectAsState(initial = ThemeSettings.cached ?: ThemeChoice())
    OneEmuTheme(choice = choice, darkOverride = darkTheme, content = content)
}

/**
 * Theme driven by an explicit [choice] instead of Settings. The picker uses this to render
 * preview cards; production code should call the parameterless overload.
 */
@Composable
fun OneEmuTheme(choice: ThemeChoice, darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = darkOverride ?: when (choice.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val resolved = remember(choice, dark, context) { choice.effectivePreset.resolve(context, dark, choice.amoled) }
    // Install the palette for legacy static readers before children compose.
    OneEmuColors.current = resolved.palette

    SystemBarSync(resolved)

    CompositionLocalProvider(LocalOneEmuColors provides resolved.palette) {
        MaterialTheme(
            colorScheme = resolved.scheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Resolve a theme for previews without installing it (does not touch [OneEmuColors]). */
@Composable
fun rememberResolvedTheme(preset: ThemePreset, dark: Boolean, amoled: Boolean): ResolvedTheme {
    val context = LocalContext.current
    return remember(preset, dark, amoled, context) { preset.resolve(context, dark, amoled) }
}

/**
 * Edge-to-edge is on, so bars are transparent and only the icon appearance needs to follow the
 * theme. Below API 29 the navigation bar cannot be transparent, so paint it with the background.
 */
@Composable
private fun SystemBarSync(resolved: ResolvedTheme) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !resolved.isDark
        controller.isAppearanceLightNavigationBars = !resolved.isDark
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = resolved.scheme.background.toArgb()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
