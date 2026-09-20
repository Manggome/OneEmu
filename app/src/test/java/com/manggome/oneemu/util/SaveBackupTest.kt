package com.manggome.oneemu.util

import org.junit.Assert.assertEquals
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
    fun `a backup file name is sortable and carries the date`() {
        assertTrue(SaveBackup.suggestedName().matches(Regex("OneEmu-saves-\\d{8}-\\d{4}\\.zip")))
    }
}
