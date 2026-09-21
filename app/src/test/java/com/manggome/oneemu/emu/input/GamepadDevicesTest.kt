package com.manggome.oneemu.emu.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which controller is which player: the rule a second pad relies on to become 2P. */
class GamepadDevicesTest {
    private fun pad(id: Int, key: String = "pad$id") = PadDevice(id, "Pad $id", key, port = 0)

    private fun ports(pads: List<PadDevice>, preferred: Map<String, Int> = emptyMap()): List<Int> =
        GamepadDevices.assignPorts(pads, preferred).map { it.port }

    @Test
    fun `pads take players in the order they connected`() {
        assertEquals(listOf(0, 1, 2), ports(listOf(pad(1), pad(2), pad(3))))
    }

    @Test
    fun `a single pad is always player one`() {
        assertEquals(listOf(0), ports(listOf(pad(9))))
    }

    @Test
    fun `a pinned pad keeps its player and the others fill the gaps`() {
        val pads = listOf(pad(1, "a"), pad(2, "b"), pad(3, "c"))
        // "a" is pinned to 2P, so "b" and "c" take the slots left over, still in order.
        assertEquals(listOf(1, 0, 2), ports(pads, mapOf("a" to 2)))
    }

    @Test
    fun `pinning the last pad to player one pushes the first one along`() {
        val pads = listOf(pad(1, "a"), pad(2, "b"))
        assertEquals(listOf(1, 0), ports(pads, mapOf("b" to 1)))
    }

    @Test
    fun `two pads pinned to the same player do not both get it`() {
        val pads = listOf(pad(1, "a"), pad(2, "b"))
        assertEquals(listOf(0, 1), ports(pads, mapOf("a" to 1, "b" to 1)))
    }

    @Test
    fun `an out of range pin is ignored rather than losing the pad`() {
        assertEquals(listOf(0), ports(listOf(pad(1, "a")), mapOf("a" to 99)))
        assertEquals(listOf(0), ports(listOf(pad(1, "a")), mapOf("a" to GamepadDevices.AUTO)))
    }

    @Test
    fun `more pads than players leaves the extras on the last one instead of dead`() {
        val pads = (1..6).map { pad(it) }
        assertEquals(listOf(0, 1, 2, 3, 3, 3), ports(pads))
    }
}
