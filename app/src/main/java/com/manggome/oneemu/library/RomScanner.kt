package com.manggome.oneemu.library

import android.content.Context
import android.graphics.Bitmap
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.data.db.AppDatabase
import com.manggome.oneemu.data.db.FolderEntity
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Walks library folders, decides which system each file belongs to, extracts titles/icons,
 * and keeps the games table in sync (adds new files, removes vanished ones).
 */
class RomScanner(
    private val context: Context,
    private val db: AppDatabase,
    private val registry: CoreRegistry,
    private val dirs: AppDirs,
) {
    data class Progress(val running: Boolean = false, val folder: String = "", val found: Int = 0, val total: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> get() = _progress

    private val extMap: Map<String, List<SystemId>> by lazy { registry.extensionToSystems() }

    suspend fun scanAll() = withContext(Dispatchers.IO) {
        for (folder in db.folders().allOnce()) scanFolder(folder)
    }

    suspend fun scanFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        _progress.value = Progress(running = true, folder = folder.path)
        try {
            val root = File(folder.path)
            if (!root.isDirectory) return@withContext
            val files = if (folder.recursive) root.walkTopDown().maxDepth(8).filter { it.isFile }.toList()
            else root.listFiles()?.filter { it.isFile } ?: emptyList()
            val existing = db.games().allOnce().associateBy { it.path }
            val seen = HashSet<String>()
            var found = 0
            for ((i, f) in files.withIndex()) {
                val ext = f.extension.lowercase()
                if (ext !in extMap) continue
                if (isSkippable(f, ext)) continue
                seen.add(f.absolutePath)
                if (existing.containsKey(f.absolutePath)) continue
                val entity = identify(f, ext, folder.id) ?: continue
                db.games().insert(entity)
                found++
                if (i % 20 == 0) _progress.value = Progress(true, folder.path, found, files.size)
            }
            // Remove entries from this folder whose files vanished.
            val gone = existing.values.filter { it.folderId == folder.id && it.path !in seen && !File(it.path).exists() }
            if (gone.isNotEmpty()) db.games().deleteIds(gone.map { it.id })
            db.folders().update(folder.copy(lastScannedAt = System.currentTimeMillis()))
        } finally {
            _progress.value = Progress(running = false)
        }
    }

    /** Adds a single file the user picked manually. */
    suspend fun addFile(file: File): GameEntity? = withContext(Dispatchers.IO) {
        val ext = file.extension.lowercase()
        if (ext !in extMap) return@withContext null
        db.games().getByPath(file.absolutePath)?.let { return@withContext it }
        val entity = identify(file, ext, null) ?: return@withContext null
        val id = db.games().insert(entity)
        if (id > 0) entity.copy(id = id) else db.games().getByPath(file.absolutePath)
    }

    /** Multi-disc/track companions and BIOS packs we should not list as games. */
    private fun isSkippable(f: File, ext: String): Boolean {
        val name = f.nameWithoutExtension.lowercase()
        if (ext == "bin") return true // only reachable through .cue
        if (ext == "zip" && name in mameBiosNames) return true
        return false
    }

    private val mameBiosNames = setOf("neogeo", "pgm", "stvbios", "decocass", "cvs", "playch10", "skns", "konamigx", "nss", "megaplay", "megatech")

    private fun identify(f: File, ext: String, folderId: Long?): GameEntity? {
        val candidates = extMap[ext] ?: return null
        val system = when {
            candidates.size == 1 -> candidates.first()
            ext == "zip" -> zipSystem(f, candidates)
            ext in setOf("iso", "chd", "cso") -> when (RomInfo.isoKind(f)) {
                RomInfo.IsoKind.PSP -> SystemId.PSP
                RomInfo.IsoKind.PS2 -> SystemId.PS2
                RomInfo.IsoKind.UNKNOWN -> when {
                    ext == "cso" -> SystemId.PSP
                    f.length() > 2_000_000_000L -> SystemId.PS2
                    else -> SystemId.PSP
                }
            }
            ext == "elf" -> if (SystemId.PSP in candidates) SystemId.PSP else candidates.first()
            else -> candidates.first()
        } ?: return null

        var title = RomInfo.cleanTitle(f.name)
        var autoIcon: String? = null
        if (system == SystemId.NDS) {
            RomInfo.ndsBanner(f)?.let { b ->
                autoIcon = saveIcon(b.icon, f)
                // Keep the user's (usually Korean) file name as the title; the banner title is a fallback.
                if (title.isBlank()) title = b.bestTitle ?: title
            }
        }
        return GameEntity(
            path = f.absolutePath,
            title = title,
            system = system.id,
            autoIcon = autoIcon,
            fileSize = f.length(),
            folderId = folderId,
        )
    }

    /** A .zip may be a zipped ROM (GBA/NES/...) or a MAME romset; peek inside. */
    private fun zipSystem(f: File, candidates: List<SystemId>): SystemId? = runCatching {
        ZipFile(f).use { zip ->
            val names = zip.entries().asSequence().map { it.name.lowercase() }.take(200).toList()
            for (sys in SystemId.entries) {
                if (sys == SystemId.ARCADE) continue
                if (names.any { n -> sys.extensions.any { n.endsWith(".$it") } }) return@runCatching sys
            }
            // MAME sets contain many extension-less or .bin/.rom files and no known ROM extension.
            if (SystemId.ARCADE in candidates) SystemId.ARCADE else candidates.first()
        }
    }.getOrNull()

    private fun saveIcon(bmp: Bitmap, rom: File): String? = runCatching {
        val out = File(dirs.thumbnails, "auto_${AppDirs.sanitize(rom.nameWithoutExtension)}_${rom.length()}.png")
        if (!out.exists()) out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.absolutePath
    }.getOrNull()
}
