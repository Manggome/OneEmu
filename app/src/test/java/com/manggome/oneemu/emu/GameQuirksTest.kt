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

    @Test
    fun `the rule belongs to one core and one system`() {
        assertNull(GameQuirks.forGame("mgba", game("유희왕 월드 챔피언십 2008")))
        assertNull(GameQuirks.forGame("melondsds", game("유희왕 월드 챔피언십 2008", "y.gba", SystemId.GBA)))
    }
}
