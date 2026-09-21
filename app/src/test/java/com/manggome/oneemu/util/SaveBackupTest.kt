package com.manggome.oneemu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A backup zip is an ordinary file a user can edit, so restoring one must never write outside the
 * save folders.
 */
class SaveBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val base: File get() = temp.root.resolve("saves").apply { mkdirs() }

    @Test
    fun `an ordinary entry lands under the folder`() {
        val target = SaveBackup.safeTarget(base, "gba/Pokemon.srm")
        assertNotNull(target)
        assertEquals(File(base, "gba/Pokemon.srm").canonicalPath, target!!.canonicalPath)
    }

    @Test
    fun `climbing out of the folder is refused`() {
        assertNull(SaveBackup.safeTarget(base, "../../evil.so"))
        assertNull(SaveBackup.safeTarget(base, "gba/../../../evil.so"))
    }

    @Test
    fun `an absolute path is refused`() {
        assertNull(SaveBackup.safeTarget(base, "/data/data/com.manggome.oneemu/evil"))
    }

    @Test
    fun `an entry naming the folder itself is refused`() {
        assertNull(SaveBackup.safeTarget(base, ""))
        assertNull(SaveBackup.safeTarget(base, "."))
    }

    @Test
    fun `shader caches and logs stay out of the backup`() {
        assertTrue(SaveBackup.isRebuildable("gc/User/Cache/Shaders/Vulkan-gs-2C6FFFC0.cache"))
        assertTrue(SaveBackup.isRebuildable("gc/User/Logs/dolphin.log"))
        assertTrue(SaveBackup.isRebuildable("jazz2/jazz2/Jazz2.log.gz"))
    }

    @Test
    fun `memory cards and save files are kept`() {
        assertFalse(SaveBackup.isRebuildable("psx/pcsx-card2.mcd"))
        assertFalse(SaveBackup.isRebuildable("gc/User/Wii/shared2/sys/SYSCONF"))
        assertFalse(SaveBackup.isRebuildable("md/Sonic the Hedgehog 3 (USA).srm"))
        // "Cache" has to be a whole folder name, not a fragment of one.
        assertFalse(SaveBackup.isRebuildable("psp/SAVEDATA/CacheKeeper/data.bin"))
    }

    @Test
    fun `a backup file name is sortable and carries the date`() {
        assertTrue(SaveBackup.suggestedName().matches(Regex("OneEmu-saves-\\d{8}-\\d{4}\\.zip")))
    }
}
