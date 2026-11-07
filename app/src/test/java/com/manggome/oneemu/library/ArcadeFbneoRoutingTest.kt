package com.manggome.oneemu.library

import com.manggome.oneemu.library.ArcadeRomCheck.RouteReason
import com.manggome.oneemu.library.ArcadeRomCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * FBNeo in the arcade router ([ArcadeRomCheck.routeByName] / [ArcadeRomCheck.resolve]): Neo Geo and CPS games that
 * FBNeo marks working run on FBNeo first, the MAME cores stay the fallback (other boards, games FBNeo marks not
 * working, and zips that are MAME-only sets), and games only FBNeo lists go to FBNeo.
 */
class ArcadeFbneoRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    // Contents of the synthetic sets. "new" = the FBNeo/current-MAME dump, "old" = the MAME 0.78 dump (other CRCs).
    private val newC1 = "MSLUG-C1-NEW".toByteArray()
    private val oldC1 = "MSLUG-C1-OLD".toByteArray()
    private val p1 = "MSLUG-P1".toByteArray()
    private val newBios = "SP-S3".toByteArray()
    private val newLo = "000-LO-128K".toByteArray()
    private val oldBios = "SP-S2".toByteArray()
    private val oldLo = "MAMELO-64K".toByteArray()
    private val cps = "SFA-ROM".toByteArray()
    private val other = "OTHER-ROM".toByteArray()

    private fun crc(b: ByteArray): String = String.format("%08x", CRC32().apply { update(b) }.value)
    private fun rom(name: String, data: ByteArray, merge: String = ""): String =
        "$name:${data.size}:${crc(data)}" + if (merge.isNotEmpty()) ":$merge" else ""

    /** name description cloneof romof bios sampleof needsSamples disks runnable roms status sourcefile … */
    private fun line(name: String, romof: String, bios: String, runnable: Boolean, roms: List<String>, status: String, src: String) =
        listOf(name, "Game $name", "", romof, bios, "", "0", "0", if (runnable) "1" else "0", roms.joinToString(";"), status, src, "", "", "", "")
            .joinToString("\t")

    private fun db(vararg lines: String) = ArcadeRomCheck.Db.parse((lines.joinToString("\n") + "\n").byteInputStream())

    /** FBNeo DAT (sourcefile = FBNeo driver path). */
    private val fbneoDb = db(
        line("neogeo", "", "", false, listOf(rom("sp-s3.sp1", newBios), rom("000-lo.lo", newLo)), "", "neogeo/d_neogeo.cpp"),
        line("mslug", "neogeo", "neogeo", true, listOf(rom("201-p1.p1", p1), rom("201-c1.c1", newC1), rom("sp-s3.sp1", newBios, "="), rom("000-lo.lo", newLo, "=")), "good", "neogeo/d_neogeo.cpp"),
        line("sfa", "", "", true, listOf(rom("sfa.bin", cps)), "good", "capcom/d_cps2.cpp"),
        line("sfiii", "", "", true, listOf(rom("sfiii.bin", cps)), "good", "cps3/d_cps3.cpp"),
        line("ngbroken", "neogeo", "neogeo", true, listOf(rom("b.bin", other)), "preliminary", "neogeo/d_neogeo.cpp"),
        line("pacman", "", "", true, listOf(rom("pac.bin", other)), "good", "pre90s/d_pacman.cpp"),
        line("fbonly", "", "", true, listOf(rom("fbo.bin", other)), "good", "pst90s/d_fbonly.cpp"),
        line("ngfbonly", "neogeo", "neogeo", true, listOf(rom("ngo.bin", other)), "good", "neogeo/d_neogeo.cpp"),
    )

    /** MAME 2003-Plus DAT (0.78 file names/CRCs for the Neo Geo set). */
    private val mame2003Db = db(
        line("neogeo", "", "", false, listOf(rom("sp-s2.sp1", oldBios), rom("mamelo.lo", oldLo)), "", "neogeo.c"),
        line("mslug", "neogeo", "neogeo", true, listOf(rom("201-p1.bin", p1), rom("201-c1.bin", oldC1), rom("sp-s2.sp1", oldBios, "="), rom("mamelo.lo", oldLo, "=")), "good", "neogeo.c"),
        line("sfa", "", "", true, listOf(rom("sfa.bin", cps)), "good", "cps2.c"),
        line("ngbroken", "neogeo", "neogeo", true, listOf(rom("b.bin", other)), "good", "neogeo.c"),
        line("pacman", "", "", true, listOf(rom("pac.bin", other)), "good", "pacman.c"),
        line("mameonly", "", "", true, listOf(rom("m.bin", other)), "good", "galaga.c"),
    )

    /** MAME 2010 DAT: lists sfiii (CPS3) which 2003-Plus lacks. */
    private val mame2010Db = db(
        line("sfiii", "", "", true, listOf(rom("sfiii.bin", cps)), "imperfect", "cps3.c"),
    )

    private val dbs = listOf("mame2003plus" to mame2003Db, "mame2010" to mame2010Db, "fbneo" to fbneoDb)

    private fun zip(dir: File, name: String, vararg files: Pair<String, ByteArray>): File {
        val f = File(dir, "$name.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, data) in files) { z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry() }
        }
        return f
    }

    private fun rt(name: String) = ArcadeRomCheck.routeByName(dbs, name)!!

    @Test fun neoGeoAndCpsGamesGoToFbneoFirstWithTheMamePickAsFallback() {
        val ms = rt("mslug")
        assertEquals("fbneo", ms.coreId)
        assertEquals(RouteReason.FBNEO_BOARD, ms.reason)
        assertEquals("mame2003plus", ms.skippedCoreId)
        assertEquals("mame2003plus", ms.fallback!!.coreId)
        assertFalse(ms.knownUnstable)
        // CPS2 and CPS3 are FBNeo boards too; the fallback is whatever the MAME rule picks (2010 for sfiii).
        assertEquals("fbneo", rt("sfa").coreId); assertEquals("mame2003plus", rt("sfa").fallback!!.coreId)
        assertEquals("fbneo", rt("sfiii").coreId); assertEquals("mame2010", rt("sfiii").skippedCoreId)
        // Upper-case zip names route the same way (DB lookups are case-insensitive).
        assertEquals("fbneo", rt("MSLUG").coreId)
    }

    @Test fun otherBoardsAndNonWorkingFbneoGamesStayOnMame() {
        // Pac-Man is on a board FBNeo is not preferred for: the MAME rule decides, FBNeo does not compete.
        val pac = rt("pacman")
        assertEquals("mame2003plus", pac.coreId); assertEquals(RouteReason.NONE, pac.reason); assertNull(pac.fallback)
        // FBNeo marks this Neo Geo game not working (preliminary) → MAME.
        assertEquals("mame2003plus", rt("ngbroken").coreId)
        assertEquals("mame2003plus", rt("mameonly").coreId)
    }

    @Test fun gamesOnlyFbneoListsGoToFbneo() {
        val fb = rt("fbonly")
        assertEquals("fbneo", fb.coreId); assertEquals(RouteReason.NONE, fb.reason); assertNull(fb.skippedCoreId); assertNull(fb.fallback)
        val ng = rt("ngfbonly")
        assertEquals("fbneo", ng.coreId); assertEquals(RouteReason.NONE, ng.reason); assertNull(ng.fallback)
        assertNull(ArcadeRomCheck.routeByName(dbs, "nothing"))
        // BIOS sets are never "FBNeo board games" (not runnable): neogeo itself follows the MAME rule.
        assertEquals("mame2003plus", rt("neogeo").coreId)
    }

    @Test fun withoutFbneoDbTheMameRuleIsUnchanged() {
        val mameOnly = listOf("mame2003plus" to mame2003Db, "mame2010" to mame2010Db)
        assertEquals("mame2003plus", ArcadeRomCheck.routeByName(mameOnly, "mslug")!!.coreId)
        assertEquals(RouteReason.NONE, ArcadeRomCheck.routeByName(mameOnly, "mslug")!!.reason)
        assertEquals("mame2010", ArcadeRomCheck.routeByName(mameOnly, "sfiii")!!.coreId)
        assertNull(ArcadeRomCheck.routeByName(mameOnly, "fbonly"))
    }

    @Test fun anFbneoSetWithItsBiosRunsOnFbneo() {
        val dir = tmp.newFolder()
        zip(dir, "neogeo", "sp-s3.sp1" to newBios, "000-lo.lo" to newLo)
        val res = ArcadeRomCheck.resolve(dbs, zip(dir, "mslug", "201-p1.p1" to p1, "201-c1.c1" to newC1), chdSupported = { it != "mame2003plus" })
        assertEquals("fbneo", res.coreId); assertEquals(Status.OK, res.status)
        assertEquals(RouteReason.FBNEO_BOARD, res.reason); assertEquals("mame2003plus", res.skippedCoreId)
        assertEquals("neogeo", res.report.biosZip); assertTrue(res.report.biosPresent)
    }

    @Test fun anOldMameSetFallsBackToTheMameCore() {
        val dir = tmp.newFolder()
        // MAME 0.78 Metal Slug + 0.78 neogeo.zip: FBNeo cannot load it (other C-ROM CRC, other BIOS files).
        zip(dir, "neogeo", "sp-s2.sp1" to oldBios, "mamelo.lo" to oldLo)
        val res = ArcadeRomCheck.resolve(dbs, zip(dir, "mslug", "201-p1.bin" to p1, "201-c1.bin" to oldC1))
        assertEquals("mame2003plus", res.coreId); assertEquals(Status.OK, res.status)
        assertEquals(RouteReason.FBNEO_SET_MISMATCH, res.reason); assertEquals("fbneo", res.skippedCoreId)
    }

    @Test fun whenNeitherSetIsCompleteFbneoStaysAndSaysWhatItNeeds() {
        val dir = tmp.newFolder()
        // FBNeo game set, but no neogeo.zip at all: the report is FBNeo's (BIOS needed), not a silent MAME fallback.
        val noBios = ArcadeRomCheck.resolve(dbs, zip(dir, "mslug", "201-p1.p1" to p1, "201-c1.c1" to newC1))
        assertEquals("fbneo", noBios.coreId); assertEquals(Status.NEEDS_BIOS, noBios.status); assertEquals("neogeo", noBios.report.neededZip)
        // FBNeo game set + an old 0.78 neogeo.zip: FBNeo's BIOS files are missing from it (outdated BIOS set),
        // and MAME 2003-Plus would not take the new C-ROM either → stays on FBNeo with the BIOS files listed.
        zip(dir, "neogeo", "sp-s2.sp1" to oldBios, "mamelo.lo" to oldLo)
        val oldBiosSet = ArcadeRomCheck.resolve(dbs, File(dir, "mslug.zip"))
        assertEquals("fbneo", oldBiosSet.coreId); assertEquals(Status.MISSING_FILES, oldBiosSet.status)
        assertEquals(setOf("sp-s3.sp1", "000-lo.lo"), oldBiosSet.report.outdatedBiosFiles.map { it.name }.toSet())
    }

    @Test fun aSetWhoseOnlyFlawIsAnOlderBiosZipGoesToTheMameCoreThatTakesIt() {
        // Galaxy S24+ report: MAME 2010's mslug.zip (FBNeo's C-ROMs) next to an older neogeo.zip whose 000-lo.lo is
        // the 64 KB dump. FBNeo wants the exact BIOS file and MAME 2003-Plus other C-ROMs; MAME 2010 finds every file
        // by name and plays it with a checksum warning, so it gets the game.
        val m2010 = db(
            line("neogeo", "", "", false, listOf(rom("sp-s3.sp1", newBios), rom("000-lo.lo", newLo)), "", "neogeo.c"),
            line("mslug", "neogeo", "neogeo", true, listOf(rom("201-p1.bin", p1), rom("201-c1.bin", newC1), rom("sp-s3.sp1", newBios, "="), rom("000-lo.lo", newLo, "=")), "good", "neogeo.c"),
        )
        val all = listOf("mame2003plus" to mame2003Db, "mame2010" to m2010, "fbneo" to fbneoDb)
        val dir = tmp.newFolder()
        zip(dir, "neogeo", "sp-s3.sp1" to newBios, "000-lo.lo" to oldLo)
        val res = ArcadeRomCheck.resolve(all, zip(dir, "mslug", "201-p1.bin" to p1, "201-c1.bin" to newC1), chdSupported = { it != "mame2003plus" })
        assertEquals("mame2010", res.coreId); assertEquals(RouteReason.FBNEO_SET_MISMATCH, res.reason); assertEquals("fbneo", res.skippedCoreId)
        assertTrue(res.report.missing.isEmpty()); assertEquals(listOf("000-lo.lo"), res.report.mismatched.map { it.name })
        // With the 000-lo.lo missing by name too, no MAME core takes it either: FBNeo stays and lists the BIOS files.
        zip(dir, "neogeo", "sp-s3.sp1" to newBios, "mamelo.lo" to oldLo)
        assertEquals("fbneo", ArcadeRomCheck.resolve(all, File(dir, "mslug.zip")).coreId)
    }

    @Test fun renamedFbneoSetIsIdentifiedAndRoutedToFbneo() {
        val dir = tmp.newFolder()
        zip(dir, "neogeo", "sp-s3.sp1" to newBios, "000-lo.lo" to newLo)
        val res = ArcadeRomCheck.resolve(dbs, zip(dir, "metalslug", "201-p1.p1" to p1, "201-c1.c1" to newC1))
        assertEquals(Status.RENAME_SUGGESTED, res.status); assertEquals("mslug", res.report.suggestedName)
        assertEquals("fbneo", res.coreId)
    }

    // ---- real DATs ------------------------------------------------------------------------------

    private fun asset(rel: String): File? = listOf("../$rel", rel).map(::File).firstOrNull { it.isFile }

    /** The reported case: mslug.zip used to go to MAME 2003-Plus; with FBNeo's DAT it goes to FBNeo. */
    @Test fun realDatsSendMetalSlugToFbneoAndKeepOtherBoardsOnMame() {
        val paths = listOf("mame2003plus", "mame2010", "mame", "fbneo").associateWith { asset("cores/$it/assets/romdb.tsv.gz") }
        assumeTrue("romdb assets not found; run cores/*/gen-romdb.py", paths.values.all { it != null })
        val real = paths.map { (id, f) -> id to f!!.inputStream().use { ArcadeRomCheck.Db.parseGzip(it) } }
        val fb = real.toMap().getValue("fbneo")

        val mslug = ArcadeRomCheck.routeByName(real, "mslug")!!
        assertEquals("fbneo", mslug.coreId); assertEquals(RouteReason.FBNEO_BOARD, mslug.reason); assertEquals("mame2003plus", mslug.skippedCoreId)
        assertEquals("d_neogeo", mslug.game.driverName); assertEquals("neogeo", mslug.game.bios)
        // FBNeo's neogeo.zip: only the default MVS BIOS and the shared Z80/fix/LO ROMs are required (the other
        // BIOS revisions are BRF_OPT in d_neogeo.cpp and dropped by gen-romdb.py).
        assertEquals(setOf("sp-s3.sp1", "sm1.sm1", "sfix.sfix", "000-lo.lo"), fb["neogeo"]!!.requiredRoms.map { it.name }.toSet())
        assertEquals(setOf("sp-s3.sp1", "sm1.sm1", "sfix.sfix", "000-lo.lo"), mslug.game.requiredRoms.filter { it.merged }.map { it.name }.toSet())
        // CPS1/2/3 go to FBNeo as well; CPS1's PLD dumps are BRF_OPT and not demanded.
        for (n in listOf("sf2", "sfa", "sfiii")) assertEquals(n, "fbneo", ArcadeRomCheck.routeByName(real, n)!!.coreId)
        assertTrue(fb["sf2"]!!.roms.none { it.name == "buf1" })
        // Other boards keep their MAME core.
        assertEquals("mame2003plus", ArcadeRomCheck.routeByName(real, "pacman")!!.coreId)
        assertEquals("mame", ArcadeRomCheck.routeByName(real, "twcup98")!!.coreId)
    }
}
