package com.manggome.oneemu.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.manggome.oneemu.R

/**
 * The handful of colours every OneEmu screen is built from. Presets declare one of these per
 * light/dark variant; [ColorScheme]s and [OneEmuPalette]s are derived from it.
 */
@Immutable
data class ThemeTones(
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val divider: Color,
    val danger: Color,
)

/** Colours exposed to feature code through [OneEmuTheme.colors] / legacy [OneEmuColors]. */
@Immutable
data class OneEmuPalette(
    val accent: Color,
    val accentDark: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val divider: Color,
    val danger: Color,
    val isDark: Boolean,
) {
    companion object {
        fun from(t: ThemeTones, dark: Boolean) = OneEmuPalette(
            accent = t.accent,
            accentDark = t.accentContainer,
            background = t.background,
            surface = t.surface,
            surfaceHigh = t.surfaceHigh,
            onSurface = t.onSurface,
            onSurfaceMuted = t.onSurfaceMuted,
            divider = t.divider,
            danger = t.danger,
            isDark = dark,
        )

        fun from(scheme: ColorScheme, dark: Boolean) = OneEmuPalette(
            accent = scheme.primary,
            accentDark = scheme.primaryContainer,
            background = scheme.background,
            surface = scheme.surfaceContainer,
            surfaceHigh = scheme.surfaceContainerHigh,
            onSurface = scheme.onSurface,
            onSurfaceMuted = scheme.onSurfaceVariant,
            divider = scheme.outlineVariant,
            danger = scheme.error,
            isDark = dark,
        )
    }
}

/** Fully resolved theme: what [OneEmuTheme] and the preview cards actually paint with. */
@Immutable
data class ResolvedTheme(val scheme: ColorScheme, val palette: OneEmuPalette, val isDark: Boolean)

/** One selectable look. [nameRes]/[descRes] are Korean strings in strings_theme.xml. */
abstract class ThemePreset(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
) {
    abstract fun resolve(context: Context, dark: Boolean, amoled: Boolean): ResolvedTheme

    /** True when the preset can be shown on this device (Material You needs Android 12). */
    open val available: Boolean get() = true
}

private class TonalPreset(
    id: String,
    @StringRes nameRes: Int,
    @StringRes descRes: Int,
    private val light: ThemeTones,
    private val dark: ThemeTones,
) : ThemePreset(id, nameRes, descRes) {
    override fun resolve(context: Context, dark: Boolean, amoled: Boolean): ResolvedTheme {
        val tones = if (dark) (if (amoled) this.dark.amoled() else this.dark) else light
        return ResolvedTheme(
            scheme = if (dark) darkSchemeOf(tones) else lightSchemeOf(tones),
            palette = OneEmuPalette.from(tones, dark),
            isDark = dark,
        )
    }
}

private class DynamicPreset : ThemePreset("dynamic", R.string.theme_dynamic, R.string.theme_dynamic_desc) {
    override val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @RequiresApi(Build.VERSION_CODES.S)
    private fun scheme(context: Context, dark: Boolean): ColorScheme =
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    override fun resolve(context: Context, dark: Boolean, amoled: Boolean): ResolvedTheme {
        if (!available) return ThemePresets.mint.resolve(context, dark, amoled)
        var scheme = scheme(context, dark)
        if (dark && amoled) {
            scheme = scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainer = Color(0xFF0E0E0E),
                surfaceContainerHigh = Color(0xFF181818),
                surfaceContainerHighest = Color(0xFF222222),
                surfaceVariant = Color(0xFF181818),
                surfaceDim = Color.Black,
            )
        }
        return ResolvedTheme(scheme, OneEmuPalette.from(scheme, dark), dark)
    }
}

/** Pure-black variant of a dark tone set for OLED panels. */
private fun ThemeTones.amoled(): ThemeTones = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceHigh = Color(0xFF161616),
    divider = lerp(Color.Black, divider, 0.7f),
)

private fun darkSchemeOf(t: ThemeTones): ColorScheme = darkColorScheme(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentContainer,
    onPrimaryContainer = t.onSurface,
    inversePrimary = t.accentContainer,
    secondary = t.accent,
    onSecondary = t.onAccent,
    secondaryContainer = t.surfaceHigh,
    onSecondaryContainer = t.onSurface,
    tertiary = t.accent,
    onTertiary = t.onAccent,
    tertiaryContainer = t.surfaceHigh,
    onTertiaryContainer = t.onSurface,
    background = t.background,
    onBackground = t.onSurface,
    surface = t.surface,
    onSurface = t.onSurface,
    surfaceVariant = t.surfaceHigh,
    onSurfaceVariant = t.onSurfaceMuted,
    surfaceTint = t.accent,
    surfaceDim = t.background,
    surfaceBright = lerp(t.surfaceHigh, t.onSurface, 0.12f),
    surfaceContainerLowest = t.background,
    surfaceContainerLow = t.surface,
    surfaceContainer = t.surface,
    surfaceContainerHigh = t.surfaceHigh,
    surfaceContainerHighest = lerp(t.surfaceHigh, t.onSurface, 0.08f),
    inverseSurface = t.onSurface,
    inverseOnSurface = t.background,
    outline = t.divider,
    outlineVariant = t.divider,
    error = t.danger,
    onError = Color.White,
    errorContainer = lerp(t.danger, t.background, 0.6f),
    onErrorContainer = t.onSurface,
    scrim = Color.Black,
)

private fun lightSchemeOf(t: ThemeTones): ColorScheme = lightColorScheme(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.accentContainer,
    onPrimaryContainer = t.onSurface,
    inversePrimary = t.accentContainer,
    secondary = t.accent,
    onSecondary = t.onAccent,
    secondaryContainer = t.surfaceHigh,
    onSecondaryContainer = t.onSurface,
    tertiary = t.accent,
    onTertiary = t.onAccent,
    tertiaryContainer = t.surfaceHigh,
    onTertiaryContainer = t.onSurface,
    background = t.background,
    onBackground = t.onSurface,
    surface = t.surface,
    onSurface = t.onSurface,
    surfaceVariant = t.surfaceHigh,
    onSurfaceVariant = t.onSurfaceMuted,
    surfaceTint = t.accent,
    surfaceDim = t.surfaceHigh,
    surfaceBright = t.surface,
    surfaceContainerLowest = t.surface,
    surfaceContainerLow = t.background,
    surfaceContainer = t.surface,
    surfaceContainerHigh = t.surfaceHigh,
    surfaceContainerHighest = lerp(t.surfaceHigh, t.onSurface, 0.06f),
    inverseSurface = t.onSurface,
    inverseOnSurface = t.background,
    outline = lerp(t.divider, t.onSurface, 0.25f),
    outlineVariant = t.divider,
    error = t.danger,
    onError = Color.White,
    errorContainer = lerp(t.danger, t.surface, 0.8f),
    onErrorContainer = lerp(t.danger, Color.Black, 0.3f),
    scrim = Color.Black,
)

/** Registry of every preset, in the order the picker shows them. */
object ThemePresets {
    const val DEFAULT_ID = "mint"

    /** 기본: 차콜 + 민트. The dark tones are the colours the app has always used. */
    val mint: ThemePreset = TonalPreset(
        id = "mint", nameRes = R.string.theme_mint, descRes = R.string.theme_mint_desc,
        dark = ThemeTones(
            accent = Color(0xFF7FD1C8), accentContainer = Color(0xFF4FA89F), onAccent = Color(0xFF10201E),
            background = Color(0xFF1E1E1E), surface = Color(0xFF2B2B2B), surfaceHigh = Color(0xFF363636),
            onSurface = Color(0xFFF2F2F2), onSurfaceMuted = Color(0xFFA5A5A5), divider = Color(0xFF444444),
            danger = Color(0xFFE06060),
        ),
        light = ThemeTones(
            accent = Color(0xFF2E8F86), accentContainer = Color(0xFFB9EAE4), onAccent = Color.White,
            background = Color(0xFFF3F6F6), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE6EEED),
            onSurface = Color(0xFF1B1F1F), onSurfaceMuted = Color(0xFF5F6B6A), divider = Color(0xFFD5DCDB),
            danger = Color(0xFFC84646),
        ),
    )

    val graphite: ThemePreset = TonalPreset(
        id = "graphite", nameRes = R.string.theme_graphite, descRes = R.string.theme_graphite_desc,
        dark = ThemeTones(
            accent = Color(0xFFE6E6E6), accentContainer = Color(0xFF8C8C8C), onAccent = Color(0xFF111111),
            background = Color(0xFF121212), surface = Color(0xFF1F1F1F), surfaceHigh = Color(0xFF2C2C2C),
            onSurface = Color(0xFFF0F0F0), onSurfaceMuted = Color(0xFF9E9E9E), divider = Color(0xFF3A3A3A),
            danger = Color(0xFFE06060),
        ),
        light = ThemeTones(
            accent = Color(0xFF3A3A3A), accentContainer = Color(0xFFD9D9D9), onAccent = Color.White,
            background = Color(0xFFF2F2F2), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE6E6E6),
            onSurface = Color(0xFF1A1A1A), onSurfaceMuted = Color(0xFF666666), divider = Color(0xFFD0D0D0),
            danger = Color(0xFFC84646),
        ),
    )

    val ocean: ThemePreset = TonalPreset(
        id = "ocean", nameRes = R.string.theme_ocean, descRes = R.string.theme_ocean_desc,
        dark = ThemeTones(
            accent = Color(0xFF62B6F7), accentContainer = Color(0xFF2F7FC1), onAccent = Color(0xFF041A2C),
            background = Color(0xFF0E1A2B), surface = Color(0xFF16263C), surfaceHigh = Color(0xFF1F324C),
            onSurface = Color(0xFFEAF2FB), onSurfaceMuted = Color(0xFF93A6BC), divider = Color(0xFF2B4160),
            danger = Color(0xFFF06A6A),
        ),
        light = ThemeTones(
            accent = Color(0xFF1769AA), accentContainer = Color(0xFFBFE0F8), onAccent = Color.White,
            background = Color(0xFFEEF4FA), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFDCE8F5),
            onSurface = Color(0xFF10233A), onSurfaceMuted = Color(0xFF51667F), divider = Color(0xFFC7D6E6),
            danger = Color(0xFFC84646),
        ),
    )

    val sunset: ThemePreset = TonalPreset(
        id = "sunset", nameRes = R.string.theme_sunset, descRes = R.string.theme_sunset_desc,
        dark = ThemeTones(
            accent = Color(0xFFFF8A5B), accentContainer = Color(0xFFC9613A), onAccent = Color(0xFF2A1208),
            background = Color(0xFF231A16), surface = Color(0xFF32261F), surfaceHigh = Color(0xFF3F312A),
            onSurface = Color(0xFFF7EEE8), onSurfaceMuted = Color(0xFFB39E92), divider = Color(0xFF4E3E35),
            danger = Color(0xFFF0605A),
        ),
        light = ThemeTones(
            accent = Color(0xFFD2551F), accentContainer = Color(0xFFFFD3BF), onAccent = Color.White,
            background = Color(0xFFFBF3EE), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF4E4DB),
            onSurface = Color(0xFF2B1B14), onSurfaceMuted = Color(0xFF7A5F52), divider = Color(0xFFE4CFC3),
            danger = Color(0xFFC0392B),
        ),
    )

    val forest: ThemePreset = TonalPreset(
        id = "forest", nameRes = R.string.theme_forest, descRes = R.string.theme_forest_desc,
        dark = ThemeTones(
            accent = Color(0xFFB8E356), accentContainer = Color(0xFF7FA83A), onAccent = Color(0xFF15200A),
            background = Color(0xFF131B14), surface = Color(0xFF1D2A1F), surfaceHigh = Color(0xFF27362A),
            onSurface = Color(0xFFEEF5EC), onSurfaceMuted = Color(0xFF9CB39B), divider = Color(0xFF344836),
            danger = Color(0xFFE86A62),
        ),
        light = ThemeTones(
            accent = Color(0xFF4C7F1A), accentContainer = Color(0xFFDCF1B4), onAccent = Color.White,
            background = Color(0xFFF1F7EE), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE0EDDA),
            onSurface = Color(0xFF15230F), onSurfaceMuted = Color(0xFF566A50), divider = Color(0xFFCBDDC4),
            danger = Color(0xFFC0392B),
        ),
    )

    /** Game Boy: olive LCD tones. Light mode is the classic pea-green screen, dark mode the shell. */
    val retro: ThemePreset = TonalPreset(
        id = "retro", nameRes = R.string.theme_retro, descRes = R.string.theme_retro_desc,
        dark = ThemeTones(
            accent = Color(0xFF9BBC0F), accentContainer = Color(0xFF5C7A18), onAccent = Color(0xFF0F380F),
            background = Color(0xFF1B2416), surface = Color(0xFF283420), surfaceHigh = Color(0xFF35442A),
            onSurface = Color(0xFFD8E6B0), onSurfaceMuted = Color(0xFF9CAA7C), divider = Color(0xFF465638),
            danger = Color(0xFFD96A5A),
        ),
        light = ThemeTones(
            accent = Color(0xFF306230), accentContainer = Color(0xFF9BBC0F), onAccent = Color(0xFFE8F0C8),
            background = Color(0xFFC4CFA1), surface = Color(0xFFD6DFB9), surfaceHigh = Color(0xFFB8C48E),
            onSurface = Color(0xFF0F380F), onSurfaceMuted = Color(0xFF4A6A3A), divider = Color(0xFF9DAB78),
            danger = Color(0xFFA03A2A),
        ),
    )

    /** Famicom: cream white with a red point; dark mode swaps the cream for charcoal. */
    val famicom: ThemePreset = TonalPreset(
        id = "famicom", nameRes = R.string.theme_famicom, descRes = R.string.theme_famicom_desc,
        dark = ThemeTones(
            accent = Color(0xFFE84B4B), accentContainer = Color(0xFFA82C2C), onAccent = Color.White,
            background = Color(0xFF202020), surface = Color(0xFF2C2C2C), surfaceHigh = Color(0xFF383838),
            onSurface = Color(0xFFF3ECE0), onSurfaceMuted = Color(0xFFA89F93), divider = Color(0xFF474747),
            danger = Color(0xFFFF6B6B),
        ),
        light = ThemeTones(
            accent = Color(0xFFD12E2E), accentContainer = Color(0xFFF8C9C9), onAccent = Color.White,
            background = Color(0xFFF4EFE4), surface = Color(0xFFFFFCF5), surfaceHigh = Color(0xFFEBE3D2),
            onSurface = Color(0xFF2A2320), onSurfaceMuted = Color(0xFF6F645C), divider = Color(0xFFDCD2C0),
            danger = Color(0xFFB42020),
        ),
    )

    /** Material You (Android 12+). Hidden on older devices. */
    val dynamic: ThemePreset = DynamicPreset()

    /** Every preset in picker order; filter with [ThemePreset.available] before showing. */
    val all: List<ThemePreset> = listOf(mint, graphite, ocean, sunset, forest, retro, famicom, dynamic)

    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id == id && it.available } ?: mint
}
