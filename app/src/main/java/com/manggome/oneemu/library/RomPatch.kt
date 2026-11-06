package com.manggome.oneemu.library

import com.manggome.oneemu.emu.EmulatorSession
import com.manggome.oneemu.library.RomPatcher.PatchException
import com.manggome.oneemu.library.RomPatcher.Problem
import com.manggome.oneemu.model.SystemId
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 패치 적용: makes a patched copy of a library game. The original file is never written to; the copy
 * goes next to it as "<name> (패치).<ext>" (or "(패치 2)" ... when that exists), or into
 * [fallbackDir] when the ROM's folder cannot be written.
 *
 * Zipped ROMs: the single ROM inside is extracted to a temp file, patched, and the result written
 * unzipped - patches are made against the bare ROM, and every core reads it as well as the zip. A zip
 * holding several ROMs is refused rather than guessed at.
 *
 * Formats that are compressed or span several files (.chd, .cso, .cue + tracks, ...) cannot be
 * patched byte for byte and are refused with the reason.
 */
object RomPatch {
    /** Systems whose "ROM" is not one patchable file: MAME sets, phone packages, a PC game folder. */
    private val UNSUPPORTED_SYSTEMS = setOf(SystemId.ARCADE, SystemId.WIPI, SystemId.JAZZ2)

    /** Compressed or multi-file images; a patch is made against the raw bytes, which these are not. */
    val CONTAINER_EXTS = setOf(
        "cue", "m3u", "m3u8", "toc", "chd", "cso", "pbp", "isz", "gcz", "rvz", "wia", "wbfs", "ciso",
        "7z", "rar", "jar", "jad",
    )

    /** Larger than any single-file image we run (a dual-layer PS2 DVD is ~8.5 GB but those are not patched this way). */
    const val MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024

    /** Left free on top of the copy's own size, so patching never fills the phone to the last byte. */
    private const val SPACE_MARGIN = 32L * 1024 * 1024

    const val SUFFIX = "패치"

    data class Result(val file: File, val format: RomPatcher.Format)

    /** Whether the action is offered for this game at all (container formats are explained, not hidden). */
    fun canPatch(system: SystemId?, path: String): Boolean =
        system != null && system !in UNSUPPORTED_SYSTEMS && !path.startsWith(EmulatorSession.NO_CONTENT_PREFIX)

    /** "<base> (패치).<ext>" in [dir], numbered when taken. */
    fun outputFor(dir: File, baseName: String, ext: String): File {
        val dot = if (ext.isEmpty()) "" else ".$ext"
        var n = 1
        while (true) {
            val label = if (n == 1) SUFFIX else "$SUFFIX $n"
            val f = File(dir, "$baseName ($label)$dot")
            if (!f.exists()) return f
            n++
        }
    }

    /**
     * Applies [patch] to [rom] and returns the new file. [romExtensions] are the system's ROM
     * extensions, used to find the ROM inside a zip. Throws [PatchException]; on failure nothing is
     * left behind.
     */
    fun apply(
        rom: File,
        system: SystemId?,
        romExtensions: Set<String>,
        patch: File,
        tempDir: File,
        fallbackDir: File,
    ): Result {
        if (!canPatch(system, rom.path)) throw PatchException(Problem.SYSTEM_UNSUPPORTED)
        if (!rom.isFile) throw PatchException(Problem.MISSING_ROM)
        val ext = rom.extension.lowercase()
        if (ext in CONTAINER_EXTS) throw PatchException(Problem.CONTAINER_UNSUPPORTED, ext)
        // Before any copying: a wrong file (xdelta, a readme) is the most likely mistake.
        RomPatcher.detect(patch)

        var extracted: File? = null
        try {
            val source: File
            val outExt: String
            if (ext == "zip") {
                extracted = extractSingleRom(rom, romExtensions, tempDir)
                source = extracted
                outExt = extracted.extension
            } else {
                source = rom
                outExt = rom.extension
            }
            if (source.length() > MAX_SOURCE_BYTES) throw PatchException(Problem.TOO_LARGE)

            val needed = source.length() + SPACE_MARGIN
            val dir = listOf(rom.parentFile, fallbackDir).firstOrNull { d ->
                d != null && d.isDirectory && d.canWrite() && d.usableSpace >= needed
            } ?: run {
                val writable = listOf(rom.parentFile, fallbackDir).any { it != null && it.isDirectory && it.canWrite() }
                throw PatchException(if (writable) Problem.NO_SPACE else Problem.IO, needed.toString())
            }
            return write(patch, source, dir, rom.nameWithoutExtension, outExt)
                ?: write(patch, source, fallbackDir.also { it.mkdirs() }, rom.nameWithoutExtension, outExt)
                ?: throw PatchException(Problem.IO)
        } finally {
            extracted?.delete()
        }
    }

    /**
     * Patches into a hidden part file in [dir] and renames it into place, so a crash or a bad patch
     * never leaves a half-written game for the scanner to list. Null when [dir] refuses the write.
     */
    private fun write(patch: File, source: File, dir: File, baseName: String, ext: String): Result? {
        val target = outputFor(dir, baseName, ext)
        val part = File(dir, ".${target.name}.part")
        part.delete() // left over from a run that was killed
        val created = try { part.createNewFile() } catch (e: IOException) { false } catch (e: SecurityException) { false }
        if (!created) return null
        var done = false
        try {
            val format = RomPatcher.apply(patch, source, part)
            if (!part.renameTo(target)) part.copyTo(target, overwrite = false)
            done = true
            return Result(target, format)
        } catch (e: IOException) {
            throw PatchException(Problem.IO)
        } finally {
            part.delete()
            // outputFor picked a name nobody had, so anything there now is our own half-copy.
            if (!done) target.delete()
        }
    }

    /** The one ROM file in [zip], extracted into [tempDir]. */
    private fun extractSingleRom(zip: File, romExtensions: Set<String>, tempDir: File): File {
        val zf = try { ZipFile(zip) } catch (e: IOException) { throw PatchException(Problem.ZIP_NO_ROM) }
        zf.use { z ->
            val roms = z.entries().asSequence()
                .filter { !it.isDirectory && File(it.name).extension.lowercase() in romExtensions }
                .toList()
            if (roms.isEmpty()) throw PatchException(Problem.ZIP_NO_ROM)
            if (roms.size > 1) throw PatchException(Problem.ZIP_MANY_ROMS, roms.size.toString())
            val entry = roms.single()
            if (entry.size > MAX_SOURCE_BYTES) throw PatchException(Problem.TOO_LARGE)
            tempDir.mkdirs()
            // Only the entry's own name: a path inside the zip must not steer where it lands.
            val out = File(tempDir, "patch-src-${System.nanoTime()}-${File(entry.name).name}")
            try {
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
            } catch (e: IOException) {
                out.delete()
                throw PatchException(Problem.IO)
            }
            return out
        }
    }
}
