package com.manggome.oneemu.emu

import androidx.datastore.preferences.core.intPreferencesKey
import com.manggome.oneemu.model.SystemId

/**
 * 되감기: the frontend keeps a few seconds of save states and plays back through them while the button
 * is held. Only on systems whose states are small and quick to take - on a PS2 or Wii a state is tens of
 * megabytes, and taking one every few frames would stall the game.
 */
object Rewind {
    val SECONDS = intPreferencesKey("rewind_seconds")
    const val DEFAULT_SECONDS = 10
    val CHOICES = listOf(0, 5, 10, 20, 30, 60)

    private val LIGHT = setOf(SystemId.NES, SystemId.GB, SystemId.GBC, SystemId.GBA, SystemId.MD, SystemId.SMS, SystemId.GG)

    fun supports(system: SystemId): Boolean = system in LIGHT
}
