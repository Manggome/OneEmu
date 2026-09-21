package com.manggome.oneemu.library

import java.io.File

/**
 * Which files on disk actually make up one library entry. A .cue is nothing without its .bin
 * tracks and a .m3u is nothing without the discs it lists, so deleting a game has to mean all of
 * them — otherwise the folder fills up with orphans the scanner can no longer explain.
 */
object RomFiles {
    /** How deep a playlist is followed: an .m3u lists .cue files, which list their tracks. */
    private const val MAX_DEPTH = 2

    /**
     * [rom] plus every file it references, in the order they would be listed to the user. Only
     * files that exist and sit inside [rom]'s own folder are included, so a hand-edited playlist
     * cannot point the deletion at something else.
     */
    fun of(rom: File): List<File> {
        val out = LinkedHashSet<File>()
        collect(rom, rom.parentFile ?: return listOf(rom), out, MAX_DEPTH)
        return out.toList()
    }

    private fun collect(file: File, root: File, out: MutableSet<File>, depth: Int) {
        if (!out.add(file) || depth <= 0) return
        val text = runCatching { if (file.length() <= MAX_TEXT_BYTES) file.readText() else null }.getOrNull() ?: return
        val referenced = when (file.extension.lowercase()) {
            "cue" -> parseCue(text)
            "m3u", "m3u8" -> parseM3u(text)
            else -> return
        }
        for (name in referenced) {
            val target = resolveInside(root, file.parentFile ?: root, name) ?: continue
            collect(target, root, out, depth - 1)
        }
    }

    /** [name] resolved against [from], or null when it escapes [root] or is not a plain file. */
    private fun resolveInside(root: File, from: File, name: String): File? {
        val target = File(from, name)
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val path = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (!path.startsWith(rootPath + File.separator)) return null
        return target.takeIf { it.isFile }
    }

    /** `FILE "track01.bin" BINARY` and the unquoted form. */
    fun parseCue(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("FILE", ignoreCase = true)) return@mapNotNull null
        val rest = trimmed.substring(4).trim()
        if (rest.startsWith('"')) rest.drop(1).substringBefore('"').takeIf { it.isNotBlank() }
        else rest.substringBeforeLast(' ').trim().takeIf { it.isNotBlank() }
    }.toList()

    /** One path per line; blanks and #EXTM3U directives are not files. */
    fun parseM3u(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()

    private const val MAX_TEXT_BYTES = 1L * 1024 * 1024
}
