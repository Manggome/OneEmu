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
    private val chdX = "CHD-X".toByteArray()
    private val weirdRom = "WEIRD-ROM".toByteArray()

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
        listOf("chdgame", "Disk Game", "", "", "", "", "0", "1", "1", rom("x.bin", chdX)),
        listOf("samp", "Sampled Game", "", "", "", "samp", "1", "0", "1", rom("s1.bin", c1)),
        listOf("weird", "Colon%3AName", "", "", "", "", "0", "0", "1", "a%3Ab%3Bc.bin:9:${crc(weirdRom)}"),
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
        assertEquals(Status.CHD_UNSUPPORTED, ArcadeRomCheck.check(db, zip(dir, "chdgame", "x.bin" to chdX)).status)
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

    // ---- multi-core routing / CRC identification ------------------------------------------------

    /** Second "core" DB: one game of its own plus a game that also exists in [db] (the first DB must win). */
    private val db2 = ArcadeRomCheck.Db.parse(
        listOf(
            listOf("newgame", "Newer Game", "", "", "", "", "0", "0", "1", rom("n1.bin", biosRomUs)),
            listOf("parent", "Parent Game (0.139)", "", "", "", "", "0", "0", "1", rom("p1.bin", p1)),
            listOf("diskgame", "Disk Game 2010", "", "", "", "", "0", "2", "1", rom("d.bin", c1)),
        ).joinToString("\n") { it.joinToString("\t") }.byteInputStream(),
    )
    private val dbs = listOf("mame2003plus" to db, "mame2010" to db2)

    @Test fun resolveRoutesToFirstDbThatKnowsTheName() {
        val dir = tmp.newFolder()
        // "parent" is in both DATs → the first (2003-Plus) wins.
        val parent = ArcadeRomCheck.resolve(dbs, zip(dir, "parent", "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom))
        assertEquals("mame2003plus", parent.coreId); assertEquals(Status.OK, parent.status)
        // Only the second DAT knows "newgame".
        val newer = ArcadeRomCheck.resolve(dbs, zip(dir, "newgame", "n1.bin" to biosRomUs))
        assertEquals("mame2010", newer.coreId); assertEquals(Status.OK, newer.status); assertEquals("Newer Game", newer.report.game!!.description)
        // Missing file in the second DAT's game is diagnosed with that DAT.
        val broken = ArcadeRomCheck.resolve(dbs, zip(dir, "newgame", "other.bin" to p1))
        assertEquals("mame2010", broken.coreId); assertEquals(Status.MISSING_FILES, broken.status); assertEquals("n1.bin", broken.report.missing.single().name)
        // Nothing matches by name or content.
        val none = ArcadeRomCheck.resolve(dbs, zip(dir, "zzz", "junk" to "totally unrelated".toByteArray()))
        assertNull(none.coreId); assertEquals(Status.NOT_IN_DAT, none.status); assertEquals("zzz", none.report.shortName)
        // Samples/CHD policy is asked per core.
        val chd = ArcadeRomCheck.resolve(dbs, zip(dir, "diskgame", "d.bin" to c1), chdSupported = { it == "mame2010" })
        assertEquals("mame2010", chd.coreId); assertEquals(Status.OK, chd.status); assertEquals(2, chd.report.disks)
        assertEquals(Status.CHD_UNSUPPORTED, ArcadeRomCheck.resolve(dbs, zip(dir, "diskgame", "d.bin" to c1)).status)
    }

    @Test fun renameSuggestedByCrcWhenNameIsUnknown() {
        val dir = tmp.newFolder()
        // Newer MAME renamed "clone" → "clonenew": the contents are the full non-merged clone set.
        val renamed = zip(dir, "clonenew", "c1.bin" to c1, "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom)
        val r = ArcadeRomCheck.resolve(dbs, renamed)
        assertEquals("mame2003plus", r.coreId)
        assertEquals(Status.RENAME_SUGGESTED, r.status)
        assertEquals(ArcadeRomCheck.Severity.WARN, r.report.severity)
        assertEquals("clone", r.report.suggestedName)
        assertEquals("clonenew", r.report.shortName)          // the file's current (wrong) name
        assertEquals("clone", r.report.game!!.name)
        assertTrue(r.report.issues.isEmpty())                 // would be OK once renamed

        // A zip holding only the parent's own file is the parent, not the clone (own-file completeness wins).
        val p = ArcadeRomCheck.resolve(dbs, zip(dir, "parentnew", "p1.bin" to p1))
        assertEquals(Status.RENAME_SUGGESTED, p.status); assertEquals("parent", p.report.suggestedName); assertEquals("mame2003plus", p.coreId)

        // Rename preview keeps the per-file diagnosis: clone set missing its own c1.bin but 60% of entries match parent.
        val partial = ArcadeRomCheck.resolve(dbs, zip(dir, "mystery", "p1.bin" to p1, "sfix.sfx" to sfix, "sp-s2.sp1" to biosRom))
        assertEquals(Status.RENAME_SUGGESTED, partial.status); assertEquals("parent", partial.report.suggestedName)

        // Contents known only to the second DB route there.
        val n = ArcadeRomCheck.resolve(dbs, zip(dir, "newgame_v2", "whatever.rom" to biosRomUs))
        assertEquals("mame2010", n.coreId); assertEquals(Status.RENAME_SUGGESTED, n.status); assertEquals("newgame", n.report.suggestedName)

        // Below the 60% share and not a complete own set → no suggestion.
        val junk = ArcadeRomCheck.resolve(dbs, zip(dir, "junkset", "a" to c1, "b" to "x1".toByteArray(), "c" to "x2".toByteArray(), "d" to "x3".toByteArray()))
        assertEquals(Status.RENAME_SUGGESTED, junk.status) // c1 is clone's only own file → complete own set qualifies
        assertEquals("clone", junk.report.suggestedName)
        val junk2 = ArcadeRomCheck.resolve(dbs, zip(dir, "junkset2", "a" to sfix, "b" to "x1".toByteArray(), "c" to "x2".toByteArray(), "d" to "x3".toByteArray()))
        assertEquals(Status.NOT_IN_DAT, junk2.status) // sfix alone: 25% of entries; only the BIOS set (never suggested) owns it
    }

    @Test fun checkAsNameAndChdSupportedFlag() {
        val dir = tmp.newFolder()
        val f = zip(dir, "whatever", "x.bin" to chdX)
        assertEquals(Status.CHD_UNSUPPORTED, ArcadeRomCheck.check(db, f, asName = "chdgame").status)
        val r = ArcadeRomCheck.check(db, f, asName = "chdgame", chdSupported = true)
        assertEquals(Status.OK, r.status); assertEquals(1, r.disks); assertEquals("chdgame", r.shortName)
    }

    private fun asset(rel: String): File? = listOf("../$rel", rel).map(::File).firstOrNull { it.isFile }

    /** The MAME 2010 asset (cores/mame2010/assets/romdb.tsv.gz, from metadata/mame2010.xml) parses; bldyror2 routes there. */
    @Test fun realMame2010DatabaseRoutesBloodyRoar2() {
        val a2003 = asset("cores/mame2003plus/assets/romdb.tsv.gz")
        val a2010 = asset("cores/mame2010/assets/romdb.tsv.gz")
        assumeTrue("romdb assets not found; run cores/*/gen-romdb.py", a2003 != null && a2010 != null)
        val real2003 = a2003!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        val real2010 = a2010!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        assertTrue(real2010.size > 8000)
        assertNull(real2003["bldyror2"])
        val br2 = real2010["bldyror2"]!!
        assertEquals("Bloody Roar 2 (World)", br2.description)
        assertEquals("psarc95", br2.bios); assertEquals("psarc95", br2.romof); assertEquals("", br2.cloneof)
        assertFalse(real2010["psarc95"]!!.runnable)
        assertTrue(br2.roms.any { it.name == "coh-1002e.353" && it.merged })
        assertTrue(br2.roms.any { it.name == "flash0.021" && !it.merged && it.crc == "fa7602e1" })
        // CHD game in 0.139: disks counted, not refused.
        assertEquals(1, real2010["kinst"]!!.disks)
        assertTrue(real2010["mslug"]!!.roms.isNotEmpty())

        val dir = tmp.newFolder()
        val real = listOf("mame2003plus" to real2003, "mame2010" to real2010)
        val res = ArcadeRomCheck.resolve(real, zip(dir, "bldyror2", "dummy" to p1), chdSupported = { it == "mame2010" })
        assertEquals("mame2010", res.coreId)
        assertEquals("bldyror2", res.report.game!!.name)
        assertEquals(Status.NEEDS_BIOS, res.status) // dummy zip: everything missing, BIOS zip absent from the folder
        assertEquals("psarc95", res.report.neededZip)
        // Something both know goes to 2003-Plus.
        assertEquals("mame2003plus", ArcadeRomCheck.resolve(real, zip(dir, "mslug", "dummy" to p1)).coreId)
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
