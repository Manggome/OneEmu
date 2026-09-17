package com.manggome.oneemu.emu

import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.pad.PadElement
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAndLayoutTest {
    @Test fun speedCycleWalksEveryStepAndWrapsToNormal() {
        val seen = generateSequence(1) { Settings.nextSpeed(it) }.take(Settings.SPEED_CYCLE.size + 1).toList()
        assertEquals(listOf(1, 2, 3, 5, 10, 0, 1), seen)
    }

    @Test fun unknownSpeedContinuesAtTheSecondStep() = assertEquals(2, Settings.nextSpeed(4))

    @Test fun withElementAddsAMissingControlAndRestoresADeletedOne() {
        val empty = PadLayout(listOf(PadElement(PadElementId.DPAD, 0.1f, 0.6f)))
        val added = empty.withElement(PadElementId.SPEED, 0.9f, 0.2f)
        assertEquals(2, added.elements.size)
        assertEquals(0.9f, added[PadElementId.SPEED]!!.x, 0.0001f)

        val hidden = added.update(PadElementId.SPEED) { it.copy(visible = false) }
        val restored = hidden.withElement(PadElementId.SPEED, 0.5f, 0.5f)
        assertEquals(2, restored.elements.size) // no duplicate
        assertTrue(restored[PadElementId.SPEED]!!.visible)
        assertEquals(0.9f, restored[PadElementId.SPEED]!!.x, 0.0001f) // keeps where the user put it
    }
}
