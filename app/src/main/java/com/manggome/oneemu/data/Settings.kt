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
import com.manggome.oneemu.emu.ScreenConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ViewMode { LIST, GRID }
enum class SortMode { TITLE, RECENT, ADDED }

/**
 * App-wide preferences. Per-core option overrides are stored as "key=value\n" blobs under
 * `coreopts.<coreId>`; per-system control layouts as JSON under `layout.<systemId>.<screen config>`.
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
        /** Extra quarter turns for one game, on top of the core's own rotation. */
        fun videoRotation(gameId: Long) = intPreferencesKey("video_rotation_$gameId")
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
        /** Per-core graphics API override: "vulkan" or "gles3"; unset = core.json hwRender. */
        fun graphicsApi(coreId: String) = stringPreferencesKey("gfxapi.$coreId")
        fun coreForSystem(systemId: String) = stringPreferencesKey("core.$systemId")
        /** Per-(system, screen configuration) pad layout JSON: `layout.<system>.<port|land|port_wide|land_wide>`. */
        fun layout(systemId: String, config: ScreenConfig) = stringPreferencesKey("layout.$systemId.${config.key}")
        /** Legacy orientation-only accessor; maps to the folded/phone configs so existing saves keep working. */
        fun layout(systemId: String, landscape: Boolean) = layout(systemId, if (landscape) ScreenConfig.LANDSCAPE else ScreenConfig.PORTRAIT)
        fun gamepadMapping(deviceKey: String) = stringPreferencesKey("gamepad.$deviceKey")
        /** Player this pad is pinned to, 1-based; absent or 0 = follow connection order. */
        fun gamepadPort(deviceKey: String) = intPreferencesKey("gamepadport.$deviceKey")
        const val GAMEPAD_PORT_PREFIX = "gamepadport."

        /** Buttons autofire presses while 연사 is on, as a libretro button mask. */
        val turboMask = intPreferencesKey("turbo_mask")
        /** Autofire presses per second. */
        val turboRate = intPreferencesKey("turbo_rate")
    }

    val flow: Flow<Preferences> get() = ds.data

    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<T> = ds.data.map { it[key] ?: default }
    suspend fun <T> get(key: Preferences.Key<T>, default: T): T = ds.data.first()[key] ?: default
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ds.edit { it[key] = value } }
    suspend fun <T> remove(key: Preferences.Key<T>) { ds.edit { it.remove(key) } }

    // Convenience accessors used across features.
    val viewMode: Flow<ViewMode> get() = observe(Keys.viewMode, ViewMode.LIST.name).map { runCatching { ViewMode.valueOf(it) }.getOrDefault(ViewMode.LIST) }
    val sortMode: Flow<SortMode> get() = observe(Keys.sortMode, SortMode.TITLE.name).map { runCatching { SortMode.valueOf(it) }.getOrDefault(SortMode.TITLE) }

    /** Every pinned player choice, keyed by device key; pads with no pin are simply absent. */
    val gamepadPorts: Flow<Map<String, Int>> get() = ds.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (k, v) ->
            val name = k.name
            if (!name.startsWith(Keys.GAMEPAD_PORT_PREFIX)) null
            else (v as? Int)?.let { name.removePrefix(Keys.GAMEPAD_PORT_PREFIX) to it }
        }.toMap()
    }

    suspend fun graphicsApi(coreId: String): String? = get(Keys.graphicsApi(coreId), "").takeIf { it.isNotEmpty() }
    suspend fun setGraphicsApi(coreId: String, api: String?) = set(Keys.graphicsApi(coreId), api ?: "")

    suspend fun coreOptionOverrides(coreId: String): Map<String, String> =
        get(Keys.coreOptions(coreId), "").lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    suspend fun setCoreOptionOverride(coreId: String, key: String, value: String?) {
        val map = coreOptionOverrides(coreId).toMutableMap()
        if (value == null) map.remove(key) else map[key] = value
        set(Keys.coreOptions(coreId), map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    companion object {
        const val DEFAULT_PAD_OPACITY = 0.85f
        const val DEFAULT_PAD_SCALE = 1.0f
        const val DEFAULT_VIBRATION_MS = 15
        const val DEFAULT_FF_SPEED = 3

        /** Autofire defaults: the two face buttons (B|A), ~10 presses a second. */
        const val DEFAULT_TURBO_MASK = (1 shl 0) or (1 shl 8)
        const val DEFAULT_TURBO_RATE = 10
        val TURBO_RATES = listOf(5, 8, 10, 15, 20)
        /** Fast-forward speeds offered in the settings, 0 = unlimited. */
        val FF_SPEEDS = listOf(2, 3, 5, 10, 0)
        /** Steps the on-screen 배속 button cycles through; 1 = normal speed (fast forward off). */
        val SPEED_CYCLE = listOf(1) + FF_SPEEDS

        /** Next step of [SPEED_CYCLE] after [current]; an unknown speed continues at the second step. */
        fun nextSpeed(current: Int): Int {
            val i = SPEED_CYCLE.indexOf(current)
            return SPEED_CYCLE[((if (i < 0) 0 else i) + 1) % SPEED_CYCLE.size]
        }
    }
}
