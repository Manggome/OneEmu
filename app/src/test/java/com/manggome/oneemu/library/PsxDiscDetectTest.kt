package com.manggome.oneemu.library

import com.manggome.oneemu.library.RomInfo.IsoKind
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PS1 / PS2 / PSP / GC disc detection against tiny synthetic images (2048-byte ISO, raw 2352-byte track,
 * cue→bin, PBP PS1 Classics vs PSP, CHD metadata, m3u) plus the scanner's system tie-breaks. Pure JVM.
 */
class PsxDiscDetectTest {
    @get:Rule val tmp = TemporaryFolder()

    private val psxCnf = "BOOT = cdrom:\\SLUS_012.34;1\r\nTCB = 4\r\nEVENT = 10\r\nSTACK = 801fff00\r\n"
    private val ps2Cnf = "BOOT2 = cdrom0:\\SLUS_200.00;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n"

    // ---- synthetic image builders ----

    /** 2048-byte-sector ISO9660 image: PVD at sector 16 (system id PLAYSTATION), [cnf] as file data at sector 24. */
    private fun iso2048(name: String, cnf: String?, sectors: Int = 40, pvdSystemId: String = "PLAYSTATION", extraAt24: String? = null): File {
        val f = tmp.newFile(name)
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(sectors * 2048L)
            raf.seek(16 * 2048L)
            raf.write(byteArrayOf(1) + "CD001".toByteArray() + byteArrayOf(1, 0) + pvdSystemId.padEnd(32).toByteArray())
            // A root-directory-ish record naming SYSTEM.CNF (so "SYSTEM.CNF" appears, like on real discs).
            raf.seek(22 * 2048L + 100)
            raf.write("SYSTEM.CNF;1".toByteArray())
            val body = cnf ?: extraAt24
            if (body != null) { raf.seek(24 * 2048L); raf.write(body.toByteArray(Charsets.ISO_8859_1)) }
        }
        return f
    }

    /** Raw 2352-byte MODE2/FORM1 track dump: 12 sync + 4 header + 8 subheader, then 2048 user bytes. */
    private fun raw2352(name: String, cnf: String, sectors: Int = 40): File {
        val f = tmp.newFile(name)
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(sectors * 2352L)
            val sync = byteArrayOf(0) + ByteArray(10) { 0xFF.toByte() } + byteArrayOf(0)
            for (s in 0 until sectors) { raf.seek(s * 2352L); raf.write(sync) }
            raf.seek(16 * 2352L + 24)
            raf.write(byteArrayOf(1) + "CD001".toByteArray() + byteArrayOf(1, 0) + "PLAYSTATION".padEnd(32).toByteArray())
            raf.seek(4 * 2352L + 24)
            raf.write("          Licensed  by          Sony Computer Entertainment Inc.".toByteArray())
            raf.seek(24 * 2352L + 24)
            raf.write(cnf.toByteArray(Charsets.ISO_8859_1))
        }
        return f
    }

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun be32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
    private fun be64(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

    /** Minimal PARAM.SFO with a single CATEGORY entry. */
    private fun sfo(category: String): ByteArray {
        val key = "CATEGORY\u0000".toByteArray()
        val keyTable = 0x14 + 16
        val dataTable = (keyTable + key.size + 3) / 4 * 4
        val data = (category + "\u0000").toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) }
        val out = ByteBuffer.allocate(dataTable + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        out.putInt(0x0101).putInt(keyTable).putInt(dataTable).putInt(1)
        out.putShort(0).putShort(0x0204).putInt(category.length + 1).putInt(data.size).putInt(0)
        out.position(keyTable); out.put(key)
        out.position(dataTable); out.put(data)
        return out.array()
    }

    /** PBP: "\0PBP", version, eight offsets (PARAM.SFO … DATA.PSAR), then the SFO and the PSAR payload. */
    private fun pbp(name: String, category: String?, psar: String): File {
        val sfoBytes = category?.let { sfo(it) } ?: ByteArray(0)
        val sfoOff = 0x28
        val psarOff = sfoOff + sfoBytes.size
        val buf = ByteBuffer.allocate(psarOff + 64).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0, 'P'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())).putInt(0x00010000)
        buf.putInt(sfoOff)
        for (i in 0 until 6) buf.putInt(psarOff) // icon0, icon1, pic0, pic1, snd0, data.psp: empty
        buf.putInt(psarOff)
        buf.position(sfoOff); buf.put(sfoBytes)
        buf.position(psarOff); buf.put(psar.toByteArray(Charsets.ISO_8859_1))
        return tmp.newFile(name).also { it.writeBytes(buf.array()) }
    }

    /** CHD v5 header (124 bytes) with one metadata entry of [tag]. */
    private fun chd(name: String, tag: String?, size: Int = 4096): File {
        val buf = ByteBuffer.allocate(size)
        buf.put("MComprHD".toByteArray()).put(be32(124)).put(be32(5))
        buf.put("cdzl".toByteArray()).put("cdzs".toByteArray()).put("cdfl".toByteArray()).put(ByteArray(4))
        buf.put(be64(700L * 1024 * 1024)).put(be64(1024)).put(be64(if (tag != null) 124L else 0L))
        if (tag != null) {
            buf.position(124)
            val text = "TRACK:1 TYPE:MODE2_RAW SUBTYPE:NONE FRAMES:1000 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0\u0000".toByteArray()
            buf.put(tag.toByteArray(Charsets.ISO_8859_1)).put(be32(text.size)).put(be64(0)).put(text)
        }
        return tmp.newFile(name).also { it.writeBytes(buf.array()) }
    }

    // ---- RomInfo ----

    @Test fun iso2048_psx() = assertEquals(IsoKind.PSX, RomInfo.isoKind(iso2048("a.iso", psxCnf)))
    @Test fun iso2048_psxNoSpaces() = assertEquals(IsoKind.PSX, RomInfo.isoKind(iso2048("b.iso", "BOOT=cdrom:\\PROGRAM.EXE;1\r\n")))
    @Test fun iso2048_ps2() = assertEquals(IsoKind.PS2, RomInfo.isoKind(iso2048("c.iso", ps2Cnf)))
    @Test fun iso2048_psp() = assertEquals(IsoKind.PSP, RomInfo.isoKind(iso2048("d.iso", null, pvdSystemId = "PSP GAME", extraAt24 = "PSP_GAME")))
    @Test fun raw2352_psx() = assertEquals(IsoKind.PSX, RomInfo.isoKind(raw2352("e.bin", psxCnf)))
    @Test fun raw2352_ps2() = assertEquals(IsoKind.PS2, RomInfo.isoKind(raw2352("f.bin", ps2Cnf)))
    @Test fun unknownImage() = assertEquals(IsoKind.UNKNOWN, RomInfo.isoKind(tmp.newFile("g.iso").also { it.writeBytes(ByteArray(0x20000)) }))

    @Test fun playstationWithoutBootLine_sizeTieBreak() {
        // SYSTEM.CNF content outside the sniff window: CD-sized → PS1, DVD-sized → PS2.
        val text = "\u0001CD001 PLAYSTATION ... SYSTEM.CNF;1"
        assertEquals(IsoKind.PSX, RomInfo.discKindFromHead(text, 650L * 1024 * 1024))
        assertEquals(IsoKind.PS2, RomInfo.discKindFromHead(text, 4L * 1024 * 1024 * 1024))
        assertEquals(IsoKind.PS2, RomInfo.discKindFromHead("BOOT = cdrom:\\X;1 and BOOT2 = cdrom0:\\Y;1", 100))
        assertEquals(IsoKind.UNKNOWN, RomInfo.discKindFromHead("nothing here", 100))
    }

    @Test fun gcMagicWins() {
        val f = tmp.newFile("gc.iso")
        RandomAccessFile(f, "rw").use { raf -> raf.setLength(0x20000); raf.seek(0x1C); raf.write(byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D)) }
        assertEquals(IsoKind.GC, RomInfo.isoKind(f))
    }

    @Test fun cue_followsFirstFile() {
        val dir = tmp.newFolder("game")
        val bin = File(dir, "Game (USA).bin").also { it.writeBytes(raw2352("tmp1.bin", psxCnf).readBytes()) }
        val cue = File(dir, "Game (USA).cue").also {
            it.writeText("FILE \"Game (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\nFILE \"Game (USA) (Track 2).bin\" BINARY\n  TRACK 02 AUDIO\n")
        }
        assertEquals(bin, RomInfo.cueFirstFile(cue))
        assertEquals(IsoKind.PSX, RomInfo.cueKind(cue))
    }

    @Test fun cue_stalePathFallsBackToName() {
        val dir = tmp.newFolder("game2")
        File(dir, "disc.bin").writeBytes(raw2352("tmp2.bin", ps2Cnf).readBytes())
        val cue = File(dir, "disc.cue").also { it.writeText("FILE \"C:\\Users\\me\\rips\\disc.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n") }
        assertEquals(File(dir, "disc.bin"), RomInfo.cueFirstFile(cue))
        assertEquals(IsoKind.PS2, RomInfo.cueKind(cue))
        val orphan = File(dir, "orphan.cue").also { it.writeText("FILE missing.bin BINARY\n") }
        assertEquals(IsoKind.UNKNOWN, RomInfo.cueKind(orphan))
    }

    @Test fun pbp_ps1ClassicsVsPsp() {
        assertEquals(IsoKind.PSX, RomInfo.pbpKind(pbp("ps1.pbp", "ME", "PSISOIMG0000")))
        assertEquals(IsoKind.PSX, RomInfo.pbpKind(pbp("ps1multi.pbp", "ME", "PSTITLEIMG000000")))
        assertEquals(IsoKind.PSX, RomInfo.pbpKind(pbp("ps1nosfo.pbp", null, "PSISOIMG0000")))
        assertEquals(IsoKind.PSP, RomInfo.pbpKind(pbp("psp.pbp", "UG", "PSPVERSION\u0000")))
        assertEquals(IsoKind.PSP, RomInfo.pbpKind(pbp("homebrew.pbp", "MG", "")))
        assertEquals(IsoKind.UNKNOWN, RomInfo.pbpKind(tmp.newFile("junk.pbp").also { it.writeBytes(ByteArray(64)) }))
    }

    @Test fun chd_metadataTags() {
        assertEquals(RomInfo.ChdKind.CD, RomInfo.chdKind(chd("cd.chd", "CHT2")))
        assertEquals(RomInfo.ChdKind.DVD, RomInfo.chdKind(chd("dvd.chd", "DVD ")))
        assertEquals(RomInfo.ChdKind.HDD, RomInfo.chdKind(chd("hdd.chd", "GDDD")))
        assertEquals(RomInfo.ChdKind.UNKNOWN, RomInfo.chdKind(chd("nometa.chd", null)))
        assertEquals(RomInfo.ChdKind.UNKNOWN, RomInfo.chdKind(tmp.newFile("junk.chd").also { it.writeBytes(ByteArray(256)) }))
    }

    @Test fun m3u_firstEntry() {
        val dir = tmp.newFolder("multi")
        val d1 = File(dir, "Game (Disc 1).cue").also { it.writeText("FILE \"x.bin\" BINARY\n") }
        val m3u = File(dir, "Game.m3u").also { it.writeText("\uFEFF# playlist\n\nGame (Disc 1).cue|Disc 1\nGame (Disc 2).cue|Disc 2\n") }
        assertEquals(d1, RomInfo.m3uFirstEntry(m3u))
        assertNull(RomInfo.m3uFirstEntry(File(dir, "empty.m3u").also { it.writeText("# only comments\n") }))
    }

    @Test fun psxExeMagic() {
        assertTrue(RomInfo.isPsxExe(tmp.newFile("hb.exe").also { it.writeBytes("PS-X EXE".toByteArray() + ByteArray(2040)) }))
        assertFalse(RomInfo.isPsxExe(tmp.newFile("setup.exe").also { it.writeBytes("MZ".toByteArray() + ByteArray(100)) }))
    }

    // ---- RomScanner.resolveSystem tie-breaks ----

    private val isoCandidates = listOf(SystemId.PSP, SystemId.PS2, SystemId.GC, SystemId.PSX)
    private val lookup: (String) -> List<SystemId>? = { ext ->
        when (ext) {
            "iso" -> isoCandidates
            "chd" -> listOf(SystemId.PSP, SystemId.PS2, SystemId.ARCADE, SystemId.PSX)
            "cue" -> listOf(SystemId.PS2, SystemId.PSX)
            "pbp" -> listOf(SystemId.PSP, SystemId.PSX)
            "m3u" -> listOf(SystemId.GC, SystemId.PSX)
            "bin", "img", "exe" -> listOf(SystemId.PSX)
            "rvz" -> listOf(SystemId.GC)
            else -> null
        }
    }
    private fun resolve(f: File) = RomScanner.resolveSystem(f, f.extension.lowercase(), lookup(f.extension.lowercase()).orEmpty(), lookup)

    @Test fun resolve_isoBySniff() {
        assertEquals(SystemId.PSX, resolve(iso2048("r1.iso", psxCnf)))
        assertEquals(SystemId.PS2, resolve(iso2048("r2.iso", ps2Cnf)))
        assertEquals(SystemId.PSP, resolve(iso2048("r3.iso", null, pvdSystemId = "PSP GAME", extraAt24 = "PSP_GAME")))
        // Unknown small .iso keeps the old PSP default (PS1 discs always carry PLAYSTATION in the PVD).
        assertEquals(SystemId.PSP, resolve(tmp.newFile("r4.iso").also { it.writeBytes(ByteArray(0x20000)) }))
    }

    @Test fun resolve_cueAndBin() {
        val dir = tmp.newFolder("rc")
        File(dir, "g.bin").writeBytes(raw2352("tmp3.bin", psxCnf).readBytes())
        val cue = File(dir, "g.cue").also { it.writeText("FILE \"g.bin\" BINARY\n  TRACK 01 MODE2/2352\n") }
        assertEquals(SystemId.PSX, resolve(cue))
        // Missing .bin: PS1 is the common case for cue sheets.
        assertEquals(SystemId.PSX, resolve(File(dir, "orphan.cue").also { it.writeText("FILE \"nope.bin\" BINARY\n") }))
        File(dir, "p.bin").writeBytes(raw2352("tmp4.bin", ps2Cnf).readBytes())
        assertEquals(SystemId.PS2, resolve(File(dir, "p.cue").also { it.writeText("FILE \"p.bin\" BINARY\n") }))
        // Lone .bin: only when the image proves to be a PS1 disc.
        assertEquals(SystemId.PSX, resolve(File(dir, "g.bin")))
        assertNull(resolve(File(dir, "p.bin")))
        assertNull(resolve(File(dir, "junk.bin").also { it.writeBytes(ByteArray(0x20000)) }))
    }

    @Test fun resolve_pbp() {
        assertEquals(SystemId.PSX, resolve(pbp("r5.pbp", "ME", "PSISOIMG0000")))
        assertEquals(SystemId.PSP, resolve(pbp("r6.pbp", "UG", "x")))
        assertEquals(SystemId.PSP, resolve(tmp.newFile("r7.pbp").also { it.writeBytes(ByteArray(64)) }))
    }

    @Test fun resolve_chd() {
        assertEquals(SystemId.PSX, resolve(chd("r8.chd", "CHT2")))          // small CD image
        assertEquals(SystemId.PSP, resolve(chd("r9.chd", "DVD ")))          // small DVD image
        assertEquals(SystemId.ARCADE, resolve(chd("r10.chd", "GDDD")))      // hard disk → MAME
        assertEquals(SystemId.PSX, resolve(chd("r11.chd", null)))           // unknown small → PS1 first
    }

    @Test fun resolve_m3uFollowsFirstEntry() {
        val dir = tmp.newFolder("rm")
        File(dir, "d1.bin").writeBytes(raw2352("tmp5.bin", psxCnf).readBytes())
        File(dir, "d1.cue").writeText("FILE \"d1.bin\" BINARY\n")
        assertEquals(SystemId.PSX, resolve(File(dir, "game.m3u").also { it.writeText("d1.cue\nd2.cue\n") }))
        File(dir, "wii.rvz").writeBytes(ByteArray(16))
        assertEquals(SystemId.GC, resolve(File(dir, "gc.m3u").also { it.writeText("wii.rvz\n") }))
        assertEquals(SystemId.PSX, resolve(File(dir, "broken.m3u").also { it.writeText("missing.cue\n") }))
    }

    @Test fun resolve_exe() {
        assertEquals(SystemId.PSX, resolve(tmp.newFile("r12.exe").also { it.writeBytes("PS-X EXE".toByteArray() + ByteArray(2040)) }))
        assertNull(resolve(tmp.newFile("r13.exe").also { it.writeBytes("MZ".toByteArray() + ByteArray(100)) }))
    }

    @Test fun resolve_singleCandidateShortcut() {
        assertEquals(SystemId.PSX, resolve(tmp.newFile("r14.img").also { it.writeBytes(ByteArray(16)) }))
        assertNull(RomScanner.resolveSystem(tmp.newFile("r15.xyz"), "xyz", emptyList(), lookup))
    }
}
