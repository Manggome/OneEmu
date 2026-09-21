package com.manggome.oneemu.emu.input

import android.view.InputDevice

/** A physical controller as the settings screen and the emulator see it. */
data class PadDevice(
    val deviceId: Int,
    val name: String,
    /** Stable per-model key used for the saved mapping and player choice. */
    val key: String,
    /** 0-based libretro port this pad feeds. */
    val port: Int,
)

/**
 * Which physical pads are attached and which player each one is. A pad the user pinned to a player
 * keeps that slot; the rest fill the gaps in the order Android reports them, which is the order they
 * were connected.
 */
object GamepadDevices {
    const val MAX_PLAYERS = 4

    /** Stored value for "decide by connection order"; 1..[MAX_PLAYERS] pin a player. */
    const val AUTO = 0

    /**
     * Compare whole source constants: a bare `and != 0` also matches keyboards via SOURCE_CLASS_BUTTON.
     */
    fun isGamepad(d: InputDevice): Boolean = !d.isVirtual &&
        ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)

    /** Attached pads, in the order Android lists them (ascending device id = connection order). */
    fun connected(): List<InputDevice> =
        InputDevice.getDeviceIds().sorted().mapNotNull { InputDevice.getDevice(it) }.filter { isGamepad(it) }

    fun assign(devices: List<InputDevice>, preferred: Map<String, Int>): List<PadDevice> =
        assignPorts(devices.map { PadDevice(it.id, it.name, GamepadMapping.deviceKey(it), port = 0) }, preferred)

    /**
     * Resolves [pads] (in connection order) to ports. [preferred] maps a device key to a stored
     * 1-based player number; a pin only holds while the slot is free, so two pads pinned to the same
     * player still end up on different ports instead of fighting over one.
     */
    fun assignPorts(pads: List<PadDevice>, preferred: Map<String, Int>): List<PadDevice> {
        val taken = BooleanArray(MAX_PLAYERS)
        val out = arrayOfNulls<PadDevice>(pads.size)
        fun place(i: Int, pad: PadDevice, port: Int) {
            taken[port] = true
            out[i] = pad.copy(port = port)
        }
        pads.forEachIndexed { i, pad ->
            val want = (preferred[pad.key] ?: AUTO) - 1
            if (want in 0 until MAX_PLAYERS && !taken[want]) place(i, pad, want)
        }
        pads.forEachIndexed { i, pad ->
            if (out[i] != null) return@forEachIndexed
            val free = (0 until MAX_PLAYERS).firstOrNull { !taken[it] }
            // More pads than ports: the extras double up on the last one rather than going dead.
            place(i, pad, free ?: (MAX_PLAYERS - 1))
        }
        return out.filterNotNull()
    }
}
