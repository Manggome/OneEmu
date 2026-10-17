package com.manggome.oneemu.emu

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.WipiCompat
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 화면 방향: how the game screen follows the phone, chosen per game.
 *
 * [AUTO] is what every game always did (the activity turns with the phone and ignores the system rotation lock).
 * A game played by turning the phone itself - NOM, where the corridor turns with the handset - needs the screen
 * to stay put, so it can be fixed, or left to the phone's own rotation lock.
 *
 * The choice is stored per game under [key]; when the user has not picked one, a WIPI game takes the default its
 * compatibility-list entry names (`"orientation": "portrait"` in cores/wipi/assets/compat.json), and anything
 * else is [AUTO].
 */
enum class GameOrientation(
    /** Stored value and compat.json spelling. */
    val key: String,
    /** The [ActivityInfo] `SCREEN_ORIENTATION_*` the emulator activity requests. */
    val activityOrientation: Int,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
) {
    AUTO("auto", ActivityInfo.SCREEN_ORIENTATION_SENSOR, R.string.orient_auto, R.string.orient_auto_desc),
    FOLLOW_SYSTEM("system", ActivityInfo.SCREEN_ORIENTATION_FULL_USER, R.string.orient_system, R.string.orient_system_desc),
    /** Strict portrait, not sensorPortrait: a game played by turning the phone must not flip upside down either. */
    PORTRAIT("portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, R.string.orient_portrait, R.string.orient_portrait_desc),
    /** Either way round sideways, whichever way the phone is held. */
    LANDSCAPE("landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, R.string.orient_landscape, R.string.orient_landscape_desc);

    /** What applies to a game, and whether it is the compatibility list's default rather than the user's pick. */
    data class Effective(val orientation: GameOrientation, val fromCatalog: Boolean = false)

    companion object {
        val DEFAULT = AUTO

        /** This game's own choice; absent or "" = not chosen. */
        fun key(gameId: Long): Preferences.Key<String> = stringPreferencesKey("orientation.$gameId")

        /** A stored or compat.json value; a few spellings of Android's own names are accepted too. */
        fun fromKey(value: String?): GameOrientation? = when (value?.trim()?.lowercase()) {
            null, "" -> null
            "auto", "sensor", "fullsensor" -> AUTO
            "system", "user", "fulluser" -> FOLLOW_SYSTEM
            "portrait", "sensorportrait", "userportrait" -> PORTRAIT
            "landscape", "sensorlandscape", "userlandscape" -> LANDSCAPE
            else -> null
        }

        /** The user's pick wins; then the catalog default; then [DEFAULT]. */
        fun resolve(stored: String?, catalogDefault: String?): Effective {
            fromKey(stored)?.let { return Effective(it) }
            fromKey(catalogDefault)?.let { return Effective(it, fromCatalog = true) }
            return Effective(DEFAULT)
        }

        /** The default the WIPI compatibility list gives this exact file (or another edition of the game), if any. */
        suspend fun catalogDefault(context: Context, game: GameEntity): String? {
            if (game.system != SystemId.WIPI.id) return null
            val verdict = runCatching { WipiCompat.get(context).resolve(game.path) }.getOrNull() ?: return null
            if (verdict.match == WipiCompat.Match.NONE) return null
            return verdict.entry?.orientation
        }

        /** The orientation in effect for [game], following later changes to its stored choice. */
        fun observe(context: Context, settings: Settings, game: GameEntity): Flow<Effective> = flow {
            val catalog = catalogDefault(context, game)
            emitAll(settings.observe(key(game.id), "").map { resolve(it, catalog) })
        }

        suspend fun set(settings: Settings, gameId: Long, choice: GameOrientation) = settings.set(key(gameId), choice.key)

        suspend fun forget(settings: Settings, gameId: Long) = settings.remove(key(gameId))
    }
}
