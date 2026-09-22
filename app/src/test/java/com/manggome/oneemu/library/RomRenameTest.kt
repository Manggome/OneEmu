package com.manggome.oneemu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Working out the new file name: the part that decides whether a rename is even possible. */
class RomRenameTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun src(name: String) = File(tmp.root, name)

    @Test fun `the extension is kept, because the scanner reads the system off it`() {
        assertEquals("Sonic.md", RomRename.targetFor(src("소닉.md"), "Sonic")?.name)
        assertEquals("Tekken 3.cue", RomRename.targetFor(src("tk3.cue"), "Tekken 3")?.name)
    }

    @Test fun `it lands next to the file it renames, not somewhere else`() {
        val f = src("a.nes")
        assertEquals(f.parentFile, RomRename.targetFor(f, "b")?.parentFile)
    }

    @Test fun `characters a file name cannot hold are replaced, not dropped silently`() {
        // AppDirs.sanitize turns each of these into "_", so the name survives in a readable form.
        assertEquals("Ratchet _ Clank_ Up.iso", RomRename.targetFor(src("r.iso"), "Ratchet / Clank: Up")?.name)
    }

    @Test fun `a name with nothing usable in it is refused`() {
        assertNull(RomRename.targetFor(src("a.nes"), ""))
        assertNull(RomRename.targetFor(src("a.nes"), "   "))
        assertNull(RomRename.targetFor(src("a.nes"), "..."))
    }

    @Test fun `a file without an extension stays without one`() {
        assertEquals("newname", RomRename.targetFor(src("oldname"), "newname")?.name)
    }

    @Test fun `renaming to the same name is a no-op the caller can spot`() {
        val f = src("game.gba")
        assertEquals(f.absolutePath, RomRename.targetFor(f, "game")?.absolutePath)
    }
}
