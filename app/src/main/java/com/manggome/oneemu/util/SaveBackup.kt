package com.manggome.oneemu.util

import android.util.Log
import com.manggome.oneemu.data.db.AppDatabase
import com.manggome.oneemu.data.db.CheatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One zip holding everything a player would hate to lose: battery saves, save states with their
 * thumbnails, and the cheats typed into the app. It is an ordinary zip, so it can be opened, kept
 * in a cloud folder, or carried to another phone.
 *
 * Only the two save folders are ever read or written, and an entry pointing anywhere else is
 * dropped, so a hand-edited zip cannot write outside them.
 */
object SaveBackup {
    const val MIME = "application/zip"

    /** Folders under the app's own directory that the backup covers. */
    private val ROOTS = listOf("saves", "states")
    private const val CHEATS = "cheats.tsv"
    private const val MANIFEST = "oneemu-backup.txt"

    data class Stats(val files: Int, val bytes: Long, val cheats: Int)
    data class ImportResult(val files: Int, val cheats: Int)

    fun suggestedName(): String =
        "OneEmu-saves-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"

    /** What a backup would contain right now, for the settings screen to show. */
    suspend fun stats(dirs: AppDirs, db: AppDatabase): Stats = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        for (root in ROOTS) {
            val base = dirs.saveRoot(root)
            base.walkTopDown().forEach {
                if (!it.isFile || isRebuildable(it.relativeTo(base).invariantSeparatorsPath)) return@forEach
                files++
                bytes += it.length()
            }
        }
        Stats(files, bytes, db.cheats().allOnce().size)
    }

    /** Writes the backup into [out]; the caller owns the stream. Returns how much went in. */
    suspend fun export(dirs: AppDirs, db: AppDatabase, out: OutputStream): Stats = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        val cheats = cheatLines(db)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write("OneEmu save data\ncreated=${Date()}\n".toByteArray())
            zip.closeEntry()
            for (root in ROOTS) {
                val base = dirs.saveRoot(root)
                base.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    val relative = file.relativeTo(base).invariantSeparatorsPath
                    if (isRebuildable(relative)) return@forEach
                    val name = "$root/$relative"
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                    bytes += file.length()
                }
            }
            if (cheats.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(CHEATS))
                zip.write(cheats.joinToString("\n").toByteArray())
                zip.closeEntry()
            }
        }
        Stats(files, bytes, cheats.size)
    }

    /**
     * Restores a backup, overwriting files of the same name. Cheats are matched to the library by
     * ROM file name, because the ids and the full paths differ between installs; a cheat for a game
     * this phone does not have is dropped rather than left dangling.
     */
    suspend fun import(dirs: AppDirs, db: AppDatabase, input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        var files = 0
        var cheats = 0
        val byFileName = db.games().allOnce().associateBy { File(it.path).name.lowercase() }
        val existing = db.cheats().allOnce().map { it.gameId to it.code.trim() }.toHashSet()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val name = entry.name.replace('\\', '/')
                if (name == CHEATS) {
                    cheats += restoreCheats(db, zip.readBytes().decodeToString(), byFileName, existing)
                    zip.closeEntry()
                    continue
                }
                val target = resolve(dirs, name)
                if (target == null || isRebuildable(name.substringAfter('/'))) { zip.closeEntry(); continue }
                target.parentFile?.mkdirs()
                runCatching { target.outputStream().use { zip.copyTo(it) } }
                    .onSuccess { files++ }
                    .onFailure { Log.w(TAG, "could not restore $name", it) }
                zip.closeEntry()
            }
        }
        ImportResult(files, cheats)
    }

    /**
     * Files a core leaves next to the saves that it will simply make again: shader caches, which
     * can run to hundreds of megabytes, and logs. Backing them up would dwarf the saves themselves.
     */
    internal fun isRebuildable(relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.any { it.equals("Cache", true) || it.equals("Logs", true) || it.equals("ShaderCache", true) }) return true
        val name = parts.last().lowercase()
        return name.endsWith(".log") || name.endsWith(".log.gz")
    }

    /** Where an entry may be written, or null when it does not belong to the backup. */
    private fun resolve(dirs: AppDirs, name: String): File? {
        val root = ROOTS.firstOrNull { name.startsWith("$it/") } ?: return null
        return safeTarget(dirs.saveRoot(root), name.substringAfter('/'))
    }

    /**
     * [relative] resolved under [base], or null when it escapes it. Both paths are canonicalized
     * first, so "../", an absolute name and a symlinked parent all come out as null.
     */
    internal fun safeTarget(base: File, relative: String): File? {
        // An absolute name would be joined onto the base rather than escaping it, but it still says
        // something we did not write, so it is refused outright.
        if (relative.isEmpty() || relative.startsWith("/")) return null
        val target = File(base, relative)
        val basePath = runCatching { base.canonicalPath }.getOrNull() ?: return null
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
        return if (targetPath.startsWith(basePath + File.separator)) target else null
    }

    /** "rom file name \t name \t code \t enabled", with tabs and newlines squeezed out of the fields. */
    private suspend fun cheatLines(db: AppDatabase): List<String> {
        val games = db.games().allOnce().associateBy { it.id }
        return db.cheats().allOnce().mapNotNull { cheat ->
            val game = games[cheat.gameId] ?: return@mapNotNull null
            listOf(
                File(game.path).name,
                cheat.name,
                cheat.code.replace('\n', '+'),
                if (cheat.enabled) "1" else "0",
            ).joinToString("\t") { it.replace('\t', ' ') }
        }
    }

    private suspend fun restoreCheats(
        db: AppDatabase,
        text: String,
        byFileName: Map<String, com.manggome.oneemu.data.db.GameEntity>,
        existing: HashSet<Pair<Long, String>>,
    ): Int {
        var restored = 0
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val game = byFileName[parts[0].lowercase()] ?: continue
            val code = parts[2].trim()
            if (code.isEmpty() || !existing.add(game.id to code)) continue
            db.cheats().upsert(
                CheatEntity(gameId = game.id, name = parts[1], code = code, enabled = parts.getOrNull(3) != "0"),
            )
            restored++
        }
        return restored
    }

    private const val TAG = "SaveBackup"
}
