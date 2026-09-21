package com.manggome.oneemu.emu.input

import android.view.KeyEvent
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Remapping a controller. Only the differences from the defaults are stored, so a mapping has to
 * survive the round trip through that text — including a default the user deliberately cleared.
 */
class GamepadMappingTest {
    private fun roundTrip(mapping: GamepadMapping) = GamepadMapping.parse(mapping.overridesOf())

    @Test
    fun `an untouched mapping stores nothing`() {
        assertEquals("", GamepadMapping.DEFAULT.overridesOf())
    }

    @Test
    fun `swapping A and B survives being written out and read back`() {
        val swapped = GamepadMapping.DEFAULT
            .rebind(Buttons.A, KeyEvent.KEYCODE_BUTTON_B)
            .rebind(Buttons.B, KeyEvent.KEYCODE_BUTTON_A)
        val back = roundTrip(swapped)
        assertEquals(Buttons.A, back.buttonFor(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Buttons.B, back.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `rebinding takes the key away from whatever used to press it`() {
        val moved = GamepadMapping.DEFAULT.rebind(Buttons.A, KeyEvent.KEYCODE_BUTTON_R1)
        // R1 was the R button; it is now A, and nothing else still presses A.
        assertEquals(Buttons.A, moved.buttonFor(KeyEvent.KEYCODE_BUTTON_R1))
        assertNull(moved.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, moved.keyFor(Buttons.A))
    }

    @Test
    fun `a cleared default stays cleared after the round trip`() {
        val cleared = GamepadMapping(GamepadMapping.DEFAULT.keys.filterValues { it != Buttons.SELECT })
        assertNull(roundTrip(cleared).buttonFor(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun `the menu and turbo pseudo-buttons can be bound like any other`() {
        val mapped = GamepadMapping.DEFAULT.rebind(GamepadMapping.TURBO, KeyEvent.KEYCODE_BUTTON_THUMBL)
        assertEquals(GamepadMapping.TURBO, roundTrip(mapped).buttonFor(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals("TURBO", GamepadMapping.nameOf(GamepadMapping.TURBO))
    }

    @Test
    fun `a line naming a button we do not know is skipped, not crashed on`() {
        // 9999 is no key Android defines, and NOPE is no button we have.
        val parsed = GamepadMapping.parse("9999=NOPE\n${KeyEvent.KEYCODE_BUTTON_C}=X")
        assertNull(parsed.buttonFor(9999))
        assertEquals(Buttons.X, parsed.buttonFor(KeyEvent.KEYCODE_BUTTON_C))
    }
}
