package com.manggome.oneemu.emu.input

import com.manggome.oneemu.emu.EmulatorSession.Buttons
import org.junit.Assert.assertEquals
import org.junit.Test

class StickDpadTest {
    @Test fun restingThumbPressesNothing() {
        assertEquals(0, StickDpad.mask(0, 0))
        assertEquals(0, StickDpad.mask(8000, -8000))
    }

    @Test fun cardinalDirections() {
        assertEquals(Buttons.LEFT, StickDpad.mask(-32768, 0))
        assertEquals(Buttons.RIGHT, StickDpad.mask(32767, 0))
        assertEquals(Buttons.UP, StickDpad.mask(0, -32768))
        assertEquals(Buttons.DOWN, StickDpad.mask(0, 32767))
    }

    @Test fun diagonalPressesBoth() {
        assertEquals(Buttons.UP or Buttons.RIGHT, StickDpad.mask(23170, -23170))
    }
}
