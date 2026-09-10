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
 * Key code → libretro button mapping for a physical controller. [MENU] is a pseudo-button that
 * opens the in-game menu. Overrides are stored per device as "keycode=BUTTON\n" lines where BUTTON
 * is a name from [buttonNames] (or a raw bit index).
 */
data class GamepadMapping(val keys: Map<Int, Int>) {
    fun buttonFor(keyCode: Int): Int? = keys[keyCode]

    fun serialize(): String = keys.entries.joinToString("\n") { (k, v) -> "$k=${nameOf(v)}" }

    companion object {
        const val MENU = 1 shl 30

        val buttonNames: Map<String, Int> = mapOf(
            "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
            "L" to Buttons.L, "R" to Buttons.R, "L2" to Buttons.L2, "R2" to Buttons.R2,
            "L3" to Buttons.L3, "R3" to Buttons.R3, "START" to Buttons.START, "SELECT" to Buttons.SELECT,
            "UP" to Buttons.UP, "DOWN" to Buttons.DOWN, "LEFT" to Buttons.LEFT, "RIGHT" to Buttons.RIGHT,
            "MENU" to MENU,
        )

        fun nameOf(button: Int): String = buttonNames.entries.firstOrNull { it.value == button }?.key ?: button.toString()

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
        fun parse(text: String, base: GamepadMapping = DEFAULT): GamepadMapping {
            if (text.isBlank()) return base
            val map = base.keys.toMutableMap()
            for (line in text.lineSequence()) {
                val key = line.substringBefore('=').trim().toIntOrNull() ?: continue
                val value = line.substringAfter('=', "").trim()
                if (value.isEmpty()) { map.remove(key); continue }
                val button = buttonNames[value.uppercase()] ?: value.toIntOrNull()?.let { if (it < 16) 1 shl it else it } ?: continue
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
 * sticks. Feed it from Activity.dispatchKeyEvent / dispatchGenericMotionEvent.
 */
class GamepadInput(
    context: Context,
    private val onChanged: (mask: Int, lx: Int, ly: Int, rx: Int, ry: Int) -> Unit,
    private val onMenu: () -> Unit,
) {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mappings = HashMap<Int, GamepadMapping>()

    private var keyMask = 0
    private var hatMask = 0
    private var triggerMask = 0
    private var lx = 0; private var ly = 0; private var rx = 0; private var ry = 0

    var mask: Int = 0
        private set

    private var deviceListener: InputManager.InputDeviceListener? = null

    /** True when a device with gamepad or joystick sources is attached. */
    fun isGamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        // Compare whole source constants: a bare `and != 0` also matches keyboards via SOURCE_CLASS_BUTTON.
        !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
    }

    fun startWatching(onDevicesChanged: () -> Unit) {
        if (deviceListener != null) return
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { mappings.remove(deviceId); onDevicesChanged() }
            override fun onInputDeviceRemoved(deviceId: Int) { mappings.remove(deviceId); onDevicesChanged() }
            override fun onInputDeviceChanged(deviceId: Int) { mappings.remove(deviceId); onDevicesChanged() }
        }
        deviceListener = l
        inputManager.registerInputDeviceListener(l, Handler(Looper.getMainLooper()))
    }

    fun stopWatching() {
        deviceListener?.let { inputManager.unregisterInputDeviceListener(it) }
        deviceListener = null
        scope.cancel()
    }

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

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) return isMapped(event)
        val button = mappingFor(event.deviceId).buttonFor(event.keyCode) ?: return false
        if (button == GamepadMapping.MENU) {
            if (event.action == KeyEvent.ACTION_UP) onMenu()
            return true
        }
        keyMask = if (event.action == KeyEvent.ACTION_DOWN) keyMask or button else keyMask and button.inv()
        publish()
        return true
    }

    private fun isMapped(event: KeyEvent) = mappingFor(event.deviceId).buttonFor(event.keyCode) != null

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0 || event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        lx = axis(event, device, MotionEvent.AXIS_X)
        ly = axis(event, device, MotionEvent.AXIS_Y)
        // Right stick is Z/RZ on most Android pads, RX/RY on a few.
        val hasZ = device.getMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK) != null
        rx = axis(event, device, if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        ry = axis(event, device, if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatMask = 0
        if (hx < -0.5f) hatMask = hatMask or Buttons.LEFT
        if (hx > 0.5f) hatMask = hatMask or Buttons.RIGHT
        if (hy < -0.5f) hatMask = hatMask or Buttons.UP
        if (hy > 0.5f) hatMask = hatMask or Buttons.DOWN

        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerMask = 0
        if (lt > 0.5f) triggerMask = triggerMask or Buttons.L2
        if (rt > 0.5f) triggerMask = triggerMask or Buttons.R2
        publish()
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

    private fun publish() {
        mask = keyMask or hatMask or triggerMask
        onChanged(mask, lx, ly, rx, ry)
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
