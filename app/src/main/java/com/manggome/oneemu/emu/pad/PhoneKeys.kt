package com.manggome.oneemu.emu.pad

/**
 * Feature-phone keypad bits for NativeBridge.setPhoneKeys. The native frontend reports them to the core
 * as keyboard keys (kPhoneKeys in frontend.cpp: 0-9, *, #, F1/F2 soft keys, Backspace, Enter, F3/F4),
 * which is what the WIPI core reads for the keys a RetroPad doesn't have.
 */
object PhoneKeys {
    const val D0 = 1 shl 0
    const val D1 = 1 shl 1
    const val D2 = 1 shl 2
    const val D3 = 1 shl 3
    const val D4 = 1 shl 4
    const val D5 = 1 shl 5
    const val D6 = 1 shl 6
    const val D7 = 1 shl 7
    const val D8 = 1 shl 8
    const val D9 = 1 shl 9
    const val STAR = 1 shl 10
    const val HASH = 1 shl 11
    const val LSK = 1 shl 12
    const val RSK = 1 shl 13
    const val CLR = 1 shl 14
    const val OK = 1 shl 15
    const val CALL = 1 shl 16
    const val END = 1 shl 17
    const val UP = 1 shl 18
    const val DOWN = 1 shl 19
    const val LEFT = 1 shl 20
    const val RIGHT = 1 shl 21

    /** The 3x4 number grid. */
    const val GRID = 0xFFF

    /** A hardware keyboard key (BT keyboard, keypad phones) as a phone key bit, or 0. */
    fun fromKeyCode(keyCode: Int): Int = when (keyCode) {
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> 1 shl (keyCode - android.view.KeyEvent.KEYCODE_0)
        in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> 1 shl (keyCode - android.view.KeyEvent.KEYCODE_NUMPAD_0)
        android.view.KeyEvent.KEYCODE_STAR, android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> STAR
        android.view.KeyEvent.KEYCODE_POUND, android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE -> HASH
        android.view.KeyEvent.KEYCODE_F1, android.view.KeyEvent.KEYCODE_SOFT_LEFT -> LSK
        android.view.KeyEvent.KEYCODE_F2, android.view.KeyEvent.KEYCODE_SOFT_RIGHT -> RSK
        android.view.KeyEvent.KEYCODE_DEL -> CLR
        android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_SPACE -> OK
        android.view.KeyEvent.KEYCODE_F3, android.view.KeyEvent.KEYCODE_CALL -> CALL
        android.view.KeyEvent.KEYCODE_F4, android.view.KeyEvent.KEYCODE_ENDCALL -> END
        else -> 0
    }
}
