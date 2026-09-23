package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.emu.ScreenConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.abs

class FreeSpotTest {
    private val stick = PadElement(PadElementId.RIGHT_STICK, 0.78f, 0.84f, 0.95f)

    @Test
    fun keepsTheDefaultWhenItIsFree() {
        val placed = FreeSpot.place(stick, listOf(PadElement(PadElementId.BUTTON_A, 0.2f, 0.5f)), ScreenConfig.PORTRAIT)
        assertEquals(stick, placed)
    }

    @Test
    fun movesOffAButtonThatIsInTheWay() {
        // A layout saved before the aim stick existed, with B dragged to exactly where the stick goes.
        val b = PadElement(PadElementId.BUTTON_B, 0.78f, 0.84f)
        val placed = FreeSpot.place(stick, listOf(b), ScreenConfig.PORTRAIT)
        assertNotEquals(stick, placed)
        // Clear of B on a 412x915 dp screen: centres further apart than the two half-sizes.
        val dx = abs(placed.x - b.x) * 412f
        val dy = abs(placed.y - b.y) * 915f
        assert(dx > (120f * 0.95f + 62f) / 2f || dy > (120f * 0.95f + 62f) / 2f)
        // Still in the pad's half of a portrait screen.
        assert(placed.y >= 0.40f)
    }

    @Test
    fun hiddenControlsAreLeftAlone() {
        val hidden = stick.copy(visible = false)
        assertEquals(hidden, FreeSpot.place(hidden, listOf(PadElement(PadElementId.BUTTON_B, 0.78f, 0.84f)), ScreenConfig.PORTRAIT))
    }
}
