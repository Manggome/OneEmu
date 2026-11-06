package com.manggome.oneemu.library

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Applies IPS, BPS and UPS patches - the formats fan translations (한글패치) are shipped in - to a ROM,
 * writing the result to a new file. The source is only ever read.
 *
 * Written from the format descriptions, with everything streamed through files: a DS or PSP image
 * can be hundreds of megabytes, so neither the ROM nor the result is ever held in memory.
 *
 * - **IPS**: "PATCH", then records `offset(3, BE) size(2, BE) data`, where size 0 means a run
 *   `count(2, BE) value(1)`; ends with "EOF", optionally followed by a 3-byte length to truncate the
 *   result to. No checksums, so a patch made for another ROM version applies without complaint.
 * - **BPS**: "BPS1", varint source/target/metadata sizes, metadata, then commands
 *   (SourceRead / TargetRead / SourceCopy / TargetCopy) and a footer of three little-endian CRC32s:
 *   source, target, and the patch itself up to that last field.
 * - **UPS**: "UPS1", varint source/target sizes, then runs of "skip N unchanged bytes, XOR bytes up to
 *   and including a 0"; same three-CRC footer as BPS.
 *
 * Failures are [PatchException]s carrying a [Problem] the UI turns into a sentence.
 */
object RomPatcher {
    enum class Format { IPS, BPS, UPS }

    enum class Problem {
        /** Not a patch format we know at all. */
        UNKNOWN_FORMAT,
        /** xdelta (VCDIFF): common for disc games, not supported. */
        XDELTA,
        /** PPF: PS1 patches, not supported. */
        PPF,
        /** Truncated or damaged patch (bad patch CRC, commands running past the end, ...). */
        CORRUPT,
        /** The ROM is not the one the patch was made for (CRC32/size mismatch). [PatchException.expected]/[PatchException.actual] hold the CRCs. */
        WRONG_SOURCE,
        /** The ROM already is the patch's target. */
        ALREADY_PATCHED,
        /** Applying went through but the result does not match the patch's target CRC. */
        BAD_RESULT,

        // Raised by RomPatch (the library side), kept here so the UI has one list to translate.
        SYSTEM_UNSUPPORTED,
        CONTAINER_UNSUPPORTED,
        ZIP_NO_ROM,
        ZIP_MANY_ROMS,
        TOO_LARGE,
        NO_SPACE,
        MISSING_ROM,
        IO,
    }

    class PatchException(
        val problem: Problem,
        val expected: String? = null,
        val actual: String? = null,
    ) : Exception(listOfNotNull(problem.name, expected, actual).joinToString(" "))

    /** The format from the first bytes of [patch]; xdelta/PPF/unknown files throw. */
    fun detect(patch: File): Format {
        val head = ByteArray(8)
        val n = FileInputStream(patch).use { input -> readUpTo(input, head) }
        return detect(head.copyOf(n))
    }

    internal fun detect(head: ByteArray): Format {
        fun starts(magic: String) = head.size >= magic.length && magic.indices.all { head[it] == magic[it].code.toByte() }
        return when {
            starts("PATCH") -> Format.IPS
            starts("BPS1") -> Format.BPS
            starts("UPS1") -> Format.UPS
            head.size >= 3 && (head[0].toInt() and 0xff) == 0xD6 && (head[1].toInt() and 0xff) == 0xC3 && (head[2].toInt() and 0xff) == 0xC4 ->
                throw PatchException(Problem.XDELTA)
            starts("PPF") -> throw PatchException(Problem.PPF)
            else -> throw PatchException(Problem.UNKNOWN_FORMAT)
        }
    }

    /** Applies [patch] to [source], writing [output] (created or overwritten). Returns the format used. */
    fun apply(patch: File, source: File, output: File): Format {
        val format = detect(patch)
        when (format) {
            Format.IPS -> applyIps(patch, source, output)
            Format.BPS -> applyBps(patch, source, output)
            Format.UPS -> applyUps(patch, source, output)
        }
        return format
    }

    // ---- IPS -----------------------------------------------------------------------------------

    private const val IPS_EOF = 0x454F46 // "EOF"

    private fun applyIps(patch: File, source: File, output: File) {
        source.copyTo(output, overwrite = true)
        val total = patch.length()
        var truncate = -1L
        RandomAccessFile(output, "rw").use { out ->
            PatchInput(patch).use { p ->
                p.expect("PATCH")
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    if (total - p.pos < 3) throw corrupt() // no "EOF" marker: cut off
                    val offset = p.u24()
                    if (offset == IPS_EOF) {
                        // Lunar IPS extension: three more bytes give the length to cut the result to.
                        if (total - p.pos == 3L) truncate = p.u24().toLong()
                        break
                    }
                    val size = p.u16()
                    if (size == 0) {
                        val count = p.u16()
                        val value = p.u8().toByte()
                        seekForWrite(out, offset.toLong())
                        var left = count
                        java.util.Arrays.fill(chunk, 0, minOf(count, chunk.size), value)
                        while (left > 0) {
                            val n = minOf(left, chunk.size)
                            out.write(chunk, 0, n)
                            left -= n
                        }
                    } else {
                        val data = ByteArray(size)
                        p.bytes(data, 0, size)
                        seekForWrite(out, offset.toLong())
                        out.write(data)
                    }
                }
            }
            if (truncate >= 0) out.setLength(truncate)
        }
    }

    /** Seeks to [offset], zero-filling up to it first when it lies past the end (the RAF spec leaves that gap undefined). */
    private fun seekForWrite(out: RandomAccessFile, offset: Long) {
        val len = out.length()
        if (offset > len) {
            out.seek(len)
            val zeros = ByteArray(64 * 1024)
            var left = offset - len
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                left -= n
            }
        }
        out.seek(offset)
    }

    // ---- BPS -----------------------------------------------------------------------------------

    private fun applyBps(patch: File, source: File, output: File) {
        val total = patch.length()
        if (total < 4 + 3 + FOOTER) throw corrupt()
        val footer = readFooter(patch)
        if (crc32(patch, total - 4) != footer.patch) throw corrupt()

        PatchInput(patch).use { p ->
            p.expect("BPS1")
            val sourceSize = p.varint()
            val targetSize = p.varint()
            val metadataSize = p.varint()
            if (metadataSize > total) throw corrupt()
            p.skip(metadataSize)
            checkSource(source, sourceSize, targetSize, footer)

            val end = total - FOOTER
            RandomReader(source).use { src ->
                AppendOutput(output).use { out ->
                    var sourceRel = 0L
                    var targetRel = 0L
                    while (p.pos < end) {
                        val data = p.varint()
                        val command = (data and 3L).toInt()
                        val length = (data ushr 2) + 1
                        if (out.size + length > targetSize) throw corrupt()
                        when (command) {
                            0 -> { // SourceRead: the bytes at the same offset in the source
                                val start = out.size
                                if (start + length > sourceSize) throw corrupt()
                                for (i in 0 until length) out.write(src.at(start + i))
                            }
                            1 -> for (i in 0 until length) out.write(p.u8()) // TargetRead: literal bytes
                            2 -> { // SourceCopy: anywhere in the source, relative to the last copy
                                sourceRel += signed(p.varint())
                                if (sourceRel < 0 || sourceRel + length > sourceSize) throw corrupt()
                                for (i in 0 until length) out.write(src.at(sourceRel++))
                            }
                            else -> { // TargetCopy: earlier output; may overlap what it writes (runs)
                                targetRel += signed(p.varint())
                                if (targetRel < 0 || targetRel >= out.size) throw corrupt()
                                for (i in 0 until length) out.write(out.at(targetRel++))
                            }
                        }
                    }
                    if (p.pos != end || out.size != targetSize) throw corrupt()
                }
            }
        }
        if (crc32(output) != footer.target) throw PatchException(Problem.BAD_RESULT, hex(footer.target), hex(crc32(output)))
    }

    /** BPS relative offsets: lowest bit is the sign, the rest the distance. */
    private fun signed(v: Long): Long = if (v and 1L != 0L) -(v ushr 1) else (v ushr 1)

    // ---- UPS -----------------------------------------------------------------------------------

    private fun applyUps(patch: File, source: File, output: File) {
        val total = patch.length()
        if (total < 4 + 2 + FOOTER) throw corrupt()
        val footer = readFooter(patch)
        if (crc32(patch, total - 4) != footer.patch) throw corrupt()

        PatchInput(patch).use { p ->
            p.expect("UPS1")
            val sourceSize = p.varint()
            val targetSize = p.varint()
            checkSource(source, sourceSize, targetSize, footer)

            val end = total - FOOTER
            val limit = maxOf(sourceSize, targetSize)
            RandomReader(source).use { src ->
                AppendOutput(output).use { out ->
                    // Source and output share one offset. Bytes past the source's end read as 0, bytes
                    // past the target's end are dropped.
                    var pos = 0L
                    fun emit(xor: Int) {
                        if (pos < targetSize) out.write(src.at(pos) xor xor)
                        pos++
                    }
                    while (p.pos < end) {
                        val skip = p.varint()
                        if (pos + skip > limit) throw corrupt()
                        for (i in 0 until skip) emit(0)
                        while (true) {
                            if (p.pos >= end || pos >= limit + 1) throw corrupt()
                            val x = p.u8()
                            emit(x)
                            if (x == 0) break // the terminating 0 is one unchanged byte too
                        }
                    }
                    while (pos < targetSize) emit(0)
                    if (p.pos != end) throw corrupt()
                }
            }
        }
        if (crc32(output) != footer.target) throw PatchException(Problem.BAD_RESULT, hex(footer.target), hex(crc32(output)))
    }

    // ---- shared --------------------------------------------------------------------------------

    private const val FOOTER = 12L

    private class Footer(val source: Long, val target: Long, val patch: Long)

    private fun readFooter(patch: File): Footer = RandomAccessFile(patch, "r").use { raf ->
        val b = ByteArray(FOOTER.toInt())
        raf.seek(raf.length() - FOOTER)
        raf.readFully(b)
        fun le(i: Int) = (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or
            ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)
        Footer(le(0), le(4), le(8))
    }

    /**
     * BPS/UPS name the exact ROM they were made from. Checked up front, before anything is written,
     * so the common mistake (another region or revision, a headered dump) gets a clear answer.
     */
    private fun checkSource(source: File, sourceSize: Long, targetSize: Long, footer: Footer) {
        val size = source.length()
        val crc = crc32(source)
        if (size == sourceSize && crc == footer.source) return
        if (size == targetSize && crc == footer.target) throw PatchException(Problem.ALREADY_PATCHED)
        throw PatchException(Problem.WRONG_SOURCE, hex(footer.source), hex(crc))
    }

    fun hex(crc: Long): String = "%08X".format(crc)

    /** CRC32 of the first [limit] bytes of [file] (all of it by default). */
    fun crc32(file: File, limit: Long = file.length()): Long {
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        FileInputStream(file).use { input ->
            var left = limit
            while (left > 0) {
                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n < 0) break
                crc.update(buf, 0, n)
                left -= n
            }
        }
        return crc.value
    }

    private fun corrupt() = PatchException(Problem.CORRUPT)

    private fun readUpTo(input: java.io.InputStream, dst: ByteArray): Int {
        var n = 0
        while (n < dst.size) {
            val r = input.read(dst, n, dst.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** The patch, read front to back, counting its position. Running off the end means a damaged patch. */
    private class PatchInput(file: File) : Closeable {
        private val input = BufferedInputStream(FileInputStream(file), 64 * 1024)
        var pos = 0L
            private set

        fun u8(): Int {
            val b = input.read()
            if (b < 0) throw corrupt()
            pos++
            return b
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()

        fun bytes(dst: ByteArray, off: Int, len: Int) {
            var n = 0
            while (n < len) {
                val r = input.read(dst, off + n, len - n)
                if (r < 0) throw corrupt()
                n += r
            }
            pos += len
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) { u8(); left--; continue }
                left -= s
                pos += s
            }
        }

        fun expect(magic: String) {
            for (c in magic) if (u8() != c.code) throw corrupt()
        }

        /** The variable-length number BPS and UPS share: 7 bits per byte, high bit ends it, each continuation adds one. */
        fun varint(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                val x = u8()
                data += (x and 0x7f) * shift
                if (x and 0x80 != 0) return data
                if (shift > (1L shl 48)) throw corrupt()
                shift = shift shl 7
                data += shift
            }
        }

        override fun close() = input.close()
    }

    /** Random-access reads through a 64 KB window; past the end reads 0 (UPS needs that). */
    private class RandomReader(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val length = raf.length()
        private val win = ByteArray(64 * 1024)
        private var winStart = 0L
        private var winLen = 0

        fun at(pos: Long): Int {
            if (pos < 0 || pos >= length) return 0
            if (pos < winStart || pos >= winStart + winLen) {
                raf.seek(pos)
                winStart = pos
                winLen = 0
                while (winLen < win.size) {
                    val r = raf.read(win, winLen, win.size - winLen)
                    if (r < 0) break
                    winLen += r
                }
                if (winLen == 0) return 0
            }
            return win[(pos - winStart).toInt()].toInt() and 0xff
        }

        override fun close() = raf.close()
    }

    /**
     * The result, written strictly front to back through a 1 MB buffer, and readable back (BPS
     * TargetCopy) both from that buffer and from what has already been flushed - which never changes.
     */
    private class AppendOutput(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
        private val buf = ByteArray(1 shl 20)
        private var bufStart = 0L
        private var bufLen = 0
        private val win = ByteArray(64 * 1024)
        private var winStart = 0L
        private var winLen = 0

        val size: Long get() = bufStart + bufLen

        fun write(b: Int) {
            if (bufLen == buf.size) flush()
            buf[bufLen++] = b.toByte()
        }

        fun at(pos: Long): Int {
            if (pos >= bufStart) return buf[(pos - bufStart).toInt()].toInt() and 0xff
            if (pos < winStart || pos >= winStart + winLen) {
                raf.seek(pos)
                winStart = pos
                val want = minOf(win.size.toLong(), bufStart - pos).toInt()
                winLen = 0
                while (winLen < want) {
                    val r = raf.read(win, winLen, want - winLen)
                    if (r < 0) break
                    winLen += r
                }
            }
            return win[(pos - winStart).toInt()].toInt() and 0xff
        }

        private fun flush() {
            if (bufLen == 0) return
            raf.seek(bufStart)
            raf.write(buf, 0, bufLen)
            bufStart += bufLen
            bufLen = 0
        }

        override fun close() {
            try { flush() } finally { raf.close() }
        }
    }
}
