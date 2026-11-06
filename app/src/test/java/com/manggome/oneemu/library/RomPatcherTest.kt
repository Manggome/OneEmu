package com.manggome.oneemu.library

import com.manggome.oneemu.library.RomPatcher.Format
import com.manggome.oneemu.library.RomPatcher.PatchException
import com.manggome.oneemu.library.RomPatcher.Problem
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** IPS / BPS / UPS from small hand-built patches, and the library-side copy that wraps them. */
class RomPatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- building blocks -----------------------------------------------------------------------

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).also { it.writeBytes(bytes) }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

    /** The BPS/UPS number encoding, written out from the description: 7 bits per byte, last byte has the high bit, each continuation subtracts one. */
    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (true) {
            val x = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) { write(0x80 or x); return }
            write(x)
            v--
        }
    }

    private fun ByteArrayOutputStream.u32le(v: Long) {
        for (i in 0 until 4) write(((v ushr (8 * i)) and 0xff).toInt())
    }

    /** Appends source CRC, target CRC and the patch's own CRC. */
    private fun ByteArrayOutputStream.footer(source: ByteArray, target: ByteArray, targetCrc: Long = crc(target)): ByteArray {
        u32le(crc(source))
        u32le(targetCrc)
        u32le(crc(toByteArray()))
        return toByteArray()
    }

    private fun applyBytes(patch: ByteArray, source: ByteArray): ByteArray {
        val out = File(tmp.root, "out-${System.nanoTime()}.bin")
        RomPatcher.apply(file("p-${System.nanoTime()}", patch), file("s-${System.nanoTime()}", source), out)
        return out.readBytes()
    }

    private fun expectProblem(problem: Problem, block: () -> Unit): PatchException {
        try {
            block()
        } catch (e: PatchException) {
            assertEquals(problem, e.problem)
            return e
        }
        fail("expected $problem")
        throw AssertionError()
    }

    // ---- varint --------------------------------------------------------------------------------

    @Test fun `varint encoding matches the hand-worked bytes`() {
        val b = ByteArrayOutputStream().apply { varint(0); varint(127); varint(128); varint(300) }.toByteArray()
        // 0 -> 80, 127 -> FF, 128 -> 00 80, 300 -> 2C 81 (300 = 0x2C + 128*(1+1))
        assertArrayEquals(bytes(0x80, 0xFF, 0x00, 0x80, 0x2C, 0x81), b)
    }

    // ---- format detection ----------------------------------------------------------------------

    @Test fun `formats are told apart by their magic, and unsupported ones say which`() {
        assertEquals(Format.IPS, RomPatcher.detect(ascii("PATCHEOF")))
        assertEquals(Format.BPS, RomPatcher.detect(ascii("BPS1")))
        assertEquals(Format.UPS, RomPatcher.detect(ascii("UPS1")))
        expectProblem(Problem.XDELTA) { RomPatcher.detect(bytes(0xD6, 0xC3, 0xC4, 0x00)) }
        expectProblem(Problem.PPF) { RomPatcher.detect(ascii("PPF30")) }
        expectProblem(Problem.UNKNOWN_FORMAT) { RomPatcher.detect(ascii("readme")) }
        expectProblem(Problem.UNKNOWN_FORMAT) { RomPatcher.detect(ByteArray(0)) }
    }

    // ---- IPS -----------------------------------------------------------------------------------

    @Test fun `IPS writes plain records`() {
        val source = ascii("ABCDEFGH")
        // offset 000002, size 0003, "xyz"; then EOF
        val patch = ascii("PATCH") + bytes(0, 0, 2, 0, 3) + ascii("xyz") + ascii("EOF")
        assertArrayEquals(ascii("ABxyzFGH"), applyBytes(patch, source))
    }

    @Test fun `IPS RLE records repeat one byte`() {
        val source = ascii("ABCDEFGH")
        // offset 000001, size 0000 (RLE), count 0004, value '*'
        val patch = ascii("PATCH") + bytes(0, 0, 1, 0, 0, 0, 4, '*'.code) + ascii("EOF")
        assertArrayEquals(ascii("A****FGH"), applyBytes(patch, source))
    }

    @Test fun `IPS records past the end grow the file, zero-filling the gap`() {
        val source = ascii("AB")
        val patch = ascii("PATCH") + bytes(0, 0, 4, 0, 2) + ascii("yz") + ascii("EOF")
        assertArrayEquals(bytes('A'.code, 'B'.code, 0, 0, 'y'.code, 'z'.code), applyBytes(patch, source))
    }

    @Test fun `IPS truncate extension cuts the result`() {
        val source = ascii("ABCDEFGH")
        val patch = ascii("PATCH") + bytes(0, 0, 0, 0, 1) + ascii("a") + ascii("EOF") + bytes(0, 0, 5)
        assertArrayEquals(ascii("aBCDE"), applyBytes(patch, source))
    }

    @Test fun `IPS without its EOF marker is reported as damaged`() {
        val patch = ascii("PATCH") + bytes(0, 0, 0, 0, 4) + ascii("ab") // record cut short
        expectProblem(Problem.CORRUPT) { applyBytes(patch, ascii("ABCDEFGH")) }
    }

    // ---- BPS -----------------------------------------------------------------------------------

    private val bpsSource = ascii("Hello, world!")
    private val bpsTarget = ascii("Hello, OneEmu! ababab world")

    /** Exercises every BPS command once. */
    private fun bpsPatch(source: ByteArray = bpsSource, target: ByteArray = bpsTarget, targetCrc: Long = crc(target)): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(ascii("BPS1"))
        p.varint(source.size.toLong())
        p.varint(target.size.toLong())
        p.varint(3); p.write(ascii("abc")) // metadata, skipped
        fun cmd(kind: Int, length: Int) = p.varint(((length - 1).toLong() shl 2) or kind.toLong())
        cmd(0, 7)                                   // SourceRead "Hello, "
        cmd(1, 10); p.write(ascii("OneEmu! ab"))    // TargetRead
        cmd(3, 4); p.varint((15L shl 1) or 0)       // TargetCopy from 15 ("ab"), overlapping: "abab"
        cmd(1, 1); p.write(ascii(" "))              // TargetRead " "
        cmd(2, 5); p.varint((7L shl 1) or 0)        // SourceCopy from 7: "world"
        return p.footer(source, target, targetCrc)
    }

    @Test fun `BPS applies source reads, literal bytes and both kinds of copy`() {
        assertArrayEquals(bpsTarget, applyBytes(bpsPatch(), bpsSource))
    }

    @Test fun `BPS source copy can move backwards`() {
        val source = ascii("ABCDEF")
        val target = ascii("DEFABC")
        val p = ByteArrayOutputStream()
        p.write(ascii("BPS1")); p.varint(6); p.varint(6); p.varint(0)
        p.varint((2L shl 2) or 2); p.varint((3L shl 1) or 0) // copy 3 from +3 -> "DEF", rel now 6
        p.varint((2L shl 2) or 2); p.varint((6L shl 1) or 1) // copy 3 from 6-6=0 -> "ABC"
        assertArrayEquals(target, applyBytes(p.footer(source, target), source))
    }

    @Test fun `BPS refuses a ROM with a different CRC and names both`() {
        val other = ascii("Hello, World!") // one letter off, same size
        val e = expectProblem(Problem.WRONG_SOURCE) { applyBytes(bpsPatch(), other) }
        assertEquals(RomPatcher.hex(crc(bpsSource)), e.expected)
        assertEquals(RomPatcher.hex(crc(other)), e.actual)
    }

    @Test fun `BPS on the already-patched ROM says so instead of failing vaguely`() {
        expectProblem(Problem.ALREADY_PATCHED) { applyBytes(bpsPatch(), bpsTarget) }
    }

    @Test fun `BPS with a damaged byte fails its own CRC`() {
        val patch = bpsPatch()
        patch[20] = (patch[20].toInt() xor 0x01).toByte()
        expectProblem(Problem.CORRUPT) { applyBytes(patch, bpsSource) }
    }

    @Test fun `BPS whose result does not match the target CRC is rejected`() {
        val patch = bpsPatch(targetCrc = crc(bpsTarget) xor 0x1234)
        expectProblem(Problem.BAD_RESULT) { applyBytes(patch, bpsSource) }
    }

    @Test fun `BPS commands running past the target size are damage`() {
        val p = ByteArrayOutputStream()
        p.write(ascii("BPS1")); p.varint(4); p.varint(2); p.varint(0)
        p.varint((3L shl 2) or 0) // SourceRead 4 into a 2-byte target
        expectProblem(Problem.CORRUPT) { applyBytes(p.footer(ascii("ABCD"), ascii("AB")), ascii("ABCD")) }
    }

    // ---- UPS -----------------------------------------------------------------------------------

    /** Hand-built the way a UPS maker lays it out: skip count, XOR bytes, a 0 that is itself one more byte. */
    private fun upsPatch(source: ByteArray, target: ByteArray): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(ascii("UPS1"))
        p.varint(source.size.toLong())
        p.varint(target.size.toLong())
        val max = maxOf(source.size, target.size)
        fun at(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xff else 0
        var last = 0
        var i = 0
        while (i < max) {
            if (at(source, i) == at(target, i)) { i++; continue }
            p.varint((i - last).toLong())
            while (i < max && at(source, i) != at(target, i)) { p.write(at(source, i) xor at(target, i)); i++ }
            p.write(0); i++
            last = i
        }
        return p.footer(source, target)
    }

    @Test fun `UPS XORs the changed runs`() {
        val source = ascii("The quick brown fox")
        val target = ascii("The QUICK brown cat")
        assertArrayEquals(target, applyBytes(upsPatch(source, target), source))
    }

    @Test fun `UPS grows and shrinks the file to the target size`() {
        val small = ascii("ABCD")
        val big = ascii("ABxDEFGH")
        assertArrayEquals(big, applyBytes(upsPatch(small, big), small))
        assertArrayEquals(small, applyBytes(upsPatch(big, small), big))
    }

    @Test fun `UPS hand-written bytes decode as expected`() {
        // source "AAAA" -> target "ABAA": skip 1, XOR 'A'^'B' = 03, terminating 00
        val p = ByteArrayOutputStream()
        p.write(ascii("UPS1")); p.write(bytes(0x84, 0x84, 0x81, 0x03, 0x00))
        assertArrayEquals(ascii("ABAA"), applyBytes(p.footer(ascii("AAAA"), ascii("ABAA")), ascii("AAAA")))
    }

    @Test fun `UPS refuses another ROM version`() {
        val source = ascii("version 1.0")
        val target = ascii("version 1.0 KR")
        val e = expectProblem(Problem.WRONG_SOURCE) { applyBytes(upsPatch(source, target), ascii("version 1.1")) }
        assertEquals(RomPatcher.hex(crc(source)), e.expected)
    }

    @Test fun `UPS on the already-patched ROM says so`() {
        val source = ascii("version 1.0")
        val target = ascii("version 1.0 KR")
        expectProblem(Problem.ALREADY_PATCHED) { applyBytes(upsPatch(source, target), target) }
    }

    // ---- larger than the buffers ---------------------------------------------------------------

    @Test fun `BPS target copies reach back into data already flushed to disk`() {
        // 3 MB: past the 1 MB output buffer, so the copy reads the file back.
        val size = 3 * 1024 * 1024
        val source = ByteArray(size) { (it * 31 + 7).toByte() }
        val target = source.copyOf()
        for (i in 0 until 1024) target[size - 1024 + i] = source[i] // tail repeats the start of the output
        val p = ByteArrayOutputStream()
        p.write(ascii("BPS1")); p.varint(size.toLong()); p.varint(size.toLong()); p.varint(0)
        p.varint(((size - 1024 - 1).toLong() shl 2) or 0) // SourceRead all but the last 1 KB
        p.varint(((1024 - 1).toLong() shl 2) or 3); p.varint(0) // TargetCopy from 0
        assertArrayEquals(target, applyBytes(p.footer(source, target), source))
    }

    // ---- the library copy ----------------------------------------------------------------------

    private val gba = SystemId.GBA

    private fun patchCopy(rom: File, patch: File) =
        RomPatch.apply(rom, gba, gba.extensions, patch, File(tmp.root, "temp"), File(tmp.root, "patched"))

    @Test fun `the copy lands next to the ROM and the original is untouched`() {
        val dir = tmp.newFolder("games")
        val rom = File(dir, "Mother 3.gba").also { it.writeBytes(bpsSource) }
        val result = patchCopy(rom, file("ko.bps", bpsPatch()))
        assertEquals(File(dir, "Mother 3 (패치).gba"), result.file)
        assertEquals(Format.BPS, result.format)
        assertArrayEquals(bpsTarget, result.file.readBytes())
        assertArrayEquals(bpsSource, rom.readBytes())
        // A second run does not overwrite the first copy.
        assertEquals(File(dir, "Mother 3 (패치 2).gba"), patchCopy(rom, file("ko2.bps", bpsPatch())).file)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun `a failed patch leaves nothing behind`() {
        val dir = tmp.newFolder("games")
        val rom = File(dir, "game.gba").also { it.writeBytes(ascii("not the right rom")) }
        expectProblem(Problem.WRONG_SOURCE) { patchCopy(rom, file("ko.bps", bpsPatch())) }
        assertEquals(listOf("game.gba"), dir.list()!!.toList())
    }

    @Test fun `a zipped ROM is patched from its single entry and written unzipped`() {
        val dir = tmp.newFolder("games")
        val zip = File(dir, "Game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("readme.txt")); z.write(ascii("hi")); z.closeEntry()
            z.putNextEntry(ZipEntry("sub/Game (K).gba")); z.write(bpsSource); z.closeEntry()
        }
        val result = patchCopy(zip, file("ko.bps", bpsPatch()))
        assertEquals(File(dir, "Game (패치).gba"), result.file)
        assertArrayEquals(bpsTarget, result.file.readBytes())
        assertTrue(File(tmp.root, "temp").listFiles().orEmpty().isEmpty())
    }

    @Test fun `a zip with several ROMs is refused rather than guessed`() {
        val zip = File(tmp.newFolder("games"), "Pack.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.gba")); z.write(bpsSource); z.closeEntry()
            z.putNextEntry(ZipEntry("b.gba")); z.write(bpsSource); z.closeEntry()
        }
        expectProblem(Problem.ZIP_MANY_ROMS) { patchCopy(zip, file("ko.bps", bpsPatch())) }
    }

    @Test fun `compressed and multi-file images are refused`() {
        val cue = file("game.cue", ascii("FILE \"game.bin\" BINARY"))
        val e = expectProblem(Problem.CONTAINER_UNSUPPORTED) {
            RomPatch.apply(cue, SystemId.PSX, SystemId.PSX.extensions, file("ko.bps", bpsPatch()), tmp.root, tmp.root)
        }
        assertEquals("cue", e.expected)
    }

    @Test fun `arcade and feature-phone games are not offered`() {
        assertFalse(RomPatch.canPatch(SystemId.ARCADE, "/x/sf2.zip"))
        assertFalse(RomPatch.canPatch(SystemId.WIPI, "/x/game.zip"))
        assertFalse(RomPatch.canPatch(SystemId.NES, "core:something"))
        assertTrue(RomPatch.canPatch(SystemId.NDS, "/x/game.nds"))
    }
}
