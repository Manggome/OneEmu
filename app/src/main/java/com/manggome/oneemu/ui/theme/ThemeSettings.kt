package com.manggome.oneemu.ui.theme

import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Light/dark selection. `SYSTEM` follows the OS dark-mode switch. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Everything the user picked on the theme screen, as one immutable value. */
data class ThemeChoice(
    val presetId: String = ThemePresets.DEFAULT_ID,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamic: Boolean = false,
) {
    /** Preset that is actually shown: Material You wins when enabled and supported. */
    val effectivePreset: ThemePreset
        get() = if (dynamic && ThemeSettings.dynamicSupported) ThemePresets.dynamic else ThemePresets.byId(presetId)

    companion object {
        fun from(prefs: Preferences): ThemeChoice = ThemeChoice(
            presetId = prefs[ThemeSettings.themeId] ?: ThemePresets.DEFAULT_ID,
            mode = ThemeMode.fromKey(prefs[ThemeSettings.themeMode]),
            amoled = prefs[ThemeSettings.themeAmoled] ?: false,
            dynamic = prefs[ThemeSettings.themeDynamic] ?: false,
        )
    }
}

/**
 * Preference keys owned by the theme package. Other features read the resolved colours via
 * [OneEmuTheme.colors] / [MaterialTheme]; only the theme screen writes these.
 */
object ThemeSettings {
    /** Preset id, one of [ThemePresets.all] ids. Default "mint". */
    val themeId: Preferences.Key<String> = stringPreferencesKey("theme_id")
    /** "system" | "light" | "dark". Default "system". */
    val themeMode: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    /** Pure-black background/surface for any dark scheme. */
    val themeAmoled: Preferences.Key<Boolean> = booleanPreferencesKey("theme_amoled")
    /** Material You wallpaper colours (Android 12+); overrides [themeId] while on. */
    val themeDynamic: Preferences.Key<Boolean> = booleanPreferencesKey("theme_dynamic")

    val dynamicSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Last value delivered by [observe]. Used as the initial state of [OneEmuTheme] so a second
     * activity (the emulator) does not flash the default theme before DataStore answers.
     */
    @Volatile
    var cached: ThemeChoice? = null
        internal set

    fun observe(settings: Settings): Flow<ThemeChoice> =
        settings.flow.map { ThemeChoice.from(it) }.map { cached = it; it }
}
