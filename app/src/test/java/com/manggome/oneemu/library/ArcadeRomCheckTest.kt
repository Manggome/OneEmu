package com.manggome.oneemu.library

import com.manggome.oneemu.library.ArcadeRomCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** DAT parsing + zip checking against tiny generated zips (java.util.zip); no Android needed. */
class ArcadeRomCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    // File contents used to build both the DB (expected CRCs) and the zips.
    private val biosRom = "BIOS-EURO".toByteArray()
    private val biosRomUs = "BIOS-US".toByteArray()
    private val sfix = "SFIX".toByteArray()
    private val p1 = "PARENT-P1".toByteArray()
    private val c1 = "CLONE-C1".toByteArray()

    private fun crc(b: ByteArray): String = String.format("%08x", CRC32().apply { update(b) }.value)
    private fun rom(name: String, data: ByteArray, merge: String = "", bios: String = ""): String {
        var t = "$name:${data.size}:${crc(data)}"
        if (merge.isNotEmpty() || bios.isNotEmpty()) t += ":$merge"
        if (bios.isNotEmpty()) t += ":$bios"
        return t
    }

    /** name description cloneof romof bios sampleof needsSamples disks runnable roms */
    private val dbText = listOf(
        "# comment line",
        listOf("neogeo", "Neo-Geo", "", "", "", "", "0", "0", "0",
            listOf(rom("sp-s2.sp1", biosRom, bios = "euro"), rom("usa_2slt.bin", biosRomUs, bios = "us"), rom("sfix.sfx", sfix)).joinToString(";")),
        listOf("parent", "Parent Game", "", "neogeo", "neogeo", "", "0", "0", "1",
            listOf(rom("p1.bin", p1), rom("sfix.sfx", sfix, "="), rom("sp-s2.sp1", biosRom, "=", "euro"), rom("usa_2slt.bin", biosRomUs, "=", "us")).joinToString(";")),
        listOf("clone", "Parent Game (clone)", "parent", "parent", "neogeo", "", "0", "0", "1",
            listOf(rom("c1.bin", c1), rom("p1.bin", p1, "="), rom("sfix.sfx", sfix, "="), rom("sp-s2.sp1", biosRom, "=", "euro"), rom("usa_2slt.bin", biosRomUs, "=", "us")).joinToString(";")),
        listOf("chdgame", "Disk Game", "", "", "", "", "0", "1", "1", rom("x.bin", p1)),
        listOf("samp", "Sampled Game", "", "", "", "samp", "1", "0", "1", rom("s1.bin", c1)),
        listOf("weird", "Colon%3AName", "", "", "", "", "0", "0", "1", "a%3Ab%3Bc.bin:9:${crc(p1)}"),
    ).map { if (it is List<*>) it.joinToString("\t") else it as String }.joinToString("\n") + "\n"

    private val db = ArcadeRomCheck.Db.parse(dbText.byteInputStream())

    private fun zip(dir: File, name: String, vararg files: Pair<String, ByteArray>): File {
        val f = File(dir, "$name.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, data) in files) { z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry() }
        }
        return f
    }

    @Test fun parsesGamesAndRomFlags() {
        assertEquals(6, db.size)
        val clone = db["clone"]!!
        assertEquals("parent", clone.cloneof)
        assertEquals("neogeo", clone.bios)
        assertTrue(clone.runnable)
        assertFalse(db["neogeo"]!!.runnable)
        val sp = clone.roms.first { it.name == "sp-s2.sp1" }
        assertTrue(sp.merged); assertEquals("sp-s2.sp1", sp.mergeName); assertEquals("euro", sp.bios)
        assertFalse(clone.roms.first { it.name == "c1.bin" }.merged)
        assertTrue(db["samp"]!!.needsSamples); assertEquals("samp", db["samp"]!!.sampleof)
        assertEquals(1, db["chdgame"]!!.disks)
        assertEquals("a:b;c.bin", db["weird"]!!.roms.single().name) // escaped separators
        assertEquals(db["CLONE"], clone) // case-insensitive lookup
    }

    @Test fun splitSetWithParentAndBiosIsOk() {
        val dir = tmp.newFolder()
        zip(dir, "neogeo", "sp-s2.sp1" to biosRom, "sfix.sfx" to sfix)
        zip(dir, "parent", "p1.bin" to p1)
        val clone = zip(dir, "clone", "c1.bin" to c1)
        val r = ArcadeRomCheck.check(db, clone)
        assertEquals(r.toString(), Status.OK, r.status)
        assertTrue(r.parentPresent); assertTrue(r.biosPresent)
        assertEquals("parent", r.parentZip); assertEquals("neogeo", r.biosZip)
        // parent alone (only the default BIOS present, "us" BIOS absent) is fine too
        assertEquals(Status.OK, ArcadeRomCheck.check(db, File(dir, "parent.zip")).status)
    }

    @Test fun nonMergedSetIsOkWithoutSiblings() {
        val dir = tmp.newFolder()
        val clone = zip(dir, "clone", "c1.bin" to c1, "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom)
        assertEquals(Status.OK, ArcadeRomCheck.check(db, clone).status)
    }

    @Test fun missingParentZip() {
        val dir = tmp.newFolder()
        zip(dir, "neogeo", "sp-s2.sp1" to biosRom, "sfix.sfx" to sfix)
        val clone = zip(dir, "clone", "c1.bin" to c1)
        val r = ArcadeRomCheck.check(db, clone)
        assertEquals(Status.NEEDS_PARENT, r.status)
        assertEquals("parent", r.neededZip)
        assertFalse(r.parentPresent)
        val missing = r.missing.single()
        assertEquals("p1.bin", missing.name); assertEquals("parent", missing.owner)
    }

    @Test fun missingBiosZip() {
        val dir = tmp.newFolder()
        zip(dir, "parent", "p1.bin" to p1)
        val clone = zip(dir, "clone", "c1.bin" to c1)
        val r = ArcadeRomCheck.check(db, clone)
        assertEquals(Status.NEEDS_BIOS, r.status)
        assertEquals("neogeo", r.neededZip)
        assertFalse(r.biosPresent)
        // sfix.sfx (merged through parent up to neogeo) and one BIOS file, both owned by neogeo
        assertEquals(setOf("sfix.sfx", "sp-s2.sp1"), r.missing.map { it.name }.toSet())
        assertTrue(r.missing.all { it.owner == "neogeo" })
    }

    @Test fun wrongCrcMeansWrongSet() {
        val dir = tmp.newFolder()
        val clone = zip(dir, "clone", "c1.bin" to "OTHER-VERSION".toByteArray(), "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom)
        val r = ArcadeRomCheck.check(db, clone)
        assertEquals(Status.WRONG_SET, r.status)
        val issue = r.mismatched.single()
        assertEquals("c1.bin", issue.name); assertEquals(crc(c1), issue.expectedCrc); assertNotNull(issue.foundCrc)
        assertTrue(r.missing.isEmpty())
    }

    @Test fun ownFileAbsentIsMissingFiles() {
        val dir = tmp.newFolder()
        val clone = zip(dir, "clone", "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom)
        val r = ArcadeRomCheck.check(db, clone)
        assertEquals(Status.MISSING_FILES, r.status)
        assertEquals("c1.bin", r.missing.single().name); assertEquals("clone", r.missing.single().owner)
    }

    @Test fun renamedFileWithRightCrcStillCounts() {
        // MAME 0.78 falls back to a CRC lookup inside the zip, so a differently named entry satisfies the ROM.
        val dir = tmp.newFolder()
        val clone = zip(dir, "clone", "renamed.rom" to c1, "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom)
        assertEquals(Status.OK, ArcadeRomCheck.check(db, clone).status)
    }

    @Test fun caseInsensitiveEntryNamesAndSiblingResolver() {
        val dir = tmp.newFolder()
        zip(dir, "NeoGeo", "SP-S2.SP1" to biosRom, "SFIX.SFX" to sfix)
        zip(dir, "parent", "P1.BIN" to p1)
        val clone = zip(dir, "clone", "C1.BIN" to c1)
        val index = dir.listFiles()!!.associateBy { it.name.lowercase() }
        val r = ArcadeRomCheck.check(db, clone, sibling = { index[it.lowercase()] ?: File(dir, it) })
        assertEquals(Status.OK, r.status)
    }

    @Test fun chdAndUnknownAndSamples() {
        val dir = tmp.newFolder()
        assertEquals(Status.CHD_UNSUPPORTED, ArcadeRomCheck.check(db, zip(dir, "chdgame", "x.bin" to p1)).status)
        val unknown = ArcadeRomCheck.check(db, zip(dir, "sf2xyz", "a" to p1))
        assertEquals(Status.NOT_IN_DAT, unknown.status); assertNull(unknown.game); assertEquals("sf2xyz", unknown.shortName)

        val samples = tmp.newFolder()
        val samp = zip(dir, "samp", "s1.bin" to c1)
        val r1 = ArcadeRomCheck.check(db, samp, samplesDir = samples)
        assertEquals(Status.NEEDS_SAMPLES, r1.status); assertEquals(ArcadeRomCheck.Severity.WARN, r1.severity); assertEquals("samp", r1.sampleZip)
        zip(samples, "samp", "boom.wav" to c1)
        val r2 = ArcadeRomCheck.check(db, samp, samplesDir = samples)
        assertEquals(Status.OK, r2.status); assertTrue(r2.samplesPresent)
        // No samples dir given → samples are not judged.
        assertEquals(Status.NEEDS_SAMPLES, ArcadeRomCheck.check(db, samp).status)
    }

    @Test fun corruptZipReportsEverythingMissing() {
        val dir = tmp.newFolder()
        val f = File(dir, "samp.zip").apply { writeText("not a zip") }
        val r = ArcadeRomCheck.check(db, f)
        assertEquals(Status.MISSING_FILES, r.status)
        assertEquals(listOf("s1.bin"), r.missing.map { it.name })
    }

    /** The shipped asset (cores/mame2003plus/assets/romdb.tsv.gz) parses and has the expected shape. */
    @Test fun realDatabaseParses() {
        val asset = listOf("../cores/mame2003plus/assets/romdb.tsv.gz", "cores/mame2003plus/assets/romdb.tsv.gz").map(::File).firstOrNull { it.isFile }
        assumeTrue("romdb asset not found; run cores/mame2003plus/gen-romdb.py", asset != null)
        val real = asset!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        assertTrue(real.size > 5000)
        val mslug = real["mslug"]!!
        assertEquals("neogeo", mslug.bios); assertEquals("neogeo", mslug.romof); assertEquals("", mslug.cloneof)
        assertTrue(mslug.roms.any { it.name == "201-p1.bin" && !it.merged })
        assertTrue(mslug.roms.any { it.name == "sp-s2.sp1" && it.merged && it.bios == "euro" })
        val kof98n = real["kof98n"]!!
        assertEquals("kof98", kof98n.cloneof); assertEquals("neogeo", kof98n.bios)
        assertEquals("sf2", real["sf2ce"]!!.sampleof); assertTrue(real["sf2ce"]!!.needsSamples)
        assertEquals("dkong", real["dkongpe"]!!.sampleof)
        assertEquals(1, real["jdredd"]!!.disks); assertEquals(1, real["kinst"]!!.disks)
        assertFalse(real["neogeo"]!!.runnable)
        assertNull(real["notagame123"])
    }
}
