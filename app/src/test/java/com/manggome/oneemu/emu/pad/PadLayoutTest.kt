package com.manggome.oneemu.emu.pad

import org.junit.Assert.assertEquals
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
    fun `mirroring keeps every element, hidden ones included`() {
        val before = layout(
            PadElement(PadElementId.DPAD, 0.2f, 0.8f),
            PadElement(PadElementId.TURBO, 0.9f, 0.3f, visible = false),
        )
        val after = before.mirrored()
        assertEquals(before.elements.size, after.elements.size)
        assertTrue(after.elements.none { it.visible && it.id == PadElementId.TURBO })
    }
}
