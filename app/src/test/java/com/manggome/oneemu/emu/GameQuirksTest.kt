package com.manggome.oneemu.emu

import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Options one specific game needs. A rule that fires on the wrong game is worse than no rule. */
class GameQuirksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun game(title: String, fileName: String = "$title.nds", system: SystemId = SystemId.NDS, code: String? = null) =
        GameEntity(
            id = 1,
            path = tmp.newFile(fileName).also { f ->
                val header = ByteArray(0x20)
                code?.toByteArray(Charsets.US_ASCII)?.copyInto(header, 0x0C)
                f.writeBytes(header)
            }.absolutePath,
            title = title,
            system = system.id,
        )

    @Test
    fun `Yu-Gi-Oh 2008 asks melonDS for German, which is the language it shows Korean in`() {
        val quirk = GameQuirks.forGame("melondsds", game("유희왕 월드 챔피언십 2008"))
        assertNotNull(quirk)
        assertEquals("de", quirk!!.options["melonds_firmware_language"])
        assertTrue(quirk.note.isNotBlank())
    }

    @Test
    fun `the romanised spellings are matched too`() {
        for (title in listOf("Yu-Gi-Oh! World Championship 2008", "Yugioh WC 2008", "yu gi oh 2008")) {
            assertEquals("de", GameQuirks.optionsFor("melondsds", game(title))["melonds_firmware_language"])
        }
    }

    @Test
    fun `the header game code alone is enough, whatever the file was renamed to`() {
        assertEquals("de", GameQuirks.optionsFor("melondsds", game("dump", "dump.nds", code = "AYWK"))["melonds_firmware_language"])
    }

    @Test
    fun `another year of the same series is left alone`() {
        assertNull(GameQuirks.forGame("melondsds", game("유희왕 월드 챔피언십 2009")))
        assertNull(GameQuirks.forGame("melondsds", game("Yu-Gi-Oh! World Championship 2007")))
    }

    @Test
    fun `an unrelated game gets nothing`() {
        assertTrue(GameQuirks.optionsFor("melondsds", game("포켓몬스터 하트골드")).isEmpty())
        assertTrue(GameQuirks.optionsFor("melondsds", game("2008 마작")).isEmpty())
    }

    private fun ps2(title: String, fileName: String = "$title.iso") = game(title, fileName, SystemId.PS2)

    @Test
    fun `Dynasty Warriors 4 Xtreme Legends starts ARMSX2 on the speed preset`() {
        val quirk = GameQuirks.forGame("armsx2", ps2("진・삼국무쌍 4 맹장전"))
        assertNotNull(quirk)
        assertEquals("75%", quirk!!.options["armsx2_ee_cycle_rate"])
        assertEquals("mild", quirk.options["armsx2_ee_cycle_skip"])
        assertEquals("Minimum", quirk.options["armsx2_blending_accuracy"])
        assertTrue(quirk.note.isNotBlank())
    }

    @Test
    fun `its other spellings and a serial-named dump are matched`() {
        for (title in listOf(
            "진삼국무쌍4 맹장전", "진 삼국무쌍 4 맹장전 (한글판)", "Jin Samguk Mussang 4 - Maengjangjeon",
            "Shin Sangoku Musou 4 - Moushouden", "Dynasty Warriors 4 - Xtreme Legends (USA)", "真・三國無双4 猛将伝",
        )) {
            assertEquals(title, "mild", GameQuirks.optionsFor("armsx2", ps2(title))["armsx2_ee_cycle_skip"])
        }
        assertEquals("mild", GameQuirks.optionsFor("armsx2", ps2("dump", "SLKA-25329.iso"))["armsx2_ee_cycle_skip"])
        assertEquals("mild", GameQuirks.optionsFor("armsx2", ps2("dump", "slka_25329.chd"))["armsx2_ee_cycle_skip"])
    }

    @Test
    fun `the rest of the series is left alone`() {
        for (title in listOf(
            "진삼국무쌍 4", "진삼국무쌍 4 엠파이어스", "Dynasty Warriors 5 - Xtreme Legends", "진삼국무쌍 3 맹장전",
            "Dynasty Warriors 4", "Shin Sangoku Musou 40 Moushouden",
        )) {
            assertNull(title, GameQuirks.forGame("armsx2", ps2(title)))
        }
        assertNull(GameQuirks.forGame("armsx2", ps2("dump", "SLKA-253290.iso")))
    }

    @Test
    fun `the PS2 preset is ARMSX2's alone`() {
        assertNull(GameQuirks.forGame("play", ps2("진・삼국무쌍 4 맹장전")))
        assertNull(GameQuirks.forGame("armsx2", game("진・삼국무쌍 4 맹장전", "x.nds")))
    }

    @Test
    fun `the rule belongs to one core and one system`() {
        assertNull(GameQuirks.forGame("mgba", game("유희왕 월드 챔피언십 2008")))
        assertNull(GameQuirks.forGame("melondsds", game("유희왕 월드 챔피언십 2008", "y.gba", SystemId.GBA)))
    }
}
