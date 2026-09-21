package com.manggome.oneemu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deleting a game deletes the files that make it up. Getting this set wrong either leaves orphaned
 * tracks behind or takes something the user did not mean to lose, so the rules are pinned here.
 */
class RomFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(name: String, text: String = "x"): File =
        File(temp.root, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `a plain rom is just itself`() {
        val rom = write("Sonic.md")
        assertEquals(listOf(rom), RomFiles.of(rom))
    }

    @Test
    fun `a cue takes its tracks with it`() {
        val bin = write("game.bin")
        val cue = write("game.cue", "FILE \"game.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        assertEquals(listOf(cue, bin), RomFiles.of(cue))
    }

    @Test
    fun `an unquoted FILE line is understood too`() {
        val bin = write("game.bin")
        write("game.cue", "FILE game.bin BINARY\n")
        assertEquals(listOf("game.bin"), RomFiles.parseCue("FILE game.bin BINARY\n"))
        assertTrue(RomFiles.of(File(temp.root, "game.cue")).contains(bin))
    }

    @Test
    fun `a playlist takes its discs and their tracks`() {
        val bin1 = write("d1.bin")
        val bin2 = write("d2.bin")
        val cue1 = write("d1.cue", "FILE \"d1.bin\" BINARY\n")
        val cue2 = write("d2.cue", "FILE \"d2.bin\" BINARY\n")
        val m3u = write("game.m3u", "#EXTM3U\nd1.cue\n\nd2.cue\n")
        assertEquals(listOf(m3u, cue1, bin1, cue2, bin2), RomFiles.of(m3u))
    }

    @Test
    fun `a playlist cannot reach outside the game's folder`() {
        val outside = File(temp.root.parentFile, "keep-me-${System.nanoTime()}.bin").apply { writeText("x") }
        try {
            val m3u = write("evil.m3u", "../${outside.name}\n/etc/hosts\n")
            assertEquals(listOf(m3u), RomFiles.of(m3u))
            assertTrue(outside.exists())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a file listed twice is only counted once`() {
        val bin = write("shared.bin")
        val cue = write("two.cue", "FILE \"shared.bin\" BINARY\nFILE \"shared.bin\" BINARY\n")
        assertEquals(listOf(cue, bin), RomFiles.of(cue))
    }

    @Test
    fun `a reference to something that is not there is skipped`() {
        val cue = write("missing.cue", "FILE \"gone.bin\" BINARY\n")
        assertEquals(listOf(cue), RomFiles.of(cue))
    }

    @Test
    fun `comments and blank lines in a playlist are not files`() {
        val parsed = RomFiles.parseM3u("#EXTM3U\n\n  disc1.cue  \n# a note\ndisc2.cue\n")
        assertEquals(listOf("disc1.cue", "disc2.cue"), parsed)
        assertFalse(parsed.any { it.startsWith("#") })
    }
}
