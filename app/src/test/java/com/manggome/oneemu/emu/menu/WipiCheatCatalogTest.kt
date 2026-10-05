package com.manggome.oneemu.emu.menu

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WipiCheatCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the shipped catalog parses and every code has a place for the number`() {
        // Unit tests run from the app module; the catalog lives with the WIPI core.
        val file = listOf(File("../cores/wipi/assets/cheats.json"), File("cores/wipi/assets/cheats.json")).first { it.isFile }
        val catalog = json.decodeFromString<WipiCheatCatalog.Catalog>(file.readText())
        assertTrue(catalog.games.isNotEmpty())
        for (game in catalog.games) {
            // a dump without a descriptor has no PID and is found by its file instead
            assertTrue(game.title, (game.pid.isNotBlank() || game.files.isNotEmpty()) && game.cheats.isNotEmpty())
            for (preset in game.cheats) {
                assertTrue(preset.code, preset.code.contains(WipiCheatCatalog.VALUE))
                assertTrue(preset.name, preset.lowest <= preset.highest)
                preset.default?.let { assertTrue(preset.name, it in preset.lowest..preset.highest) }
            }
        }
    }

    @Test
    fun `a filled code is recognized again with its number`() {
        val preset = WipiCheatCatalog.Preset(name = "별", code = "P:015087C0:{v}", max = 99999999)
        assertEquals("P:015087C0:77777", preset.fill(77777))
        assertEquals(77777L, preset.valueIn("P:015087C0:77777"))
        assertEquals(5L, preset.valueIn(" P:015087C0:5 "))
        assertNull(preset.valueIn("P:015087C4:77777"))
        assertNull(preset.valueIn("once:P:015087C0:77777"))
        assertEquals("once:P:015087C0:5", preset.fillOnce(5))
        assertEquals("once:P:1:7+once:P:2:7", WipiCheatCatalog.Preset(name = "x", code = "P:1:{v}+ P:2:{v}").fillOnce(7))
        assertEquals(Int.MAX_VALUE.toLong(), WipiCheatCatalog.Preset(name = "x", code = "J:a.b:{v}").highest)
        assertEquals(0xFFL, WipiCheatCatalog.Preset(name = "x", code = "J:a.b:{v}", type = "u8").highest)
    }
}
