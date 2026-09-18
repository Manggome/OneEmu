package com.manggome.oneemu.library

import android.content.Context
import android.graphics.Bitmap
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.data.db.AppDatabase
import com.manggome.oneemu.data.db.FolderEntity
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.emu.EmulatorSession
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
    private val arcadeTitles by lazy { ArcadeTitles(context) }

    suspend fun scanAll() = withContext(Dispatchers.IO) {
        for (folder in db.folders().allOnce()) scanFolder(folder)
    }

    suspend fun scanFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        _progress.value = Progress(running = true, folder = folder.path)
        cueCache.clear()
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
                existing[f.absolutePath]?.let { old ->
                    // Disc images keep the system they were given when first scanned. Detection improves over time
                    // (PS1 support arrived after many libraries already held .iso files as PSP), so re-run it for
                    // ambiguous extensions and move the entry when the user has not pinned a core themselves.
                    if (ext in AMBIGUOUS_EXTS && old.coreId == null) {
                        val sys = extMap[ext]?.let { resolveSystem(f, ext, it, lookup = { e -> extMap[e] }, zip = ::zipSystem) }
                        if (sys != null && sys.id != old.system) db.games().update(old.copy(system = sys.id))
                    }
                    continue
                }
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

    /**
     * Cores that run without a ROM (Jazz² Resurrection) get one library entry, so the user has something to
     * tap when their game data sits in the system directory where no scan can find it.
     *
     * Exactly one entry per such system: when a scan did find the game in a library folder, that entry is the
     * one to tap and this placeholder is removed again. Two entries that look alike and both start the same
     * game is the confusing case this avoids.
     */
    suspend fun ensureNoContentEntries() = withContext(Dispatchers.IO) {
        val all = db.games().allOnce()
        for (core in registry.cores) {
            if (!core.supportsNoContent || !registry.isAvailable(core)) continue
            val system = core.systems.firstOrNull()?.let { SystemId.fromId(it) } ?: continue
            val path = EmulatorSession.NO_CONTENT_PREFIX + core.id
            val placeholder = all.firstOrNull { it.path == path }
            val scanned = all.any { it.system == system.id && !it.hidden && !it.path.startsWith(EmulatorSession.NO_CONTENT_PREFIX) }
            if (scanned) {
                placeholder?.let { db.games().deleteIds(listOf(it.id)) }
                continue
            }
            if (placeholder != null) continue
            db.games().insert(GameEntity(path = path, title = core.displayName, system = system.id, coreId = core.id, folderId = null))
        }
    }

    /**
     * Drops entries whose extension no longer belongs to any system. Detection rules change between versions
     * (Jazz² first listed every .j2l level, now only the game's Anims.j2a), and those old rows would otherwise
     * sit in the library for ever: a scan only removes entries whose file has vanished.
     */
    suspend fun pruneUnsupportedEntries(): Int = withContext(Dispatchers.IO) {
        val stale = db.games().allOnce().filter { g ->
            if (g.path.startsWith(EmulatorSession.NO_CONTENT_PREFIX)) return@filter false
            val ext = g.path.substringAfterLast('.', "").lowercase()
            // Jazz² levels (.j2l, .j2e) were listed one by one before the scanner settled on the Anims.j2a
            // anchor. The core still names them as loadable extensions, so the extension test below keeps
            // them; they are not games and there are hundreds of them per install.
            if (isJunk(g.path.substringAfterLast('/'))) return@filter true
            if (g.system == SystemId.JAZZ2.id && ext != JAZZ2_ANCHOR_EXT) return@filter true
            ext.isNotEmpty() && ext !in extMap
        }
        if (stale.isNotEmpty()) db.games().deleteIds(stale.map { it.id })
        stale.size
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

    /** Multi-disc/track companions, BIOS packs and file-system litter we should not list as games. */
    private fun isSkippable(f: File, ext: String): Boolean {
        if (isJunk(f.name)) return true
        val name = f.nameWithoutExtension.lowercase()
        if (ext == "bin") return !isLoneDiscBin(f) // normally reached through its .cue
        if (ext == "zip" && (name in mameBiosNames || isArcadeBiosSet(name))) return true
        return false
    }

    /**
     * A .bin is listed on its own only when it is a disc image nobody owns: at least 1 MB and no .cue in the
     * same folder names it (same base name, or referenced from any sibling cue sheet). Whether it really is a
     * PS1 disc is decided afterwards by [resolveSystem].
     */
    private fun isLoneDiscBin(f: File): Boolean {
        if (f.length() < 1_000_000L) return false
        val dir = f.parentFile ?: return false
        val cues = cueSheetsIn(dir)
        if (cues.any { it.first == f.nameWithoutExtension.lowercase() }) return false
        val target = f.name.lowercase()
        return cues.none { target in it.second }
    }

    /** (lower-case base name, lower-case text) of every .cue in [dir]; cached for the current scan. */
    private val cueCache = HashMap<String, List<Pair<String, String>>>()
    private fun cueSheetsIn(dir: File): List<Pair<String, String>> = cueCache.getOrPut(dir.absolutePath) {
        dir.listFiles { c -> c.isFile && c.extension.equals("cue", true) }?.map { cue ->
            cue.nameWithoutExtension.lowercase() to (runCatching { cue.readText(Charsets.ISO_8859_1) }.getOrDefault("").lowercase())
        }.orEmpty()
    }

    /**
     * Copying a game folder through macOS leaves an AppleDouble twin next to every file ("._Anims.j2a"), a few
     * kilobytes of resource fork carrying the real file's extension. They scan as games, sit next to the real
     * entry under a nearly identical name, and handing one to a core is what crashed Jazz² Resurrection.
     */
    private fun isJunk(name: String): Boolean =
        name.startsWith("._") || name.equals(".DS_Store", true) || name.equals("Thumbs.db", true)

    private val mameBiosNames = setOf("neogeo", "pgm", "stvbios", "decocass", "cvs", "playch10", "skns", "konamigx", "nss", "megaplay", "megatech")

    /** BIOS sets of every bundled MAME core (runnable="no" in its DAT), e.g. psarc95.zip for MAME 2010's Namco/PSX games. */
    private fun isArcadeBiosSet(name: String): Boolean {
        val checker = ArcadeRomChecker.get(context)
        return ArcadeCoreRouter.CORE_IDS.any { checker.db(it)[name]?.runnable == false }
    }

    private fun identify(f: File, ext: String, folderId: Long?): GameEntity? {
        if (isJunk(f.name)) return null
        val candidates = extMap[ext] ?: return null
        val system = resolveSystem(f, ext, candidates, lookup = { extMap[it] }, zip = ::zipSystem) ?: return null

        var title = RomInfo.cleanTitle(f.name)
        var autoIcon: String? = null
        if (system == SystemId.ARCADE) {
            arcadeTitles.cleanTitle(f.nameWithoutExtension)?.let { title = it }
        }
        if (system == SystemId.JAZZ2) {
            // One entry per game, never per file. The core opens any file in the game directory, so the whole
            // Source/ folder would otherwise land in the library as a couple of hundred unplayable levels.
            if (ext != JAZZ2_ANCHOR_EXT) return null
            // The anchor file is Anims.j2a inside the game directory (often .../<game>/Source/); the entry is
            // named after that directory so the library shows the game, not the file.
            val dir = f.parentFile
            val gameDir = if (dir?.name.equals("Source", ignoreCase = true)) dir?.parentFile else dir
            title = gameDir?.name?.takeIf { it.isNotBlank() } ?: SystemId.JAZZ2.displayName
        }
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

    companion object {
        private const val DVD_SIZED_BYTES = 2_000_000_000L
        /** The one Jazz² file that stands for a game: Anims.j2a, which every install has exactly one of. */
        private const val JAZZ2_ANCHOR_EXT = "j2a"
        /** Extensions shared by several systems whose detection reads the file (re-checked on every rescan). */
        private val AMBIGUOUS_EXTS = setOf("iso", "img", "bin", "cue", "chd", "pbp", "m3u", "cso")

        /**
         * A system named by the enclosing folders ("PS1", "PSX", "PlayStation 2", "PSP", "GameCube", ...), used when
         * the image itself gives no clue (unreadable, empty or an unusual layout). Nearest folder wins.
         */
        internal fun folderHint(f: File, candidates: List<SystemId>): SystemId? {
            var dir = f.parentFile
            var depth = 0
            while (dir != null && depth < 3) {
                val n = dir.name.lowercase().replace(Regex("[\\s_\\-.]"), "")
                val hit = when {
                    n in setOf("ps1", "psx", "psone", "playstation", "playstation1", "플스1", "플레이스테이션1") -> SystemId.PSX
                    n in setOf("ps2", "playstation2", "플스2", "플레이스테이션2") -> SystemId.PS2
                    n in setOf("psp", "playstationportable") -> SystemId.PSP
                    n in setOf("gc", "ngc", "gamecube", "게임큐브") -> SystemId.GC
                    else -> null
                }
                if (hit != null) return hit.takeIf { it in candidates }
                dir = dir.parentFile; depth++
            }
            return null
        }

        /**
         * Which system a file belongs to when several cores claim its extension. Reads only the file itself (no
         * Android), so the disc heuristics are unit-testable. [lookup] maps an extension to its candidate systems
         * (used for the file an .m3u points at); [zip] peeks inside archives.
         *
         * Disc images: `.iso/.img/.cso` are sniffed with [RomInfo.isoKind] (PS1 `BOOT = cdrom:` / PS2 `BOOT2` /
         * PSP `PSP_GAME` / GC magic); `.cue` sniffs its first FILE; `.pbp` tells PS1 EBOOTs from PSP games;
         * `.chd` can only be classified by its metadata (CD vs DVD vs HDD) plus size; `.m3u` follows its first
         * entry. Ties are broken PS1 first for CD-sized images (< [RomInfo.SMALL_DISC_BYTES]).
         */
        internal fun resolveSystem(
            f: File,
            ext: String,
            candidates: List<SystemId>,
            lookup: (String) -> List<SystemId>?,
            zip: (File, List<SystemId>) -> SystemId? = { _, c -> c.firstOrNull() },
            depth: Int = 0,
        ): SystemId? {
            fun prefer(vararg order: SystemId): SystemId? = order.firstOrNull { it in candidates } ?: candidates.firstOrNull()
            fun only(sys: SystemId): SystemId? = sys.takeIf { it in candidates }
            val size = f.length()
            return when {
                candidates.isEmpty() -> null
                // PS1 executables carry a magic; anything else called .exe (Windows installers…) is not a game.
                ext == "exe" -> only(SystemId.PSX)?.takeIf { RomInfo.isPsxExe(f) }
                // A lone .bin (no owning .cue, see isLoneDiscBin) is listed only when it proves to be a PS1 disc.
                ext == "bin" -> only(SystemId.PSX)?.takeIf { RomInfo.isoKind(f) == RomInfo.IsoKind.PSX }
                candidates.size == 1 -> candidates.first()
                ext == "zip" -> zip(f, candidates)
                ext == "m3u" -> {
                    val first = RomInfo.m3uFirstEntry(f)?.takeIf { it.isFile && depth < 3 }
                        ?: return prefer(SystemId.PSX, SystemId.GC)
                    val fext = first.extension.lowercase()
                    val sub = lookup(fext)?.let { resolveSystem(first, fext, it, lookup, zip, depth + 1) }
                    if (sub != null && sub in candidates) sub else prefer(SystemId.PSX, SystemId.GC)
                }
                ext == "cue" -> when (RomInfo.cueKind(f)) {
                    RomInfo.IsoKind.PS2 -> prefer(SystemId.PS2, SystemId.PSX)
                    // PS1 cue sheets are by far the common case; also the fallback when the .bin is missing.
                    else -> prefer(SystemId.PSX, SystemId.PS2)
                }
                ext == "pbp" -> when (RomInfo.pbpKind(f)) {
                    RomInfo.IsoKind.PSX -> prefer(SystemId.PSX, SystemId.PSP)
                    else -> prefer(SystemId.PSP, SystemId.PSX)
                }
                ext == "chd" -> when (RomInfo.chdKind(f)) {
                    RomInfo.ChdKind.HDD -> prefer(SystemId.ARCADE, SystemId.PSX)
                    RomInfo.ChdKind.CD ->
                        if (size < RomInfo.SMALL_DISC_BYTES) prefer(SystemId.PSX, SystemId.PS2, SystemId.PSP)
                        else prefer(SystemId.PS2, SystemId.PSX, SystemId.PSP)
                    RomInfo.ChdKind.DVD ->
                        if (size > DVD_SIZED_BYTES) prefer(SystemId.PS2, SystemId.PSP) else prefer(SystemId.PSP, SystemId.PS2)
                    RomInfo.ChdKind.UNKNOWN -> folderHint(f, candidates) ?: when {
                        size > DVD_SIZED_BYTES -> prefer(SystemId.PS2, SystemId.PSP)
                        size < RomInfo.SMALL_DISC_BYTES -> prefer(SystemId.PSX, SystemId.PSP, SystemId.PS2)
                        else -> prefer(SystemId.PSP, SystemId.PS2)
                    }
                }
                ext == "iso" || ext == "img" || ext == "cso" -> when (RomInfo.isoKind(f)) {
                    RomInfo.IsoKind.PSX -> prefer(SystemId.PSX, SystemId.PS2, SystemId.PSP)
                    RomInfo.IsoKind.PSP -> prefer(SystemId.PSP, SystemId.PSX)
                    RomInfo.IsoKind.PS2 -> prefer(SystemId.PS2, SystemId.PSX)
                    RomInfo.IsoKind.GC -> prefer(SystemId.GC)
                    RomInfo.IsoKind.UNKNOWN -> folderHint(f, candidates) ?: when {
                        ext == "cso" -> prefer(SystemId.PSP, SystemId.PS2)
                        size > DVD_SIZED_BYTES -> prefer(SystemId.PS2, SystemId.PSP)
                        else -> prefer(SystemId.PSP, SystemId.PS2)
                    }
                }
                ext == "elf" -> prefer(SystemId.PSP)
                else -> candidates.first()
            }
        }
    }
}
