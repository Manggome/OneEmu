package com.manggome.oneemu.emu.phone

import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.emu.pad.PhoneKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Which feature-phone key each gamepad button presses. The physical pad first goes through OneEmu's own
 * per-device mapping to RetroPad buttons; this table then turns those into handset keys (WIPI-X-style
 * 게임패드 키매핑). One table for every game, optionally overridden per game.
 */
data class WipiPadMapping(val keys: Map<Int, Int>) {

    /** RetroPad mask -> phone key bits. */
    fun toPhone(mask: Int): Int {
        if (mask == 0) return 0
        var out = 0
        for ((button, phone) in keys) if (mask and button != 0) out = out or phone
        return out
    }

    fun keyFor(button: Int): Int = keys[button] ?: 0

    fun with(button: Int, phone: Int): WipiPadMapping =
        WipiPadMapping(if (phone == 0) keys - button else keys + (button to phone))

    /** "button:phone,button:phone" (both as bit values). */
    fun encode(): String = keys.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        /** Pad buttons the mapping screen offers, in RetroPad order. */
        val BUTTONS = listOf(
            Buttons.UP, Buttons.DOWN, Buttons.LEFT, Buttons.RIGHT,
            Buttons.A, Buttons.B, Buttons.X, Buttons.Y,
            Buttons.L, Buttons.R, Buttons.L2, Buttons.R2,
            Buttons.SELECT, Buttons.START, Buttons.L3, Buttons.R3,
        )

        /**
         * Defaults follow how Korean phone games were played: the d-pad moves, the bottom face button confirms
         * (확인/OK) and the right one backs out (취소), the shoulders are the soft keys, * and # on the top face
         * buttons, 5 and 0 on the stick clicks for games that fire with them.
         */
        val DEFAULT = WipiPadMapping(
            mapOf(
                Buttons.UP to PhoneKeys.UP,
                Buttons.DOWN to PhoneKeys.DOWN,
                Buttons.LEFT to PhoneKeys.LEFT,
                Buttons.RIGHT to PhoneKeys.RIGHT,
                Buttons.B to PhoneKeys.OK,
                Buttons.A to PhoneKeys.CLR,
                Buttons.Y to PhoneKeys.STAR,
                Buttons.X to PhoneKeys.HASH,
                Buttons.L to PhoneKeys.LSK,
                Buttons.R to PhoneKeys.RSK,
                Buttons.L2 to PhoneKeys.CALL,
                Buttons.R2 to PhoneKeys.D5,
                Buttons.START to PhoneKeys.LSK,
                Buttons.SELECT to PhoneKeys.RSK,
                Buttons.L3 to PhoneKeys.D0,
                Buttons.R3 to PhoneKeys.D5,
            ),
        )

        fun decode(text: String): WipiPadMapping? {
            if (text.isBlank()) return null
            val map = text.split(',').mapNotNull { part ->
                val (b, p) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val button = b.trim().toIntOrNull() ?: return@mapNotNull null
                val phone = p.trim().toIntOrNull() ?: return@mapNotNull null
                button to phone
            }.toMap()
            return WipiPadMapping(map)
        }

        val GLOBAL = stringPreferencesKey("wipi.pad_mapping")
        fun perGame(gameId: Long) = stringPreferencesKey("wipi.pad_mapping.$gameId")

        /** The game's own table when it has one, else the shared table, else [DEFAULT]. */
        fun observe(settings: Settings, gameId: Long): Flow<Pair<WipiPadMapping, Boolean>> =
            combine(settings.observe(perGame(gameId), ""), settings.observe(GLOBAL, "")) { game, global ->
                val own = decode(game)
                if (own != null) own to true else (decode(global) ?: DEFAULT) to false
            }
    }
}
