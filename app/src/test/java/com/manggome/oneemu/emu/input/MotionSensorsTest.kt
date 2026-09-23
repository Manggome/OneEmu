package com.manggome.oneemu.emu.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame the Wii Remote is fed in. Dolphin reads the libretro sensors as the remote's own axes:
 * +X to its left, +Y back towards the player, +Z out of its button face, with "+Z swings the nose left"
 * and "+X pitches it down". Get a sign wrong and the pointer runs the other way to the phone.
 */
class MotionSensorsTest {
    // android.view.Surface constants, spelled out so the test needs no Android.
    private val r0 = 0
    private val r90 = 1
    private val r180 = 2
    private val r270 = 3

    private fun remote(x: Float, y: Float, z: Float, rot: Int) = MotionSensors.toRemote(x, y, z, rot)

    @Test fun `a phone held upright facing the player is a remote lying flat, aimed at the screen`() {
        // Gravity's reaction points up the screen; for the remote that is out of its button face.
        assertArrayEquals(floatArrayOf(-0f, 0f, 1f), remote(0f, 1f, 0f, r0), 1e-6f)
    }

    @Test fun `in landscape the same pose still reads as flat and level`() {
        // Turned 90 degrees, the phone's own +X is what points up the screen.
        assertArrayEquals(floatArrayOf(-0f, 0f, 1f), remote(1f, 0f, 0f, r90), 1e-6f)
        assertArrayEquals(floatArrayOf(-0f, 0f, 1f), remote(-1f, 0f, 0f, r270), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), remote(0f, -1f, 0f, r180), 1e-6f)
    }

    @Test fun `turning the phone right swings the remote's nose right`() {
        // Turning right is a negative rotation about "up the screen" - device +X in landscape.
        val w = remote(-1f, 0f, 0f, r90)
        assertTrue("yaw should be negative (nose right), was ${w[2]}", w[2] < 0f)
    }

    @Test fun `tipping the far edge up pitches the remote up`() {
        // A positive rotation about "right on the screen" lifts the nose (into the glass) upwards;
        // the core reads +X as pitch down, so this must come out negative.
        val w = remote(1f, 0f, 0f, r0)
        assertTrue("pitch should be negative (nose up), was ${w[0]}", w[0] < 0f)
    }

    @Test fun `rolling the phone rolls the remote about its long axis`() {
        // Out of the glass is towards the player: the remote's +Y.
        assertArrayEquals(floatArrayOf(-0f, 1f, 0f), remote(0f, 0f, 1f, r90), 1e-6f)
    }
}
