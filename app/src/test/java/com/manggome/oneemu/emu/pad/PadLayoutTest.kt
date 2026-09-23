package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout edits that are easy to get subtly wrong and impossible to see in a screenshot. */
class PadLayoutTest {
    private fun layout(vararg els: PadElement) = PadLayout(els.toList())

    @Test
    fun `mirroring swaps left and right but leaves height and size alone`() {
        val before = layout(
            PadElement(PadElementId.DPAD, x = 0.2f, y = 0.8f, scale = 1.3f),
            PadElement(PadElementId.ABXY_CLUSTER, x = 0.8f, y = 0.8f),
        )
        val after = before.mirrored()
        assertEquals(0.8f, after[PadElementId.DPAD]!!.x, 1e-6f)
        assertEquals(0.2f, after[PadElementId.ABXY_CLUSTER]!!.x, 1e-6f)
        assertEquals(0.8f, after[PadElementId.DPAD]!!.y, 1e-6f)
        assertEquals(1.3f, after[PadElementId.DPAD]!!.scale, 1e-6f)
    }

    @Test
    fun `mirroring twice is the layout you started with`() {
        val before = layout(
            PadElement(PadElementId.DPAD, x = 0.17f, y = 0.8f),
            PadElement(PadElementId.MENU, x = 0.5f, y = 0.05f, visible = false),
        )
        val back = before.mirrored().mirrored()
        // Compared with a tolerance: 1 - (1 - 0.17) is not bit-for-bit 0.17 in float.
        assertEquals(before.elements.map { it.id }, back.elements.map { it.id })
        before.elements.forEachIndexed { i, e ->
            assertEquals(e.x, back.elements[i].x, 1e-5f)
            assertEquals(e.y, back.elements[i].y, 1e-6f)
            assertEquals(e.visible, back.elements[i].visible)
        }
    }

    @Test
    fun `an element on the centre line stays on it`() {
        val centred = layout(PadElement(PadElementId.START, x = 0.5f, y = 0.9f))
        assertEquals(0.5f, centred.mirrored()[PadElementId.START]!!.x, 1e-6f)
    }

    @Test
    fun `the DS pad carries the two stick clicks melonDS uses`() {
        // L3 is the microphone and R3 cycles the screen layout; without them a DS game cannot blow
        // into the mic or move the touch screen.
        for (config in ScreenConfig.entries) {
            val nds = DefaultLayouts.forSystem(SystemId.NDS, config)
            assertNotNull("$config has no L3", nds[PadElementId.L3])
            assertNotNull("$config has no R3", nds[PadElementId.R3])
        }
    }

    @Test
    fun `other systems are not given stick clicks they have no use for`() {
        val gba = DefaultLayouts.forSystem(SystemId.GBA, ScreenConfig.PORTRAIT)
        assertNull(gba[PadElementId.L3])
        assertNull(gba[PadElementId.R3])
    }

    @Test
    fun `the DS labels say what the buttons actually do`() {
        assertEquals("마이크", PadElementId.L3.label(SystemId.NDS))
        assertEquals("화면", PadElementId.R3.label(SystemId.NDS))
        // Every other system gets the plain name, since the function is the core's, not ours.
        assertEquals("L3", PadElementId.L3.label(SystemId.PSX))
        assertEquals("R3", PadElementId.R3.label(SystemId.PSX))
    }

    @Test
    fun `mirroring keeps every element, hidden ones included`() {
        val before = layout(
            PadElement(PadElementId.DPAD, 0.2f, 0.8f),
            PadElement(PadElementId.TURBO, 0.9f, 0.3f, visible = false),
        )
        val after = before.mirrored()
        assertEquals(before.elements.size, after.elements.size)
        assertTrue(after.elements.none { it.visible && it.id == PadElementId.TURBO })
    }

    @Test
    fun `a button saved under the navigation bar is nudged back into reach`() {
        val screen = androidx.compose.ui.geometry.Size(1000f, 2000f)
        val bar = PadInsets(bottom = 150f)
        val start = PadElement(PadElementId.START, x = 0.5f, y = 0.98f)
        // 0.98 of 2000 is 1960, and a 34dp pill reaches past 1960: without the bar it sits there,
        // with the bar it stops at its top edge.
        val free = start.rectOn(screen, density = 1f, globalScale = 1f)
        val clamped = start.rectOn(screen, density = 1f, globalScale = 1f, insets = bar)
        assertTrue(free.bottom > screen.height - bar.bottom)
        assertTrue(clamped.bottom <= screen.height - bar.bottom + 0.01f)
        assertEquals(free.width, clamped.width, 1e-3f)
        assertEquals(free.center.x, clamped.center.x, 1e-3f)
    }

    @Test
    fun `a button that already clears the bars is left exactly where it was`() {
        val screen = androidx.compose.ui.geometry.Size(1000f, 2000f)
        val middle = PadElement(PadElementId.BUTTON_A, x = 0.5f, y = 0.5f)
        val free = middle.rectOn(screen, 1f, 1f)
        val clamped = middle.rectOn(screen, 1f, 1f, PadInsets(bottom = 150f, top = 80f))
        assertEquals(free, clamped)
    }

    @Test
    fun `a control taller than the space left over is centred in it, not pushed off screen`() {
        val screen = androidx.compose.ui.geometry.Size(1000f, 300f)
        val huge = PadElement(PadElementId.DPAD, x = 0.5f, y = 0.9f)
        val r = huge.rectOn(screen, density = 3f, globalScale = 1f, insets = PadInsets(top = 100f, bottom = 100f))
        assertEquals(150f, r.center.y, 0.01f)
    }

    @Test
    fun `a layout saved with the old per-stick d-pad flag still loads`() {
        val old = """{"elements":[{"id":"LEFT_STICK","x":0.3,"y":0.8,"scale":1.0,"visible":true,"dpadToo":true}]}"""
        val parsed = PadLayout.fromJson(old)!!
        assertEquals(1, parsed.elements.size)
        assertEquals(0.3f, parsed[PadElementId.LEFT_STICK]!!.x, 0.001f)
    }
}
