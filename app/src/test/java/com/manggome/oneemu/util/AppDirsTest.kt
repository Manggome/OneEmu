package com.manggome.oneemu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Carrying an existing install over to the visible data folder. This runs once against somebody's
 * only copy of their saves, so the rules it must never break are pinned here.
 */
class AppDirsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val names = listOf("saves", "states", "system")
    private lateinit var from: File
    private lateinit var to: File

    private fun seed(path: String, text: String = "data") {
        File(from, path).apply { parentFile?.mkdirs(); writeText(text) }
    }

    @org.junit.Before
    fun setUp() {
        from = temp.newFolder("old")
        to = temp.newFolder("new")
    }

    @Test
    fun `a save is moved across and the original is gone`() {
        seed("saves/gba/Pokemon.srm", "sram")
        val moved = AppDirs.migrateFolders(from, to, names, log = {})
        assertEquals(listOf("saves"), moved)
        assertEquals("sram", File(to, "saves/gba/Pokemon.srm").readText())
        assertFalse(File(from, "saves").exists())
    }

    @Test
    fun `nested folders a core made come along`() {
        seed("saves/gc/User/Wii/shared2/sys/SYSCONF", "wii")
        seed("saves/psx/pcsx-card2.mcd", "card")
        AppDirs.migrateFolders(from, to, names, log = {})
        assertEquals("wii", File(to, "saves/gc/User/Wii/shared2/sys/SYSCONF").readText())
        assertEquals("card", File(to, "saves/psx/pcsx-card2.mcd").readText())
    }

    @Test
    fun `a destination that already exists is left alone, not merged`() {
        seed("saves/gba/old.srm", "old")
        File(to, "saves/gba").mkdirs()
        File(to, "saves/gba/new.srm").writeText("new")

        val moved = AppDirs.migrateFolders(from, to, names, log = {})

        assertTrue(moved.isEmpty())
        assertEquals("new", File(to, "saves/gba/new.srm").readText())
        // Nothing was taken from the old place either, so the user can still get at it.
        assertEquals("old", File(from, "saves/gba/old.srm").readText())
    }

    @Test
    fun `an empty or missing folder is not moved`() {
        File(from, "saves").mkdirs()
        assertTrue(AppDirs.migrateFolders(from, to, names, log = {}).isEmpty())
        assertFalse(File(to, "saves").exists())
        assertFalse(File(to, "states").exists())
    }

    @Test
    fun `each folder is moved on its own`() {
        seed("saves/gba/a.srm")
        seed("system/scph5501.bin")
        val moved = AppDirs.migrateFolders(from, to, names, log = {})
        assertEquals(listOf("saves", "system"), moved)
        assertTrue(File(to, "system/scph5501.bin").isFile)
    }

    @Test
    fun `running it twice does nothing the second time`() {
        seed("saves/gba/a.srm", "first")
        AppDirs.migrateFolders(from, to, names, log = {})
        seed("saves/gba/b.srm", "later")
        assertTrue(AppDirs.migrateFolders(from, to, names, log = {}).isEmpty())
        assertEquals("first", File(to, "saves/gba/a.srm").readText())
        assertEquals("later", File(from, "saves/gba/b.srm").readText())
    }
}
