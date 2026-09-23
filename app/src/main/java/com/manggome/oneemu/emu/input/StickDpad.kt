package com.manggome.oneemu.emu.input

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.emu.pad.PadProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * "스틱을 방향키로도": the left stick also presses the d-pad.
 *
 * Plenty of games never read an analog stick - Tekken 5 on PS2 is one, most PS1 games are others - and
 * there a stick does nothing at all. With this on, whichever stick is doing the steering (the on-screen
 * one, an image skin's, or a Bluetooth pad's) sends the matching direction too. It is applied once, where
 * every input source is merged before it goes to the core, so all of them behave the same way.
 *
 * Stored per pad (system + variant), with one switch in 게임패드 settings that turns it on everywhere.
 * Off by default: a game that reads both would see every direction twice.
 */
object StickDpad {
    /** On for every system. */
    val everywhere: Preferences.Key<Boolean> = booleanPreferencesKey("stick_dpad_all")

    fun key(profile: PadProfile): Preferences.Key<Boolean> = booleanPreferencesKey("stick_dpad.${profile.key}")

    fun observe(settings: Settings, profile: PadProfile): Flow<Boolean> =
        combine(settings.observe(everywhere, false), settings.observe(key(profile), false)) { all, mine -> all || mine }

    /** How far the stick must be pushed on an axis (of 32767) before it counts as that direction held. */
    private const val THRESHOLD = 16384

    /**
     * The d-pad bits for a stick at ([lx], [ly]), libretro axes (-32768..32767, +y down). Half-way out
     * counts, so a thumb resting on the stick presses nothing and a diagonal presses both.
     */
    fun mask(lx: Int, ly: Int): Int {
        var m = 0
        if (lx <= -THRESHOLD) m = m or Buttons.LEFT
        if (lx >= THRESHOLD) m = m or Buttons.RIGHT
        if (ly <= -THRESHOLD) m = m or Buttons.UP
        if (ly >= THRESHOLD) m = m or Buttons.DOWN
        return m
    }
}
