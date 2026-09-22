package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.emu.input.WiiController
import com.manggome.oneemu.library.RomInfo.DiscPlatform
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wii pad: its own saved layout, its own legend, and the device that makes Dolphin attach a nunchuk. */
class PadProfileTest {
    @Test
    fun `the Wii pad saves under its own key, not the GameCube one`() {
        assertEquals("gc", PadProfile(SystemId.GC).key)
        assertEquals("gc-wii", PadProfile.WIIMOTE.key)
    }

    @Test
    fun `every profile key parses back to the profile it came from`() {
        for (profile in PadProfile.ordered) {
            assertEquals(profile, PadProfile.fromKey(profile.key))
        }
        assertNull(PadProfile.fromKey("nope"))
        assertNull(PadProfile.fromKey(""))
    }

    @Test
    fun `the layout editor offers the Wii pad next to the GameCube one`() {
        val keys = PadProfile.ordered.map { it.key }
        assertEquals(SystemId.entries.size + 1, keys.size)
        assertEquals(keys.indexOf("gc") + 1, keys.indexOf("gc-wii"))
    }

    @Test
    fun `the legend follows Dolphin's Wii Remote wiring, not the GameCube pad`() {
        val wii = PadProfile.WIIMOTE
        // Source/Core/DolphinLibretro/Input.cpp, descWiimoteNunchuk.
        assertEquals("C", PadElementId.BUTTON_X.label(wii))
        assertEquals("Z", PadElementId.BUTTON_Y.label(wii))
        assertEquals("−", PadElementId.L.label(wii))
        assertEquals("+", PadElementId.R.label(wii))
        assertEquals("1", PadElementId.START.label(wii))
        assertEquals("2", PadElementId.SELECT.label(wii))
        assertEquals("HOME", PadElementId.R3.label(wii))
        // A and B keep their names on both.
        assertEquals("A", PadElementId.BUTTON_A.label(wii))
        assertEquals("B", PadElementId.BUTTON_B.label(wii))
    }

    @Test
    fun `the GameCube pad is untouched`() {
        val gc = PadProfile(SystemId.GC)
        assertEquals("X", PadElementId.BUTTON_X.label(gc))
        assertEquals("Y", PadElementId.BUTTON_Y.label(gc))
        assertEquals("START", PadElementId.START.label(gc))
        assertEquals("R3", PadElementId.R3.label(gc))
    }

    @Test
    fun `the Wii layout has HOME, which the GameCube one has no room for`() {
        for (config in ScreenConfig.entries) {
            val wii = DefaultLayouts.forProfile(PadProfile.WIIMOTE, config)
            assertNotNull("$config has no HOME", wii[PadElementId.R3])
            // The nunchuk's stick is how a Wii game is walked around.
            assertNotNull("$config has no stick", wii[PadElementId.LEFT_STICK])
            assertNull(DefaultLayouts.forProfile(PadProfile(SystemId.GC), config)[PadElementId.R3])
        }
    }

    @Test
    fun `a Wii disc gets the nunchuk by itself, a GameCube disc keeps the core default`() {
        // (3 << 8) | RETRO_DEVICE_JOYPAD - RETRO_DEVICE_WIIMOTE_NC in the core.
        assertEquals(769, WiiController.AUTO.deviceFor(DiscPlatform.WII))
        assertEquals(0, WiiController.AUTO.deviceFor(DiscPlatform.GAMECUBE))
        assertEquals(0, WiiController.AUTO.deviceFor(DiscPlatform.UNKNOWN))
        assertTrue(WiiController.AUTO.padProfile(DiscPlatform.WII).isWiimote)
        assertFalse(WiiController.AUTO.padProfile(DiscPlatform.GAMECUBE).isWiimote)
    }

    @Test
    fun `an explicit choice wins whatever the disc looks like`() {
        for (platform in DiscPlatform.entries) {
            assertEquals(1, WiiController.WIIMOTE.deviceFor(platform))
            assertEquals(769, WiiController.NUNCHUK.deviceFor(platform))
            assertEquals(1025, WiiController.CLASSIC.deviceFor(platform))
            assertEquals(1537, WiiController.GAMECUBE.deviceFor(platform))
        }
        // Only the remote-in-hand devices relabel the pad; a classic or GameCube controller is the usual one.
        assertTrue(WiiController.NUNCHUK.padProfile(DiscPlatform.GAMECUBE).isWiimote)
        assertFalse(WiiController.CLASSIC.padProfile(DiscPlatform.WII).isWiimote)
        assertFalse(WiiController.GAMECUBE.padProfile(DiscPlatform.WII).isWiimote)
    }

    @Test
    fun `an unknown stored value falls back to auto`() {
        assertEquals(WiiController.AUTO, WiiController.fromKey(null))
        assertEquals(WiiController.AUTO, WiiController.fromKey(""))
        assertEquals(WiiController.AUTO, WiiController.fromKey("something-else"))
        assertEquals(WiiController.NUNCHUK, WiiController.fromKey("nunchuk"))
    }
}
