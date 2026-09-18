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

    // ---- driver status / status-aware routing ---------------------------------------------------

    /** A romdb line with the optional trailing columns: status  sourcefile  emulation  color  sound  graphic. */
    private fun row(name: String, roms: String, status: String, src: String = "", emulation: String = status) =
        listOf(name, "Game $name", "", "", "", "", "0", "0", "1", roms, status, src, emulation, "good", "good", "good").joinToString("\t")

    @Test fun parsesDriverStatusColumnsAndOldFormat() {
        val text = listOf(
            row("g", rom("a.bin", p1), "good", "cps1.c"),
            row("i", rom("a.bin", p1), "imperfect", "neogeo.c"),
            row("p", rom("a.bin", p1), "preliminary", "stv.c", emulation = "protection"),
            row("u", rom("a.bin", p1), "", ""),
        ).joinToString("\n") + "\n"
        val d = ArcadeRomCheck.Db.parse(text.byteInputStream())
        assertEquals(ArcadeRomCheck.DriverStatus.GOOD, d["g"]!!.driverStatus); assertEquals("cps1.c", d["g"]!!.sourceFile); assertEquals("cps1", d["g"]!!.driverName)
        assertEquals(ArcadeRomCheck.DriverStatus.IMPERFECT, d["i"]!!.driverStatus)
        assertEquals(ArcadeRomCheck.DriverStatus.PRELIMINARY, d["p"]!!.driverStatus); assertEquals("protection", d["p"]!!.emulation); assertEquals("stv", d["p"]!!.driverName)
        assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, d["u"]!!.driverStatus); assertEquals("", d["u"]!!.sourceFile)
        // The old 10-column format (this file's [db]) still parses, with UNKNOWN status.
        assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, db["parent"]!!.driverStatus)
        assertEquals("", db["parent"]!!.status)
        assertEquals(ArcadeRomCheck.DriverStatus.PRELIMINARY, ArcadeRomCheck.DriverStatus.parse("protection"))
        assertTrue(ArcadeRomCheck.DriverStatus.UNKNOWN.rank == ArcadeRomCheck.DriverStatus.GOOD.rank)
    }

    /** "2003-Plus" side: statuses of the same names as [statusDb2010] below. */
    private val statusDb2003 = ArcadeRomCheck.Db.parse(
        listOf(
            row("prelim", rom("p.bin", p1), "preliminary", "zn.c"),           // preliminary here, imperfect in 2010 → 2010
            row("bothbad", rom("b.bin", c1), "preliminary", "segac2.c"),      // preliminary in both → 2010 (newer driver, no worse)
            row("goodgame", rom("g.bin", sfix), "good", "cps1.c"),            // good here → stays (2010 says imperfect)
            row("onlyhere", rom("o.bin", biosRom), "preliminary", "nss.c"),   // preliminary but no alternative → stays
            row("stvgood", rom("s.bin", biosRomUs), "good", "stv.c"),         // "good" but ST-V crashes on arm64 → 2010 (crashes there too, newest wins)
            row("stvalone", rom("t.bin", chdX), "good", "stv.c"),             // unstable driver, nobody else lists it → stays, knownUnstable
            row("prelimvsstv", rom("v.bin", sfix), "preliminary", "zn.c"),    // preliminary here, but 2010 lists it on a crashing driver → stays
            row("old", rom("w.bin", weirdRom), "", ""),                       // no rating → behaves like good
        ).joinToString("\n").byteInputStream(),
    )
    private val statusDb2010 = ArcadeRomCheck.Db.parse(
        listOf(
            row("prelim", rom("p.bin", p1), "imperfect", "zn.c"),
            row("bothbad", rom("b.bin", c1), "preliminary", "segac2.c"),
            row("goodgame", rom("g.bin", sfix), "imperfect", "cps1.c"),
            row("stvgood", rom("s.bin", biosRomUs), "imperfect", "stv.c"),
            row("old", rom("w.bin", weirdRom), "good", "x.c"),
            row("stv2010", rom("z.bin", p1), "imperfect", "stv.c"),            // only 2010 lists it; ST-V crashes there too → knownUnstable
            row("prelimvsstv", rom("v.bin", sfix), "good", "stv.c"),
        ).joinToString("\n").byteInputStream(),
    )
    private val statusDbs = listOf("mame2003plus" to statusDb2003, "mame2010" to statusDb2010)

    @Test fun routeByNamePrefersFirstCoreUnlessPreliminaryOrUnstable() {
        fun rt(n: String) = ArcadeRomCheck.routeByName(statusDbs, n)!!
        val prelim = rt("prelim")
        assertEquals("mame2010", prelim.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, prelim.reason); assertEquals("mame2003plus", prelim.skippedCoreId)
        val both = rt("bothbad")
        assertEquals("mame2010", both.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, both.reason)
        val good = rt("goodgame")
        assertEquals("mame2003plus", good.coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, good.reason); assertNull(good.skippedCoreId)
        val only = rt("onlyhere")
        assertEquals("mame2003plus", only.coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, only.reason)
        val stv = rt("stvgood")
        assertEquals("mame2010", stv.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, stv.reason); assertEquals("mame2003plus", stv.skippedCoreId)
        assertEquals("mame2003plus", rt("stvalone").coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, rt("stvalone").reason)
        assertEquals("mame2003plus", rt("old").coreId)
        assertEquals("mame2010", rt("stv2010").coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, rt("stv2010").reason)
        // A plain preliminary driver beats a known crash: the game stays where it merely runs badly.
        val pv = rt("prelimvsstv")
        assertEquals("mame2003plus", pv.coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, pv.reason); assertFalse(pv.knownUnstable)
        assertNull(ArcadeRomCheck.routeByName(statusDbs, "nothing"))
        assertTrue(ArcadeRomCheck.isUnreliable("mame2003plus", statusDb2003["stvgood"]!!))
        assertTrue(ArcadeRomCheck.isUnreliable("mame2010", statusDb2010["stvgood"]!!)) // ST-V crashes in MAME 2010 as well
        assertTrue(ArcadeRomCheck.isUnreliable("mame2010", statusDb2010["bothbad"]!!))
        assertFalse(ArcadeRomCheck.isUnstableDriver("mame2010", statusDb2010["bothbad"]!!))
        // knownUnstable: only when the *chosen* core's driver is in UNSTABLE_DRIVERS — preliminary alone is not it.
        assertFalse(prelim.knownUnstable); assertFalse(both.knownUnstable); assertFalse(good.knownUnstable); assertFalse(only.knownUnstable)
        assertTrue(stv.knownUnstable); assertTrue(rt("stvalone").knownUnstable); assertTrue(rt("stv2010").knownUnstable)
    }

    /** Both bundled cores crash on the driver: the newest core still takes the game, and the result says it will crash. */
    @Test fun knownUnstableWhenEveryCoreCrashesOnTheDriver() {
        val both2003 = ArcadeRomCheck.Db.parse(listOf(
            row("stvboth", rom("s.bin", biosRomUs), "good", "stv.c"),
            row("stvprelim", rom("q.bin", c1), "preliminary", "stv.c"),
        ).joinToString("\n").byteInputStream())
        val both2010 = ArcadeRomCheck.Db.parse(listOf(
            row("stvboth", rom("s.bin", biosRomUs), "imperfect", "stv.c"),
            row("stvprelim", rom("q.bin", c1), "preliminary", "stv.c"),
        ).joinToString("\n").byteInputStream())
        val dbs = listOf("mame2003plus" to both2003, "mame2010" to both2010)
        assertTrue(ArcadeRomCheck.UNSTABLE_DRIVERS.getValue("mame2003plus").contains("stv"))
        // Pinning the whole set breaks whenever another crashing driver is found; the driver under test is what matters.
        assertTrue(ArcadeRomCheck.UNSTABLE_DRIVERS.getValue("mame2010").contains("stv"))

        val rt = ArcadeRomCheck.routeByName(dbs, "stvboth")!!
        assertEquals("mame2010", rt.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, rt.reason); assertEquals("mame2003plus", rt.skippedCoreId)
        assertTrue(rt.knownUnstable)
        val pr = ArcadeRomCheck.routeByName(dbs, "stvprelim")!!
        assertEquals("mame2010", pr.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, pr.reason); assertTrue(pr.knownUnstable)

        // Resolution carries the flag independently of the report status (OK here, MISSING_FILES below, RENAME_SUGGESTED via CRC).
        val dir = tmp.newFolder()
        val ok = ArcadeRomCheck.resolve(dbs, zip(dir, "stvboth", "s.bin" to biosRomUs))
        assertEquals("mame2010", ok.coreId); assertEquals(Status.OK, ok.status); assertTrue(ok.knownUnstable)
        val broken = ArcadeRomCheck.resolve(dbs, zip(dir, "stvboth", "other.bin" to p1))
        assertEquals(Status.MISSING_FILES, broken.status); assertTrue(broken.knownUnstable)
        val renamed = ArcadeRomCheck.resolve(dbs, zip(dir, "stvboth_v2", "whatever.rom" to biosRomUs))
        assertEquals(Status.RENAME_SUGGESTED, renamed.status); assertEquals("stvboth", renamed.report.suggestedName); assertTrue(renamed.knownUnstable)
        // Unknown everywhere → no core, not unstable.
        val none = ArcadeRomCheck.resolve(dbs, zip(dir, "zzz", "junk" to "unrelated bytes".toByteArray()))
        assertNull(none.coreId); assertFalse(none.knownUnstable)
        // The earlier fixtures: a plain first match and a preliminary-but-not-crashing route are not flagged.
        assertFalse(ArcadeRomCheck.resolve(statusDbs, zip(dir, "goodgame", "g.bin" to sfix)).knownUnstable)
        assertFalse(ArcadeRomCheck.resolve(statusDbs, zip(dir, "prelim", "p.bin" to p1)).knownUnstable)
    }

    // ---- three DBs: 2003-Plus → 2010 → current MAME (downloadable) ---------------------------------

    /** Current MAME's romdb may carry no status column (UNKNOWN = rated like GOOD) and `.cpp` source names. */
    private val statusDbMame = ArcadeRomCheck.Db.parse(
        listOf(
            row("stvgood", rom("s.bin", biosRomUs), "", "stv.cpp"),          // ST-V: crashes in both older cores → current MAME
            row("bothbad", rom("b.bin", c1), "", "segac2.cpp"),              // preliminary in both older cores, unrated here → current MAME
            row("prelim", rom("p.bin", p1), "good", "zn.cpp"),               // 2010 already rates it better than 2003 → 2010 keeps it
            row("goodgame", rom("g.bin", sfix), "good", "cps1.cpp"),         // good in 2003-Plus → stays there
            row("stv2010", rom("z.bin", p1), "imperfect", "stv.cpp"),        // only 2010 (crashing) and current MAME list it → current MAME
            row("stvalone", rom("t.bin", chdX), "good", "stv.cpp"),          // 2003-Plus "good" on a crashing driver → current MAME
            row("onlymame", rom("o.bin", weirdRom), "", "namcos12.cpp"),     // nobody else lists it → current MAME, plain first match
            row("zipnamed", "", "", "cps2.cpp"),                             // zip-level only entry (no rom rows)
        ).joinToString("\n").byteInputStream(),
    )
    private val threeDbs = listOf("mame2003plus" to statusDb2003, "mame2010" to statusDb2010, "mame" to statusDbMame)

    @Test fun routeByNameWithThreeDbsSendsStvAndDoublyPreliminaryGamesToCurrentMame() {
        fun rt(n: String) = ArcadeRomCheck.routeByName(threeDbs, n)!!
        assertNull(ArcadeRomCheck.UNSTABLE_DRIVERS["mame"])
        assertEquals("stv", statusDbMame["stvgood"]!!.driverName)
        assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, statusDbMame["stvgood"]!!.driverStatus)

        // ST-V: both bundled MAMEs crash on the driver; current MAME lists the game → it goes there and is no longer "known unstable".
        val stv = rt("stvgood")
        assertEquals("mame", stv.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, stv.reason); assertEquals("mame2003plus", stv.skippedCoreId)
        assertFalse(stv.knownUnstable)
        val stv2010 = rt("stv2010")
        assertEquals("mame", stv2010.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, stv2010.reason); assertEquals("mame2010", stv2010.skippedCoreId)
        assertFalse(stv2010.knownUnstable)
        val alone = rt("stvalone")
        assertEquals("mame", alone.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, alone.reason); assertFalse(alone.knownUnstable)
        // Preliminary in the first two, unrated (= OK) in current MAME → current MAME.
        val both = rt("bothbad")
        assertEquals("mame", both.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, both.reason); assertEquals("mame2003plus", both.skippedCoreId)
        // The first strictly better later core still wins: 2010 rates it imperfect, so current MAME is not needed.
        assertEquals("mame2010", rt("prelim").coreId)
        // Good in the preferred core → stays; unknown to the older cores → current MAME as a plain first match.
        assertEquals("mame2003plus", rt("goodgame").coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, rt("goodgame").reason)
        assertEquals("mame", rt("onlymame").coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, rt("onlymame").reason); assertFalse(rt("onlymame").knownUnstable)
        // A preliminary game nobody else lists still stays where it is.
        assertEquals("mame2003plus", rt("onlyhere").coreId)
        // The two-DB behaviour is unchanged when current MAME does not list the game.
        assertEquals("mame2003plus", rt("prelimvsstv").coreId)
        assertNull(ArcadeRomCheck.routeByName(threeDbs, "nothing"))
    }

    /** A DB without per-file rows (zip-level only) cannot judge the contents: UNVERIFIED, severity OK, not an error. */
    @Test fun zipLevelOnlyDbReportsUnverified() {
        val dir = tmp.newFolder()
        val g = statusDbMame["zipnamed"]!!
        assertTrue(g.roms.isEmpty()); assertTrue(g.runnable)
        val r = ArcadeRomCheck.check(statusDbMame, zip(dir, "zipnamed", "anything.bin" to p1))
        assertEquals(Status.UNVERIFIED, r.status); assertEquals(ArcadeRomCheck.Severity.OK, r.severity)
        assertTrue(r.issues.isEmpty()); assertEquals("zipnamed", r.game!!.name)
        // Through resolve with three DBs: routed to current MAME, still UNVERIFIED (no crash, no MISSING_FILES).
        val res = ArcadeRomCheck.resolve(threeDbs, zip(dir, "zipnamed", "anything.bin" to p1), chdSupported = { it != "mame2003plus" })
        assertEquals("mame", res.coreId); assertEquals(Status.UNVERIFIED, res.status); assertFalse(res.knownUnstable)
        // ST-V through resolve: current MAME, OK, and the report's game comes from the current MAME DB.
        val stv = ArcadeRomCheck.resolve(threeDbs, zip(dir, "stvgood", "s.bin" to biosRomUs))
        assertEquals("mame", stv.coreId); assertEquals(Status.OK, stv.status); assertFalse(stv.knownUnstable)
        assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, stv.driverStatus)
        // A DB with rom rows keeps checking files as before.
        assertEquals(Status.MISSING_FILES, ArcadeRomCheck.check(statusDbMame, zip(dir, "onlymame", "other.bin" to p1)).status)
    }

    @Test fun resolveCarriesRoutingReasonAndStatus() {
        val dir = tmp.newFolder()
        val r = ArcadeRomCheck.resolve(statusDbs, zip(dir, "prelim", "p.bin" to p1))
        assertEquals("mame2010", r.coreId); assertEquals(Status.OK, r.status)
        assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, r.reason); assertEquals("mame2003plus", r.skippedCoreId)
        assertEquals(ArcadeRomCheck.DriverStatus.IMPERFECT, r.driverStatus)
        val g = ArcadeRomCheck.resolve(statusDbs, zip(dir, "goodgame", "g.bin" to sfix))
        assertEquals("mame2003plus", g.coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, g.reason); assertEquals(ArcadeRomCheck.DriverStatus.GOOD, g.driverStatus)
        // CRC identification finds the set in the first DB, but the identified name is routed like a named zip.
        val renamed = ArcadeRomCheck.resolve(statusDbs, zip(dir, "prelim_v2", "whatever.rom" to p1))
        assertEquals(Status.RENAME_SUGGESTED, renamed.status); assertEquals("prelim", renamed.report.suggestedName)
        assertEquals("mame2010", renamed.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, renamed.reason)
        // Unknown everywhere: no reason, UNKNOWN status.
        val none = ArcadeRomCheck.resolve(statusDbs, zip(dir, "zzz", "junk" to "unrelated bytes".toByteArray()))
        assertNull(none.coreId); assertEquals(ArcadeRomCheck.RouteReason.NONE, none.reason); assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, none.driverStatus)
    }

    // ---- default biosset + device ROMs (romdb columns 17/18: defaultbios, devices) -------------------

    private val devRom = "DEVICE-ROM".toByteArray()

    /** Full 18-column line: the 16 known ones plus defaultbios and ';'-separated devices. */
    private fun row18(name: String, cloneof: String, romof: String, bios: String, runnable: String, roms: String, defaultBios: String, devices: String = "") =
        listOf(name, "Game $name", cloneof, romof, bios, "", "0", "0", runnable, roms, "good", "x.cpp", "good", "", "", "", defaultBios, devices).joinToString("\t")

    private val dbNew = ArcadeRomCheck.Db.parse(
        listOf(
            // BIOS set with two selectable BIOSes; "us" is the default one (default="yes" or first biosset, resolved by gen-romdb).
            row18("nb", "", "", "", "0", listOf(rom("sp-s2.sp1", biosRom, bios = "euro"), rom("usa_2slt.bin", biosRomUs, bios = "us"), rom("sfix.sfx", sfix)).joinToString(";"), "us"),
            row18("g", "", "nb", "nb", "1",
                listOf(rom("p1.bin", p1), rom("sfix.sfx", sfix, "="), rom("sp-s2.sp1", biosRom, "=", "euro"), rom("usa_2slt.bin", biosRomUs, "=", "us")).joinToString(";"), "us"),
            // A game whose own default differs from the BIOS set's (ROM_DEFAULT_BIOS in the driver): needs "euro".
            row18("geuro", "", "nb", "nb", "1",
                listOf(rom("p1.bin", p1), rom("sfix.sfx", sfix, "="), rom("sp-s2.sp1", biosRom, "=", "euro"), rom("usa_2slt.bin", biosRomUs, "=", "us")).joinToString(";"), "euro"),
            // Device with one ROM (segabill-like) and a game that instantiates it.
            row18("dev1", "", "", "", "0", rom("d1.bin", devRom), ""),
            row18("dg", "", "", "", "1", rom("p1.bin", p1), "", "dev1"),
            // Device with a parent ROM device (qsound_hle romof=qsound): the file may live in the parent's zip.
            row18("devhle", "", "devpar", "", "0", rom("d1.bin", devRom, "="), ""),
            row18("hg", "", "", "", "1", rom("c1.bin", c1), "", "devhle"),
            // Two devices, one of them also a BIOS-using clone chain: clone → parent, BIOS nb, devices dev1 + devhle.
            row18("multi", "g", "g", "nb", "1", listOf(rom("c1.bin", c1), rom("p1.bin", p1, "="), rom("sfix.sfx", sfix, "="), rom("usa_2slt.bin", biosRomUs, "=", "us")).joinToString(";"), "us", "dev1;devhle"),
        ).joinToString("\n").byteInputStream(),
    )

    @Test fun parsesDefaultBiosAndDevicesAndOldFormat() {
        assertEquals("us", dbNew["nb"]!!.defaultBios); assertEquals("euro", dbNew["geuro"]!!.defaultBios)
        assertEquals(listOf("dev1"), dbNew["dg"]!!.devices); assertEquals(listOf("dev1", "devhle"), dbNew["multi"]!!.devices)
        assertTrue(dbNew["dev1"]!!.devices.isEmpty()); assertFalse(dbNew["dev1"]!!.runnable); assertEquals("devpar", dbNew["devhle"]!!.romof)
        // requiredRoms: files without a biosset + the default biosset's files only.
        assertEquals(listOf("p1.bin", "sfix.sfx", "usa_2slt.bin"), dbNew["g"]!!.requiredRoms.map { it.name })
        assertEquals(listOf("p1.bin", "sfix.sfx", "sp-s2.sp1"), dbNew["geuro"]!!.requiredRoms.map { it.name })
        assertTrue(dbNew["g"]!!.legacyBiosRoms.isEmpty())
        // 10- and 16-column lines: no default BIOS → legacy "any one BIOS file" rule, no devices.
        assertEquals("", db["neogeo"]!!.defaultBios); assertTrue(db["clone"]!!.devices.isEmpty())
        assertEquals(listOf("sp-s2.sp1", "usa_2slt.bin"), db["neogeo"]!!.legacyBiosRoms.map { it.name })
        assertEquals(listOf("sfix.sfx"), db["neogeo"]!!.requiredRoms.map { it.name })
        assertEquals("", statusDbMame["stvgood"]!!.defaultBios); assertTrue(statusDbMame["stvgood"]!!.devices.isEmpty())
    }

    @Test fun defaultBiossetFilesAreRequiredNotJustAnyBiosFile() {
        val dir = tmp.newFolder()
        // Old-style BIOS zip: only the euro BIOS — enough under the legacy rule, not for a DAT whose default is "us".
        zip(dir, "nb", "sp-s2.sp1" to biosRom, "sfix.sfx" to sfix)
        val g = zip(dir, "g", "p1.bin" to p1)
        val r = ArcadeRomCheck.check(dbNew, g)
        assertEquals(r.toString(), Status.MISSING_FILES, r.status) // the BIOS zip is there, it just predates this DAT
        assertTrue(r.biosPresent); assertTrue(r.zipPresent("nb"))
        val m = r.missing.single()
        assertEquals("usa_2slt.bin", m.name); assertEquals("nb", m.owner); assertEquals(crc(biosRomUs), m.expectedCrc)
        assertEquals(listOf("usa_2slt.bin"), r.outdatedBiosFiles.map { it.name })
        assertEquals(ArcadeRomCheck.OwnerKind.BIOS, r.ownerKind("nb")); assertEquals(ArcadeRomCheck.OwnerKind.GAME, r.ownerKind("g"))
        assertEquals(listOf("nb"), r.issuesByOwner.map { it.first })
        // The non-default euro file is never demanded: a BIOS zip with only the us BIOS is fine …
        zip(dir, "nb", "usa_2slt.bin" to biosRomUs, "sfix.sfx" to sfix)
        assertEquals(Status.OK, ArcadeRomCheck.check(dbNew, g).status)
        // … while the game with ROM_DEFAULT_BIOS "euro" now lacks its BIOS file.
        val ge = ArcadeRomCheck.check(dbNew, zip(dir, "geuro", "p1.bin" to p1))
        assertEquals(Status.MISSING_FILES, ge.status); assertEquals("sp-s2.sp1", ge.missing.single().name)
        // No BIOS zip at all: NEEDS_BIOS listing the default BIOS file (and the shared sfix), not every alternative.
        File(dir, "nb.zip").delete()
        val nb = ArcadeRomCheck.check(dbNew, g)
        assertEquals(Status.NEEDS_BIOS, nb.status); assertEquals("nb", nb.neededZip); assertFalse(nb.zipPresent("nb"))
        assertEquals(setOf("sfix.sfx", "usa_2slt.bin"), nb.missing.map { it.name }.toSet())
        // The legacy DB ([db], no defaultbios column) keeps accepting any one BIOS file.
        val legacyDir = tmp.newFolder()
        zip(legacyDir, "neogeo", "usa_2slt.bin" to biosRomUs, "sfix.sfx" to sfix)
        assertEquals(Status.OK, ArcadeRomCheck.check(db, zip(legacyDir, "parent", "p1.bin" to p1)).status)
    }

    @Test fun deviceRomsAreRequiredFromDeviceZipOrTheGamesSearchPath() {
        val dir = tmp.newFolder()
        val dg = zip(dir, "dg", "p1.bin" to p1)
        // No dev1.zip anywhere: the device zip is what is missing.
        val r = ArcadeRomCheck.check(dbNew, dg)
        assertEquals(r.toString(), Status.NEEDS_DEVICE, r.status); assertEquals(ArcadeRomCheck.Severity.ERROR, r.severity)
        assertEquals("dev1", r.neededZip); assertEquals(listOf("dev1"), r.deviceZips); assertFalse(r.zipPresent("dev1"))
        val m = r.missing.single()
        assertEquals("d1.bin", m.name); assertEquals("dev1", m.owner); assertEquals(crc(devRom), m.expectedCrc)
        assertEquals(ArcadeRomCheck.OwnerKind.DEVICE, r.ownerKind("dev1"))
        // dev1.zip in the folder → OK.
        zip(dir, "dev1", "d1.bin" to devRom)
        val ok = ArcadeRomCheck.check(dbNew, dg)
        assertEquals(Status.OK, ok.status); assertTrue(ok.zipPresent("dev1"))
        // dev1.zip present but with the wrong file: a mismatch (WRONG_SET), not a missing zip.
        zip(dir, "dev1", "d1.bin" to "OLD-DEVICE-ROM".toByteArray())
        val wrong = ArcadeRomCheck.check(dbNew, dg)
        assertEquals(Status.WRONG_SET, wrong.status); assertEquals("dev1", wrong.mismatched.single().owner)
        zip(dir, "dev1", "other.bin" to p1)
        assertEquals(Status.MISSING_FILES, ArcadeRomCheck.check(dbNew, dg).status)
        File(dir, "dev1.zip").delete()
        // MAME also searches the game's own zip: a merged set carrying the device ROM needs no dev1.zip.
        assertEquals(Status.OK, ArcadeRomCheck.check(dbNew, zip(dir, "dg", "p1.bin" to p1, "d1.bin" to devRom)).status)
        // Parent ROM device: devhle (romof devpar) is satisfied by devpar.zip.
        val hg = zip(dir, "hg", "c1.bin" to c1)
        assertEquals(Status.NEEDS_DEVICE, ArcadeRomCheck.check(dbNew, hg).status)
        zip(dir, "devpar", "d1.bin" to devRom)
        val viaParent = ArcadeRomCheck.check(dbNew, hg)
        assertEquals(viaParent.toString(), Status.OK, viaParent.status); assertTrue(viaParent.zipPresent("devpar"))
        // Corrupt game zip (reads as empty): every file is missing, device files with their device as owner; the
        // absent dev1.zip still decides the status.
        File(dir, "dg.zip").writeText("not a zip")
        val corrupt = ArcadeRomCheck.check(dbNew, File(dir, "dg.zip"))
        assertEquals(Status.NEEDS_DEVICE, corrupt.status)
        assertEquals(mapOf("p1.bin" to "dg", "d1.bin" to "dev1"), corrupt.missing.associate { it.name to it.owner })
        // Precedence: an absent BIOS zip outranks an absent device zip; with the BIOS present the device zip is reported.
        val dir2 = tmp.newFolder()
        val multi = zip(dir2, "multi", "c1.bin" to c1)
        zip(dir2, "g", "p1.bin" to p1)
        val noBios = ArcadeRomCheck.check(dbNew, multi)
        assertEquals(Status.NEEDS_BIOS, noBios.status); assertEquals("nb", noBios.neededZip)
        zip(dir2, "nb", "usa_2slt.bin" to biosRomUs, "sfix.sfx" to sfix)
        val noDev = ArcadeRomCheck.check(dbNew, multi)
        assertEquals(Status.NEEDS_DEVICE, noDev.status); assertEquals("dev1", noDev.neededZip)
        assertEquals(listOf("dev1", "devhle"), noDev.issuesByOwner.map { it.first })
        assertEquals(setOf("dev1", "devhle"), noDev.missing.map { it.owner }.toSet())
        zip(dir2, "dev1", "d1.bin" to devRom); zip(dir2, "devpar", "d1.bin" to devRom)
        assertEquals(Status.OK, ArcadeRomCheck.check(dbNew, multi).status)
        // Device entries are never suggested by CRC identification (non-runnable, like BIOS sets).
        assertNull(dbNew.identify(setOf(crc(devRom))))
    }

    /**
     * The user's real failure on the current MAME core (0.289): twcup98 with an older stvbios.zip (no epr-23603.ic8, the
     * 0.289 default BIOS "jp") and no segabill.zip — MAME logged `epr-23603.ic8 NOT FOUND (tried in twcup98 stvbios)`
     * and `epr-18022.ic2 NOT FOUND (tried in segabill twcup98 stvbios)`; the doctor used to report nothing wrong.
     */
    @Test fun realTwcup98OnCurrentMameNeedsTheDefaultBiosAndSegabill() {
        val aMame = asset("cores/mame/assets/romdb.tsv.gz")
        assumeTrue("current MAME romdb asset not found; run cores/mame/gen-romdb.py", aMame != null)
        val mame = aMame!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        val stvbios = mame["stvbios"]!!
        assertFalse(stvbios.runnable)
        assertEquals("jp", stvbios.defaultBios) // no <biosset default="yes"> in 0.289 → first biosset, as MAME does
        assertEquals("epr-23603.ic8", stvbios.roms.first { it.bios == "jp" }.name)
        assertEquals(listOf("segabill"), stvbios.devices)
        val twcup98 = mame["twcup98"]!!
        assertEquals("stvbios", twcup98.bios); assertEquals("jp", twcup98.defaultBios); assertEquals(listOf("segabill"), twcup98.devices)
        assertEquals("sega/stv.cpp", twcup98.sourceFile); assertEquals("stv", twcup98.driverName)
        // Of the 14 merged BIOS rows only the default BIOS file is required (stvbios.nv belongs to the stvbios entry alone).
        assertEquals(listOf("epr-23603.ic8"), twcup98.requiredRoms.filter { it.merged }.map { it.name })
        assertEquals(14, twcup98.roms.count { it.merged }); assertEquals(5, twcup98.ownRoms.size)
        assertEquals(listOf("epr-23603.ic8", "stvbios.nv"), stvbios.requiredRoms.map { it.name })
        val segabill = mame["segabill"]!!
        assertFalse(segabill.runnable); assertTrue(segabill.devices.isEmpty()); assertEquals("", segabill.bios)
        val bill = segabill.roms.single()
        assertEquals("epr-18022.ic2", bill.name); assertEquals(65536L, bill.size); assertEquals("0ca70f80", bill.crc)
        // Device with a parent ROM device: CPS2's qsound_hle loads dl-1425.bin from qsound_hle.zip or qsound.zip.
        assertEquals("qsound", mame["qsound_hle"]!!.romof); assertEquals("", mame["qsound_hle"]!!.bios)
        assertTrue(mame["ssf2"]!!.devices.contains("qsound_hle"))
        assertTrue(mame["sf2"]!!.devices.isEmpty()); assertEquals("euro", mame["mslug"]!!.defaultBios)

        val dir = tmp.newFolder()
        val game = zip(dir, "twcup98", "dummy" to p1)
        // An older ST-V BIOS set: a non-default BIOS file (and the eeprom), but not the 0.289 default epr-23603.ic8.
        zip(dir, "stvbios", "epr-17954a.ic8" to biosRom, "stvbios.nv" to sfix)
        val r = ArcadeRomCheck.check(mame, game, chdSupported = true)
        assertEquals(r.toString(), Status.NEEDS_DEVICE, r.status); assertEquals("segabill", r.neededZip)
        assertTrue(r.biosPresent); assertTrue(r.zipPresent("stvbios")); assertFalse(r.zipPresent("segabill"))
        val byName = r.missing.associateBy { it.name }
        assertEquals("stvbios", byName.getValue("epr-23603.ic8").owner)
        assertEquals("segabill", byName.getValue("epr-18022.ic2").owner)
        assertEquals("twcup98", byName.getValue("epr20819.24").owner)
        assertNull(byName["stvbios.nv"]) // not part of twcup98's rom list
        assertNull(byName["epr-17954a.ic8"]); assertNull(byName["epr-20091.ic8"]) // non-default BIOSes are not demanded
        assertTrue(r.mismatched.isEmpty())
        assertEquals(listOf("epr-23603.ic8"), r.outdatedBiosFiles.map { it.name })
        assertEquals(listOf("twcup98", "stvbios", "segabill"), r.issuesByOwner.map { it.first })
        assertEquals(ArcadeRomCheck.OwnerKind.DEVICE, r.ownerKind("segabill")); assertEquals(ArcadeRomCheck.OwnerKind.BIOS, r.ownerKind("stvbios"))
        // With a (wrong-version) segabill.zip in place the device zip is no longer "needed"; the BIOS file still is.
        zip(dir, "segabill", "epr-18022.ic2" to c1)
        val r2 = ArcadeRomCheck.check(mame, game, chdSupported = true)
        assertEquals(Status.MISSING_FILES, r2.status); assertTrue(r2.zipPresent("segabill"))
        assertEquals("segabill", r2.mismatched.single { it.name == "epr-18022.ic2" }.owner)
        assertTrue(r2.missing.any { it.name == "epr-23603.ic8" && it.owner == "stvbios" })
        // Through resolve with all three real DATs the game is routed to current MAME (ST-V crashes the older cores).
        val a2003 = asset("cores/mame2003plus/assets/romdb.tsv.gz"); val a2010 = asset("cores/mame2010/assets/romdb.tsv.gz")
        assumeTrue(a2003 != null && a2010 != null)
        val dbs = listOf(
            "mame2003plus" to a2003!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) },
            "mame2010" to a2010!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) },
            "mame" to mame,
        )
        val res = ArcadeRomCheck.resolve(dbs, game, chdSupported = { it != "mame2003plus" })
        assertEquals("mame", res.coreId); assertEquals(Status.MISSING_FILES, res.status); assertFalse(res.knownUnstable)
        assertTrue(res.report.missing.any { it.name == "epr-23603.ic8" && it.owner == "stvbios" })
        // The older DATs carry the column too (explicit default="yes" there).
        assertEquals("japan", dbs[0].second["stvbios"]!!.defaultBios); assertEquals("jp", dbs[1].second["stvbios"]!!.defaultBios)
        assertEquals("euro", dbs[0].second["neogeo"]!!.defaultBios); assertTrue(dbs[1].second["twcup98"]!!.devices.isEmpty())
    }

    private fun asset(rel: String): File? = listOf("../$rel", rel).map(::File).firstOrNull { it.isFile }

    /** Tecmo World Cup '98 (ST-V) crashes MAME 2003-Plus: both DATs rate it preliminary, so the newer core must take it. */
    @Test fun realTwcup98RoutesToMame2010() {
        val a2003 = asset("cores/mame2003plus/assets/romdb.tsv.gz")
        val a2010 = asset("cores/mame2010/assets/romdb.tsv.gz")
        assumeTrue("romdb assets not found; run cores/*/gen-romdb.py", a2003 != null && a2010 != null)
        val real2003 = a2003!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        val real2010 = a2010!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) }
        val t2003 = real2003["twcup98"]!!
        val t2010 = real2010["twcup98"]!!
        assertEquals("preliminary", t2003.status); assertEquals("stv.c", t2003.sourceFile); assertEquals("preliminary", t2003.sound)
        assertEquals("preliminary", t2010.status); assertEquals("stv.c", t2010.sourceFile); assertEquals("preliminary", t2010.emulation)
        assertEquals(ArcadeRomCheck.DriverStatus.PRELIMINARY, t2003.driverStatus)
        // 0.78 "protection" and sub-flag normalisation by gen-romdb.py
        assertEquals("protection", real2003["alibaba"]!!.emulation); assertEquals(ArcadeRomCheck.DriverStatus.PRELIMINARY, real2003["alibaba"]!!.driverStatus)
        assertEquals(ArcadeRomCheck.DriverStatus.GOOD, real2003["sf2"]!!.driverStatus); assertEquals("cps1.c", real2003["sf2"]!!.sourceFile)
        assertEquals(ArcadeRomCheck.DriverStatus.UNKNOWN, real2003["neogeo"]!!.driverStatus) // BIOS sets have no <driver>
        assertEquals(ArcadeRomCheck.DriverStatus.GOOD, real2010["mslug"]!!.driverStatus)

        val real = listOf("mame2003plus" to real2003, "mame2010" to real2010)
        val rt = ArcadeRomCheck.routeByName(real, "twcup98")!!
        assertEquals("mame2010", rt.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, rt.reason); assertEquals("mame2003plus", rt.skippedCoreId)
        // … but MAME 2010's SH-2 emulation crashes the process on ST-V as well: the launch must be confirmed.
        assertTrue(rt.knownUnstable)
        // Every ST-V game leaves 2003-Plus when 2010 lists it; the DAT-rated "good" ones for the unstable-driver reason.
        val baku = ArcadeRomCheck.routeByName(real, "bakubaku")!!
        assertEquals("mame2010", baku.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_UNSTABLE, baku.reason); assertTrue(baku.knownUnstable)
        // Games only 2003-Plus knows stay there even when preliminary; good games stay by preference.
        assertEquals("mame2003plus", ArcadeRomCheck.routeByName(real, "sassisu")!!.coreId)
        assertEquals("mame2003plus", ArcadeRomCheck.routeByName(real, "mslug")!!.coreId)
        assertEquals(ArcadeRomCheck.RouteReason.NONE, ArcadeRomCheck.routeByName(real, "mslug")!!.reason)
        assertFalse(ArcadeRomCheck.routeByName(real, "mslug")!!.knownUnstable)
        assertEquals("mame2003plus", ArcadeRomCheck.routeByName(real, "sf2")!!.coreId)
        assertEquals("mame2010", ArcadeRomCheck.routeByName(real, "bldyror2")!!.coreId)
        assertFalse(ArcadeRomCheck.routeByName(real, "bldyror2")!!.knownUnstable)

        val dir = tmp.newFolder()
        val res = ArcadeRomCheck.resolve(real, zip(dir, "twcup98", "dummy" to p1), chdSupported = { it == "mame2010" })
        assertEquals("mame2010", res.coreId); assertEquals(ArcadeRomCheck.RouteReason.PREFERRED_PRELIMINARY, res.reason)
        assertEquals(ArcadeRomCheck.DriverStatus.PRELIMINARY, res.driverStatus)
        assertEquals("twcup98", res.report.game!!.name)
        assertTrue(res.knownUnstable)
        assertFalse(ArcadeRomCheck.resolve(real, zip(dir, "mslug", "dummy" to p1)).knownUnstable)
    }

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
