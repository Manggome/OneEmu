package com.manggome.oneemu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ViewMode { LIST, GRID }
enum class SortMode { TITLE, RECENT, ADDED }

/**
 * App-wide preferences. Per-core option overrides are stored as "key=value\n" blobs under
 * `coreopts.<coreId>`; per-system control layouts as JSON under `layout.<systemId>.<orientation>`.
 */
class Settings(private val context: Context) {
    private val ds get() = context.dataStore

    object Keys {
        val viewMode = stringPreferencesKey("view_mode")
        val sortMode = stringPreferencesKey("sort_mode")
        val gridColumns = intPreferencesKey("grid_columns")
        val groupBySystem = booleanPreferencesKey("group_by_system")
        val showFileName = booleanPreferencesKey("show_file_name")

        val videoLinearFilter = booleanPreferencesKey("video_linear")
        val videoAspect = intPreferencesKey("video_aspect") // 0 core, 1 stretch, 2 integer, 3 square
        val fastForwardSpeed = intPreferencesKey("ff_speed") // 0 unlimited, else N×
        val showFps = booleanPreferencesKey("show_fps")
        val audioEnabled = booleanPreferencesKey("audio_enabled")
        val autoSaveState = booleanPreferencesKey("auto_save_state")

        val padOpacity = floatPreferencesKey("pad_opacity") // 0..1
        val padVibration = booleanPreferencesKey("pad_vibration")
        val padVibrationMs = intPreferencesKey("pad_vibration_ms")
        val padScale = floatPreferencesKey("pad_scale")
        val padHideWithGamepad = booleanPreferencesKey("pad_hide_with_gamepad")

        val updateCheckOnStart = booleanPreferencesKey("update_check_on_start")
        val updateLastCheckAt = longPreferencesKey("update_last_check_at")
        val updateSkippedVersion = stringPreferencesKey("update_skipped_version")

        val storageGranted = booleanPreferencesKey("storage_granted_once")

        fun coreOptions(coreId: String) = stringPreferencesKey("coreopts.$coreId")
        fun coreForSystem(systemId: String) = stringPreferencesKey("core.$systemId")
        fun layout(systemId: String, landscape: Boolean) = stringPreferencesKey("layout.$systemId.${if (landscape) "land" else "port"}")
        fun gamepadMapping(deviceKey: String) = stringPreferencesKey("gamepad.$deviceKey")
    }

    val flow: Flow<Preferences> get() = ds.data

    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<T> = ds.data.map { it[key] ?: default }
    suspend fun <T> get(key: Preferences.Key<T>, default: T): T = ds.data.first()[key] ?: default
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ds.edit { it[key] = value } }
    suspend fun <T> remove(key: Preferences.Key<T>) { ds.edit { it.remove(key) } }

    // Convenience accessors used across features.
    val viewMode: Flow<ViewMode> get() = observe(Keys.viewMode, ViewMode.LIST.name).map { runCatching { ViewMode.valueOf(it) }.getOrDefault(ViewMode.LIST) }
    val sortMode: Flow<SortMode> get() = observe(Keys.sortMode, SortMode.TITLE.name).map { runCatching { SortMode.valueOf(it) }.getOrDefault(SortMode.TITLE) }

    suspend fun coreOptionOverrides(coreId: String): Map<String, String> =
        get(Keys.coreOptions(coreId), "").lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    suspend fun setCoreOptionOverride(coreId: String, key: String, value: String?) {
        val map = coreOptionOverrides(coreId).toMutableMap()
        if (value == null) map.remove(key) else map[key] = value
        set(Keys.coreOptions(coreId), map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    companion object {
        const val DEFAULT_PAD_OPACITY = 0.6f
        const val DEFAULT_PAD_SCALE = 1.0f
        const val DEFAULT_VIBRATION_MS = 15
        const val DEFAULT_FF_SPEED = 3
    }
}
