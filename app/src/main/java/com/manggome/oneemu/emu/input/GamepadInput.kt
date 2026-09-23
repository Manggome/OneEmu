package com.manggome.oneemu.emu.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Key code → libretro button mapping for a physical controller. [MENU] and [TURBO] are
 * pseudo-buttons that open the in-game menu and toggle autofire. Overrides are stored per device as
 * "keycode=BUTTON\n" lines where BUTTON is a name from [buttonNames] (or a raw bit index); an empty
 * value drops a default binding.
 */
data class GamepadMapping(val keys: Map<Int, Int>) {
    fun buttonFor(keyCode: Int): Int? = keys[keyCode]

    /** The first key bound to [button], which is what the mapping screen shows on its row. */
    fun keyFor(button: Int): Int? = keys.entries.firstOrNull { it.value == button }?.key

    /** Every key bound to [button], in key-code order. */
    fun keysFor(button: Int): List<Int> = keys.filterValues { it == button }.keys.sorted()

    /** Binds [keyCode] to [button], dropping whatever used to press it. */
    fun rebind(button: Int, keyCode: Int): GamepadMapping =
        GamepadMapping(keys.filterValues { it != button } + (keyCode to button))

    /**
     * Binds [keyCode] to [button] as well, keeping the keys it already had - a back paddle (L4/R4) that
     * presses A without A itself stopping to. A key only ever does one thing, so it leaves its old job.
     */
    fun addKey(button: Int, keyCode: Int): GamepadMapping = GamepadMapping(keys + (keyCode to button))

    /**
     * Keys that press several pad buttons at once - a 조합 버튼 (macro) such as L4 = A+B. Stored like any
     * binding, just with more than one bit in the mask.
     */
    val combos: List<Pair<Int, Int>>
        get() = keys.entries.filter { (_, v) -> v !in ACTIONS && Integer.bitCount(v) > 1 }
            .map { it.key to it.value }.sortedBy { it.first }

    fun withoutKey(keyCode: Int): GamepadMapping = GamepadMapping(keys - keyCode)

    /** Only the differences from [base], in the format [parse] reads back. */
    fun overridesOf(base: GamepadMapping = DEFAULT): String = buildList {
        for (k in base.keys.keys) if (k !in keys) add("$k=")
        for ((k, v) in keys) if (base.keys[k] != v) add("$k=${nameOf(v)}")
    }.joinToString("\n")

    companion object {
        const val MENU = 1 shl 30
        const val TURBO = 1 shl 29
        /** 빨리감기 on/off. */
        const val FAST_FORWARD = 1 shl 28
        /** 배속: the next speed step. */
        const val SPEED = 1 shl 27
        /** Opens the save / load slot list, as the on-screen 저장 / 불러오기 buttons do. */
        const val SAVE_STATE = 1 shl 26
        const val LOAD_STATE = 1 shl 25

        /**
         * PlayStation games take the face buttons by position: a pad's bottom button is ×, right ○, left □,
         * top △, as on a DualShock, whatever letters are printed on it.
         */
        val PS_POSITIONAL = androidx.datastore.preferences.core.booleanPreferencesKey("gamepad_ps_positional")

        /**
         * Which games read the face buttons by position: [LAYOUT_AUTO] only PlayStation games (a DualShock's
         * ×○□△ have no letters to match), [LAYOUT_POSITION] every game, the way RetroArch does it (on a
         * Nintendo game the bottom button is then B, where the SNES has it), [LAYOUT_LETTER] none (a pad's A
         * is the game's A). Replaces [PS_POSITIONAL]; "off" there reads as [LAYOUT_LETTER].
         */
        val FACE_LAYOUT = androidx.datastore.preferences.core.stringPreferencesKey("gamepad_face_layout")
        const val LAYOUT_AUTO = "auto"
        const val LAYOUT_POSITION = "position"
        const val LAYOUT_LETTER = "letter"
        val LAYOUTS = listOf(LAYOUT_AUTO, LAYOUT_POSITION, LAYOUT_LETTER)

        /** Whether a game on a PlayStation system (or not) takes the face buttons by position under [layout]. */
        fun positionalFor(layout: String, playStation: Boolean): Boolean = when (layout) {
            LAYOUT_POSITION -> true
            LAYOUT_LETTER -> false
            else -> playStation
        }

        /** [FACE_LAYOUT], falling back to what the old on/off switch said. */
        fun observeLayout(settings: com.manggome.oneemu.data.Settings): kotlinx.coroutines.flow.Flow<String> =
            kotlinx.coroutines.flow.combine(settings.observe(FACE_LAYOUT, ""), settings.observe(PS_POSITIONAL, true)) { layout, oldSwitch ->
                layout.takeIf { it in LAYOUTS } ?: if (oldSwitch) LAYOUT_AUTO else LAYOUT_LETTER
            }

        /**
         * From the letter mapping to the position one. Android reports face buttons by position (the bottom
         * one is BUTTON_A on any pad), and the default mapping sends BUTTON_A to the RetroPad's A - its
         * *right* button, the PlayStation's ○. Swapping A↔B and X↔Y puts bottom on B (×), right on A (○),
         * left on Y (□) and top on X (△).
         */
        fun toPositional(mask: Int): Int {
            val face = Buttons.A or Buttons.B or Buttons.X or Buttons.Y
            var out = mask and face.inv()
            if (mask and Buttons.A != 0) out = out or Buttons.B
            if (mask and Buttons.B != 0) out = out or Buttons.A
            if (mask and Buttons.X != 0) out = out or Buttons.Y
            if (mask and Buttons.Y != 0) out = out or Buttons.X
            return out
        }

        /** Pseudo-buttons: app actions rather than buttons of the emulated pad. */
        val ACTIONS = setOf(MENU, TURBO, FAST_FORWARD, SPEED, SAVE_STATE, LOAD_STATE)

        val buttonNames: Map<String, Int> = mapOf(
            "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
            "L" to Buttons.L, "R" to Buttons.R, "L2" to Buttons.L2, "R2" to Buttons.R2,
            "L3" to Buttons.L3, "R3" to Buttons.R3, "START" to Buttons.START, "SELECT" to Buttons.SELECT,
            "UP" to Buttons.UP, "DOWN" to Buttons.DOWN, "LEFT" to Buttons.LEFT, "RIGHT" to Buttons.RIGHT,
            "MENU" to MENU, "TURBO" to TURBO, "FAST_FORWARD" to FAST_FORWARD, "SPEED" to SPEED,
            "SAVE_STATE" to SAVE_STATE, "LOAD_STATE" to LOAD_STATE,
        )

        /** Buttons the mapping screen offers, in the order it lists them. */
        val assignable: List<Pair<String, Int>> = listOf(
            "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
            "L" to Buttons.L, "R" to Buttons.R, "L2" to Buttons.L2, "R2" to Buttons.R2,
            "L3" to Buttons.L3, "R3" to Buttons.R3,
            "START" to Buttons.START, "SELECT" to Buttons.SELECT,
            "UP" to Buttons.UP, "DOWN" to Buttons.DOWN, "LEFT" to Buttons.LEFT, "RIGHT" to Buttons.RIGHT,
            "MENU" to MENU, "TURBO" to TURBO, "FAST_FORWARD" to FAST_FORWARD, "SPEED" to SPEED,
            "SAVE_STATE" to SAVE_STATE, "LOAD_STATE" to LOAD_STATE,
        )

        /** "A", "MENU", or "A+B" for a combination. */
        fun nameOf(button: Int): String {
            buttonNames.entries.firstOrNull { it.value == button }?.let { return it.key }
            if (button !in ACTIONS && Integer.bitCount(button) > 1) {
                val parts = COMBO_BUTTONS.filter { (_, bit) -> button and bit != 0 }
                if (parts.fold(0) { acc, (_, bit) -> acc or bit } == button) return parts.joinToString("+") { it.first }
            }
            return button.toString()
        }

        /** Pad buttons a combination can be made of, in the order they are listed and named. */
        val COMBO_BUTTONS: List<Pair<String, Int>> = listOf(
            "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
            "L" to Buttons.L, "R" to Buttons.R, "L2" to Buttons.L2, "R2" to Buttons.R2,
            "L3" to Buttons.L3, "R3" to Buttons.R3, "START" to Buttons.START, "SELECT" to Buttons.SELECT,
            "UP" to Buttons.UP, "DOWN" to Buttons.DOWN, "LEFT" to Buttons.LEFT, "RIGHT" to Buttons.RIGHT,
        )

        val DEFAULT = GamepadMapping(
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to Buttons.A,
                KeyEvent.KEYCODE_BUTTON_B to Buttons.B,
                KeyEvent.KEYCODE_BUTTON_X to Buttons.X,
                KeyEvent.KEYCODE_BUTTON_Y to Buttons.Y,
                KeyEvent.KEYCODE_BUTTON_L1 to Buttons.L,
                KeyEvent.KEYCODE_BUTTON_R1 to Buttons.R,
                KeyEvent.KEYCODE_BUTTON_L2 to Buttons.L2,
                KeyEvent.KEYCODE_BUTTON_R2 to Buttons.R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL to Buttons.L3,
                KeyEvent.KEYCODE_BUTTON_THUMBR to Buttons.R3,
                KeyEvent.KEYCODE_BUTTON_START to Buttons.START,
                KeyEvent.KEYCODE_BUTTON_SELECT to Buttons.SELECT,
                KeyEvent.KEYCODE_DPAD_UP to Buttons.UP,
                KeyEvent.KEYCODE_DPAD_DOWN to Buttons.DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to Buttons.LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to Buttons.RIGHT,
                KeyEvent.KEYCODE_BUTTON_MODE to MENU,
                // Keyboard fallback (arrow keys + Z/X/A/S, Enter/Shift).
                KeyEvent.KEYCODE_Z to Buttons.B,
                KeyEvent.KEYCODE_X to Buttons.A,
                KeyEvent.KEYCODE_A to Buttons.Y,
                KeyEvent.KEYCODE_S to Buttons.X,
                KeyEvent.KEYCODE_Q to Buttons.L,
                KeyEvent.KEYCODE_W to Buttons.R,
                KeyEvent.KEYCODE_ENTER to Buttons.START,
                KeyEvent.KEYCODE_SHIFT_RIGHT to Buttons.SELECT,
                KeyEvent.KEYCODE_SPACE to Buttons.SELECT,
            ),
        )

        /** Parses override lines on top of [base]; unknown names are ignored. */
        /** "A+B" → A|B; null unless every part is a pad button. */
        private fun parseCombo(value: String): Int? {
            if ('+' !in value) return null
            var mask = 0
            for (part in value.split('+')) {
                val bit = COMBO_BUTTONS.firstOrNull { it.first == part.trim().uppercase() }?.second ?: return null
                mask = mask or bit
            }
            return mask
        }

        fun parse(text: String, base: GamepadMapping = DEFAULT): GamepadMapping {
            if (text.isBlank()) return base
            val map = base.keys.toMutableMap()
            for (line in text.lineSequence()) {
                val key = line.substringBefore('=').trim().toIntOrNull() ?: continue
                val value = line.substringAfter('=', "").trim()
                if (value.isEmpty()) { map.remove(key); continue }
                val button = buttonNames[value.uppercase()] ?: parseCombo(value)
                    ?: value.toIntOrNull()?.let { if (it < 16) 1 shl it else it } ?: continue
                map[key] = button
            }
            return GamepadMapping(map)
        }

        /** Stable key for [Settings.Keys.gamepadMapping]. */
        fun deviceKey(device: InputDevice): String =
            if (device.vendorId != 0 || device.productId != 0) "${device.vendorId}_${device.productId}"
            else device.descriptor.take(16)
    }
}

/**
 * Translates physical controller / keyboard events into a libretro button mask plus two analog
 * sticks, per player. Feed it from Activity.dispatchKeyEvent / dispatchGenericMotionEvent.
 *
 * Each attached pad keeps its own state and is published on its own port, so a second controller
 * plays as 2P instead of fighting the first one for player 1. Anything that is not a pad (a
 * keyboard, a TV remote) always plays as 1P.
 */
class GamepadInput(
    context: Context,
    private val onChanged: (port: Int, mask: Int, lx: Int, ly: Int, rx: Int, ry: Int) -> Unit,
    private val onMenu: () -> Unit,
    private val onTurbo: () -> Unit = {},
    /** The other [GamepadMapping.ACTIONS] (빨리감기, 배속, 저장, 불러오기). */
    private val onAction: (Int) -> Unit = {},
) {
    private class DeviceState {
        var keyMask = 0
        /** Keys held down right now, with what each presses; [keyMask] is their union. */
        val held = HashMap<Int, Int>()
        var hatMask = 0
        var triggerMask = 0
        var lx = 0; var ly = 0; var rx = 0; var ry = 0
        val mask: Int get() = keyMask or hatMask or triggerMask
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mappings = HashMap<Int, GamepadMapping>()
    private val states = HashMap<Int, DeviceState>()

    /** deviceId → port, recomputed whenever pads come and go or the player choice changes. */
    private var ports = emptyMap<Int, Int>()
    private var preferredPorts = emptyMap<String, Int>()

    private var deviceListener: InputManager.InputDeviceListener? = null
    private var onDevices: (() -> Unit)? = null

    /** True when a device with gamepad or joystick sources is attached. */
    fun isGamepadConnected(): Boolean = GamepadDevices.connected().isNotEmpty()

    /** Attached pads with the player each one currently drives. */
    fun devices(): List<PadDevice> = GamepadDevices.assign(GamepadDevices.connected(), preferredPorts)

    fun startWatching(onDevicesChanged: () -> Unit) {
        if (deviceListener != null) return
        onDevices = onDevicesChanged
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = forget(deviceId)
            override fun onInputDeviceRemoved(deviceId: Int) = forget(deviceId)
            override fun onInputDeviceChanged(deviceId: Int) = forget(deviceId)
        }
        deviceListener = l
        inputManager.registerInputDeviceListener(l, Handler(Looper.getMainLooper()))
        scope.launch {
            OneEmuApp.get().settings.gamepadPorts.collect {
                preferredPorts = it
                refreshPorts()
                onDevices?.invoke()
            }
        }
        refreshPorts()
    }

    private fun forget(deviceId: Int) {
        mappings.remove(deviceId)
        // A pad that just left must not leave its buttons stuck down on its port.
        val port = ports[deviceId]
        states.remove(deviceId)
        refreshPorts()
        if (port != null) publish(port)
        onDevices?.invoke()
    }

    fun stopWatching() {
        deviceListener?.let { inputManager.unregisterInputDeviceListener(it) }
        deviceListener = null
        onDevices = null
        scope.cancel()
    }

    private fun refreshPorts() {
        ports = GamepadDevices.assign(GamepadDevices.connected(), preferredPorts).associate { it.deviceId to it.port }
    }

    /** Pads play as the player they were given; keyboards and other oddities are always 1P. */
    private fun portFor(deviceId: Int): Int = ports[deviceId] ?: 0

    private fun mappingFor(deviceId: Int): GamepadMapping {
        mappings[deviceId]?.let { return it }
        mappings[deviceId] = GamepadMapping.DEFAULT
        val device = InputDevice.getDevice(deviceId) ?: return GamepadMapping.DEFAULT
        val key = GamepadMapping.deviceKey(device)
        scope.launch {
            val text = OneEmuApp.get().settings.get(Settings.Keys.gamepadMapping(key), "")
            if (text.isNotBlank()) mappings[deviceId] = GamepadMapping.parse(text)
        }
        return GamepadMapping.DEFAULT
    }

    private fun stateFor(deviceId: Int): DeviceState = states.getOrPut(deviceId) { DeviceState() }

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) return isMapped(event)
        val button = mappingFor(event.deviceId).buttonFor(event.keyCode) ?: return false
        if (button in GamepadMapping.ACTIONS) {
            // Pseudo-buttons fire once, on release, whichever player pressed them.
            if (event.action == KeyEvent.ACTION_UP) {
                when (button) {
                    GamepadMapping.MENU -> onMenu()
                    GamepadMapping.TURBO -> onTurbo()
                    else -> onAction(button)
                }
            }
            return true
        }
        val st = stateFor(event.deviceId)
        // Rebuilt from every held key: with 조합 buttons two keys can share a bit (L4 = A+B while A is held),
        // and letting go of one must not release the other.
        if (event.action == KeyEvent.ACTION_DOWN) st.held[event.keyCode] = button else st.held.remove(event.keyCode)
        st.keyMask = st.held.values.fold(0) { acc, b -> acc or b }
        publish(portFor(event.deviceId))
        return true
    }

    private fun isMapped(event: KeyEvent) = mappingFor(event.deviceId).buttonFor(event.keyCode) != null

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val st = stateFor(event.deviceId)
        st.lx = axis(event, device, MotionEvent.AXIS_X)
        st.ly = axis(event, device, MotionEvent.AXIS_Y)
        // Right stick is Z/RZ on most Android pads, RX/RY on a few.
        val hasZ = device.getMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK) != null
        st.rx = axis(event, device, if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        st.ry = axis(event, device, if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var hat = 0
        if (hx < -0.5f) hat = hat or Buttons.LEFT
        if (hx > 0.5f) hat = hat or Buttons.RIGHT
        if (hy < -0.5f) hat = hat or Buttons.UP
        if (hy > 0.5f) hat = hat or Buttons.DOWN
        st.hatMask = hat

        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        var trigger = 0
        if (lt > 0.5f) trigger = trigger or Buttons.L2
        if (rt > 0.5f) trigger = trigger or Buttons.R2
        st.triggerMask = trigger
        publish(portFor(event.deviceId))
        return true
    }

    private fun axis(event: MotionEvent, device: InputDevice, axis: Int): Int {
        val range = device.getMotionRange(axis, event.source) ?: return 0
        var v = event.getAxisValue(axis)
        val flat = maxOf(range.flat, DEAD_ZONE)
        if (abs(v) < flat) return 0
        // Rescale so the dead zone edge maps to 0 and the extreme to ±1.
        val sign = if (v < 0) -1f else 1f
        v = (abs(v) - flat) / (1f - flat)
        return (sign * v.coerceIn(0f, 1f) * 32767f).toInt()
    }

    /** Merges every device sharing [port] — two pads on one player act as one. */
    private fun publish(port: Int) {
        var mask = 0
        var lx = 0; var ly = 0; var rx = 0; var ry = 0
        for ((id, st) in states) {
            if (portFor(id) != port) continue
            mask = mask or st.mask
            if (st.lx != 0 || st.ly != 0) { lx = st.lx; ly = st.ly }
            if (st.rx != 0 || st.ry != 0) { rx = st.rx; ry = st.ry }
        }
        onChanged(port, mask, lx, ly, rx, ry)
    }

    companion object {
        private const val DEAD_ZONE = 0.15f

        /** True when the event comes from something that looks like a controller or keyboard. */
        fun isControllerEvent(event: KeyEvent): Boolean {
            val src = event.source
            return src and InputDevice.SOURCE_GAMEPAD != 0 || src and InputDevice.SOURCE_JOYSTICK != 0 ||
                src and InputDevice.SOURCE_DPAD != 0 || src and InputDevice.SOURCE_KEYBOARD != 0 ||
                KeyEvent.isGamepadButton(event.keyCode)
        }
    }
}
