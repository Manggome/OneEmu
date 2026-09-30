package com.manggome.oneemu.library

import com.manggome.oneemu.util.AppDirs
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Feature-phone (WIPI) save backups. Games save through their own menus; the core keeps those files under
 * `saves/wipi/<key>/` where the key is the product id (PID) of a KTF/LGT package, or the file name otherwise -
 * the same rule as `save_key` in cores/wipi/wie_libretro/src/session.rs. This zips that folder for keeping
 * outside the app and puts one back (WIPI-X's 저장 파일 내보내기 / 가져오기).
 */
object WipiSaves {
    const val EXTENSION = ".wipisave.zip"

    /** Same folder name the core derives for [game]. */
    fun key(game: File): String {
        val pid = runCatching {
            ZipFile(game).use { zip ->
                zip.entries().asSequence().firstOrNull { e ->
                    val n = e.name.substringAfterLast('/')
                    n == "__adf__" || n == "app_info" || n.endsWith(".adf", ignoreCase = true)
                }?.let { e ->
                    zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                        .lineSequence().firstOrNull { it.startsWith("PID:") }?.removePrefix("PID:")?.trim()
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val raw = pid ?: game.nameWithoutExtension
        val clean = raw.trim().map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_' }.joinToString("")
        return if (clean.isEmpty() || clean == "." || clean == "..") "game" else clean
    }

    fun folder(dirs: AppDirs, game: File): File = File(dirs.saves("wipi"), key(game))

    fun hasSave(dirs: AppDirs, game: File): Boolean = folder(dirs, game).walkTopDown().any { it.isFile }

    /** Writes `<OneEmu>/backup/wipi/<title>_<key>.wipisave.zip`; null when the game has not saved anything yet. */
    fun export(dirs: AppDirs, game: File, title: String): File? {
        val src = folder(dirs, game)
        val files = src.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null
        val outDir = File(dirs.dataPath, "backup/wipi").apply { mkdirs() }
        val safeTitle = title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").trim().ifEmpty { "game" }
        val out = File(outDir, "${safeTitle}_${src.name}$EXTENSION")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.relativeTo(src).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /**
     * Replaces the game's saves with the zip's contents. The previous folder is kept as `<key>.before-import`
     * until the next import, so one mistaken restore can still be undone by hand.
     */
    fun import(dirs: AppDirs, game: File, input: InputStream) {
        val dst = folder(dirs, game)
        val staging = File(dst.parentFile, "${dst.name}.importing").apply { deleteRecursively(); mkdirs() }
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(staging, e.name)
                // Refuse entries that would land outside the game's folder.
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) error("잘못된 경로: ${e.name}")
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                count++
            }
        }
        if (count == 0) {
            staging.deleteRecursively()
            error("저장 파일이 들어 있지 않습니다")
        }
        val backup = File(dst.parentFile, "${dst.name}.before-import")
        backup.deleteRecursively()
        if (dst.exists()) dst.renameTo(backup)
        if (!staging.renameTo(dst)) error("저장 폴더를 바꾸지 못했습니다")
    }
}
