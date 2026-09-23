package com.manggome.oneemu.library

import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaveImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun target(system: SystemId, rom: String = "/x/game.bin", core: String? = null) =
        SaveImporter.Target(system, rom, core, tmp.newFolder())

    /** A .max made by Play!'s own LZARI encoder (LzAri.cpp built on the Mac) must unpack to the same files. */
    @Test fun maxSavesUnpackLikePlayDoes() {
        val bytes = javaClass.getResourceAsStream("/saves/tekken-test.max")!!.readBytes()
        val (folder, files) = SaveImporter.parseMax(bytes)!!
        assertEquals("BASLUS-21059", folder)
        assertEquals(listOf("icon.sys", "BASLUS-21059"), files.keys.toList())
        assertEquals("PS2D icon data 1234567890", String(files["icon.sys"]!!))
        val body = String(files["BASLUS-21059"]!!)
        assertTrue(body.startsWith("TEKKEN5-UNLOCK-0;TEKKEN5-UNLOCK-1;"))
        assertEquals(6000, body.split(';').size - 1)
    }

    private fun psu(folder: String, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun entry(type: Int, size: Int, name: String) {
            val b = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0, type.toShort()); b.putInt(4, size)
            name.toByteArray().forEachIndexed { i, c -> b.put(0x40 + i, c) }
            out.write(b.array())
        }
        entry(0x8427, files.size + 2, folder)
        entry(0x8427, 0, "."); entry(0x8427, 0, "..")
        for ((n, d) in files) {
            entry(0x8497, d.size, n); out.write(d)
            if (d.size % 0x400 != 0) out.write(ByteArray(0x400 - d.size % 0x400))
        }
        return out.toByteArray()
    }

    @Test fun psuGoesIntoPlaysMemoryCardAsAFolder() {
        val t = target(SystemId.PS2, core = "play")
        val data = mapOf("icon.sys" to ByteArray(964) { 1 }, "BASLUS-21059" to ByteArray(3000) { 7 })
        val plan = SaveImporter.analyze(t, "tekken5.psu", psu("BASLUS-21059", data)) as SaveImporter.Plan.Folder
        assertEquals("BASLUS-21059", plan.folderName)
        assertTrue(plan.parent.path.endsWith("Play Data Files/vfs/mc0"))
        val applied = SaveImporter.apply(plan, tmp.newFolder("bk"))
        assertArrayEquals(ByteArray(3000) { 7 }, File(applied.written, "BASLUS-21059").readBytes())
    }

    @Test fun anotherPs2CoreIsRefused() {
        val plan = SaveImporter.analyze(target(SystemId.PS2, core = "armsx2"), "a.psu", psu("X", mapOf("a" to ByteArray(4))))
        assertEquals(SaveImporter.Problem.PS2_OTHER_CORE, (plan as SaveImporter.Plan.Unsupported).problem)
    }

    private fun gci(code: String, name: String, blocks: Int): ByteArray {
        val b = ByteArray(0x40 + blocks * 0x2000)
        code.toByteArray().copyInto(b, 0)
        "8P".toByteArray().copyInto(b, 4)
        name.toByteArray().copyInto(b, 8)
        b[0x38] = (blocks shr 8).toByte(); b[0x39] = blocks.toByte()
        b[0x40] = 0x5A
        return b
    }

    @Test fun gcsAndDatelBecomeTheSameGci() {
        val g = gci("G4NJ", "NARUTO4", 2)
        val gcs = ByteArray(0x150 + 2 * 0x2000)
        "GCSAVE".toByteArray().copyInto(gcs, 0)
        g.copyInto(gcs, 0x110, 0, 0x40); gcs[0x110 + 0x39] = 1 // GCS keeps a count of 1
        g.copyInto(gcs, 0x150, 0x40)
        assertArrayEquals(g, SaveImporter.toGci(gcs))
        val sav = ByteArray(0xC0 + 2 * 0x2000)
        "DATELGC_SAVE".toByteArray().copyInto(sav, 0)
        val swapped = g.copyOfRange(0, 0x40)
        for (p in intArrayOf(0x06, 0x2C, 0x2E, 0x30, 0x32, 0x34, 0x36, 0x38, 0x3A, 0x3C, 0x3E)) { val x = swapped[p]; swapped[p] = swapped[p + 1]; swapped[p + 1] = x }
        swapped.copyInto(sav, 0x80); g.copyInto(sav, 0xC0, 0x40)
        assertArrayEquals(g, SaveImporter.toGci(sav))
    }

    @Test fun aGameCubeSaveLandsInItsRegionFolderAndBacksUpTheOldOne() {
        val t = target(SystemId.GC, rom = "/nonexistent/naruto4.iso")
        val plan = SaveImporter.analyze(t, "naruto.gci", gci("G4NJ", "NARUTO4", 1)) as SaveImporter.Plan.GameCube
        assertEquals("JAP", plan.regionDir)
        val backups = tmp.newFolder("bk")
        val first = SaveImporter.apply(plan, backups)
        assertTrue(first.written.path.endsWith("User/GC/JAP/Card A/8P-G4NJ-NARUTO4.gci"))
        val second = SaveImporter.apply(plan, backups)
        assertTrue(second.backup != null && second.backup!!.exists())
    }

    @Test fun regionRelabelOnlyWhenTheGameMatches() {
        val plan = SaveImporter.Plan.GameCube(tmp.newFolder("card"), "USA", gci("G4NJ", "N", 1), "G4NJ", "G4NE", "N")
        assertTrue(plan.regionOnlyMismatch)
        val applied = SaveImporter.apply(plan, tmp.newFolder("bk2"), relabelRegion = true)
        assertEquals("G4NE", String(applied.written.readBytes(), 0, 4))
    }

    @Test fun batterySavesBecomeTheRomsSrm() {
        val t = target(SystemId.GBA, rom = "/roms/Pokemon Emerald.gba")
        val plan = SaveImporter.analyze(t, "emerald.sav", ByteArray(131072)) as SaveImporter.Plan.Battery
        assertEquals("Pokemon Emerald.srm", plan.target.name)
    }
}
