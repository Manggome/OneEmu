package com.manggome.oneemu.emu.input

import android.view.KeyEvent

/**
 * Lets the "press a button" dialog in the settings read one raw key press. An activity forwards its
 * key events here before its own handling; while nothing is listening every event passes straight
 * through.
 */
object GamepadCapture {
    /** Set while the dialog is open. Returns true to take the press. */
    @Volatile
    var onKey: ((KeyEvent) -> Boolean)? = null

    private var consumedDown: Int? = null

    /** True when the event was captured and must not reach the rest of the app. */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            // Swallow the release of a press we took, or it also activates whatever has focus.
            if (consumedDown == event.keyCode) { consumedDown = null; return true }
            return false
        }
        val handler = onKey ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (!handler(event)) return false
        consumedDown = event.keyCode
        return true
    }

    /** Key name as the mapping screen prints it: "BUTTON_A", "DPAD_UP", … */
    fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
}
