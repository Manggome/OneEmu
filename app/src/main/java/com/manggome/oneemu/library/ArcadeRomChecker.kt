package com.manggome.oneemu.library

import android.content.Context
import android.util.Log
import com.manggome.oneemu.R
import com.manggome.oneemu.library.ArcadeRomCheck.Report
import com.manggome.oneemu.library.ArcadeRomCheck.Resolution
import com.manggome.oneemu.library.ArcadeRomCheck.Status
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android side of [ArcadeRomCheck]: loads one DB per arcade core from the APK on first use
 * (coreassets/<coreId>/romdb.tsv[.gz]), caches one [Resolution] per zip (invalidated when the file's
 * size/mtime changes), and bounds concurrent checks so the library list can request them lazily while
 * scrolling. Also owns the Korean texts for reports.
 */
class ArcadeRomChecker private constructor(private val context: Context) {
    private val dirs = AppDirs(context)
    private val dbs = ConcurrentHashMap<String, ArcadeRomCheck.Db>()

    /** The reference DB of [coreId]; empty when the core ships no romdb (e.g. not built into this APK). */
    fun db(coreId: String): ArcadeRomCheck.Db = dbs[coreId] ?: synchronized(dbs) { dbs.getOrPut(coreId) { loadDb(coreId) } }

    private fun loadDb(coreId: String): ArcadeRomCheck.Db = runCatching {
        val t = System.currentTimeMillis()
        val dir = "coreassets/$coreId"
        val gz = "$dir/$ROMDB_GZ"
        // AGP unpacks *.gz assets at build time and drops the suffix, so the APK usually holds
        // the plain TSV; keep the gzip path for other packaging setups.
        val assetNames = context.assets.list(dir)?.toSet() ?: emptySet()
        when {
            ROMDB_GZ in assetNames -> context.assets.open(gz).use { ArcadeRomCheck.Db.parseGzip(it) }
            ROMDB_PLAIN in assetNames -> context.assets.open("$dir/$ROMDB_PLAIN").use { ArcadeRomCheck.Db.parse(it.buffered(64 * 1024)) }
            else -> { Log.i(TAG, "no romdb for $coreId"); ArcadeRomCheck.Db(emptyMap()) }
        }.also { Log.i(TAG, "romdb[$coreId]: ${it.size} games in ${System.currentTimeMillis() - t} ms") }
    }.getOrElse { e ->
        Log.e(TAG, "romdb load failed for $coreId", e)
        ArcadeRomCheck.Db(emptyMap())
    }

    /** DBs in routing preference order. */
    private fun orderedDbs(): List<Pair<String, ArcadeRomCheck.Db>> = ArcadeCoreRouter.CORE_IDS.map { it to db(it) }

    /** First core (preference order) whose DAT lists [shortName]; name lookup only, no zip IO. */
    fun coreIdFor(shortName: String): String? = ArcadeCoreRouter.CORE_IDS.firstOrNull { db(it)[shortName] != null }

    /** `<system>/<core subdir>/samples` — where the core looks for sample zips. */
    fun samplesDir(coreId: String): File = File(dirs.system, "${ArcadeCoreRouter.systemSubdir(coreId)}/samples")

    /** `<system>/mame2003-plus` — cheat.dat / hiscore.dat / history.dat for MAME 2003-Plus. */
    val datDir: File get() = File(dirs.system, "mame2003-plus")

    private class Cached(val stamp: Long, val resolution: Resolution)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val gate = Semaphore(2)

    /** Folder listing cache for case-insensitive sibling lookups: dir path → (dir mtime, lowercase name → File). */
    private class DirIndex(val stamp: Long, val files: Map<String, File>)
    private val dirIndex = ConcurrentHashMap<String, DirIndex>()

    private fun stampOf(f: File): Long = f.length() * 31 + f.lastModified()

    /** Last result for [path] if the file has not changed since; never touches the zip contents. */
    fun cached(path: String): Resolution? {
        val c = cache[path] ?: return null
        val f = File(path)
        return if (c.stamp == stampOf(f)) c.resolution else null
    }

    /** Runs the check on IO (at most two at a time). */
    suspend fun resolve(path: String, force: Boolean = false): Resolution = withContext(Dispatchers.IO) {
        if (!force) cached(path)?.let { return@withContext it }
        gate.withPermit { resolveNow(File(path)) }
    }

    /** Synchronous check; safe from any background thread (used by EmulatorSession's error path). */
    fun resolveNow(file: File): Resolution {
        val res = runCatching {
            ArcadeRomCheck.resolve(orderedDbs(), file, ::samplesDir, ArcadeCoreRouter::chdSupported, siblingResolver(file.parentFile))
        }.getOrElse { e ->
            Log.w(TAG, "check failed for ${file.name}", e)
            Resolution(null, Report(file.nameWithoutExtension.lowercase(), Status.NOT_IN_DAT, null))
        }
        cache[file.absolutePath] = Cached(stampOf(file), res)
        return res
    }

    fun invalidate(path: String) { cache.remove(path) }

    private fun siblingResolver(dir: File?): (String) -> File {
        if (dir == null) return { File(it) }
        val stamp = dir.lastModified()
        val idx = dirIndex[dir.absolutePath]?.takeIf { it.stamp == stamp } ?: DirIndex(
            stamp,
            dir.listFiles()?.filter { it.isFile }?.associateBy { it.name.lowercase() } ?: emptyMap(),
        ).also { dirIndex[dir.absolutePath] = it }
        return { name -> idx.files[name.lowercase()] ?: File(dir, name) }
    }

    // ---- Korean texts -------------------------------------------------------------------------

    private fun coreName(coreId: String): String = ArcadeCoreRouter.displayName(com.manggome.oneemu.OneEmuApp.get().cores, coreId)

    /** "MAME 2010 (MAME 0.139 롬셋)" */
    fun coreLabel(coreId: String): String = context.getString(R.string.lib_arcade_core_label, coreName(coreId), ArcadeCoreRouter.mameVersion(coreId))

    /** "MAME 2003-Plus, MAME 2010" */
    private fun allCoreNames(): String = ArcadeCoreRouter.CORE_IDS.joinToString(", ") { coreName(it) }

    /** One-line status, e.g. "BIOS 필요: neogeo". */
    fun statusText(res: Resolution): String {
        val r = res.report
        return when (r.status) {
            Status.OK -> context.getString(R.string.lib_arcade_status_ok)
            Status.MISSING_FILES -> context.getString(R.string.lib_arcade_status_missing)
            Status.WRONG_SET -> context.getString(R.string.lib_arcade_status_wrong_set)
            Status.NEEDS_PARENT -> context.getString(R.string.lib_arcade_status_needs_parent, "${r.neededZip ?: r.parentZip}.zip")
            Status.NEEDS_BIOS -> context.getString(R.string.lib_arcade_status_needs_bios, "${r.neededZip ?: r.biosZip}.zip")
            Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_status_needs_samples, "${r.sampleZip}.zip")
            Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_status_chd)
            Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_status_not_in_dat, allCoreNames())
            Status.RENAME_SUGGESTED -> context.getString(R.string.lib_arcade_status_rename, "${r.suggestedName}.zip")
        }
    }

    /** Longer explanation of the status for the detail card / error dialog. */
    fun explanation(res: Resolution): String {
        val r = res.report
        val coreId = res.coreId ?: ArcadeCoreRouter.MAME2003PLUS
        val name = coreName(coreId)
        val version = ArcadeCoreRouter.mameVersion(coreId)
        return when (r.status) {
            Status.OK -> context.getString(R.string.lib_arcade_desc_ok, coreLabel(coreId))
            Status.MISSING_FILES -> context.getString(R.string.lib_arcade_desc_missing, r.missing.size, name, version)
            Status.WRONG_SET -> context.getString(R.string.lib_arcade_desc_wrong_set, r.mismatched.size, version)
            Status.NEEDS_PARENT -> {
                val p = r.neededZip ?: r.parentZip ?: ""
                context.getString(R.string.lib_arcade_desc_needs_parent, db(coreId)[p]?.description ?: p, p)
            }
            Status.NEEDS_BIOS -> context.getString(R.string.lib_arcade_desc_needs_bios, r.neededZip ?: r.biosZip ?: "")
            Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_desc_needs_samples, r.sampleZip ?: "")
            Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_desc_chd, r.disks, name)
            Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_desc_not_in_dat, r.shortName, allCoreNames())
            Status.RENAME_SUGGESTED -> context.getString(
                R.string.lib_arcade_desc_rename, name, r.suggestedName ?: "", r.game?.description ?: "", r.shortName,
            )
        }
    }

    /** "실행 코어: MAME 2010 (MAME 0.139 롬셋)" or null when no core lists the game. */
    fun runCoreText(res: Resolution): String? = res.coreId?.let { context.getString(R.string.lib_arcade_run_core, coreLabel(it)) }

    /**
     * When the game was launched with a core other than the one its DAT belongs to (user override), explains
     * that mismatch; null otherwise.
     */
    fun coreMismatchNote(res: Resolution, runningCoreId: String): String? {
        val target = res.coreId ?: return null
        if (target == runningCoreId || runningCoreId !in ArcadeCoreRouter.CORE_IDS) return null
        return context.getString(R.string.lib_arcade_core_mismatch, coreName(runningCoreId), coreName(target))
    }

    /** Facts about companion zips ("neogeo.zip BIOS가 같은 폴더에 없습니다" …), one per line; empty when nothing to say. */
    fun companionNotes(res: Resolution): List<String> = buildList {
        val r = res.report
        r.biosZip?.let { b ->
            add(if (r.biosPresent) context.getString(R.string.lib_arcade_in_folder_ok, b) else context.getString(R.string.lib_arcade_bios_missing_folder, b))
        }
        r.parentZip?.takeIf { it != r.biosZip }?.let { p ->
            add(if (r.parentPresent) context.getString(R.string.lib_arcade_in_folder_ok, p) else context.getString(R.string.lib_arcade_parent_missing_folder, p))
        }
        if (r.disks > 0 && r.status != Status.CHD_UNSUPPORTED) {
            add(context.getString(R.string.lib_arcade_chd_note, r.disks, r.game?.name ?: r.shortName))
        }
    }

    /** Compact multi-line summary for the emulator error dialog (status, explanation, companion notes, first files). */
    fun summary(res: Resolution, maxFiles: Int = 6): String = buildString {
        val r = res.report
        append(statusText(res))
        append(" — ").append(explanation(res))
        for (n in companionNotes(res)) append('\n').append(n)
        if (r.issues.isNotEmpty()) {
            append('\n')
            r.issues.take(maxFiles).forEach { i ->
                append("\n• ").append(i.name)
                append(if (i.missing) " (${context.getString(R.string.lib_arcade_file_missing)}, ${i.owner}.zip)" else " (CRC ${i.foundCrc} ≠ ${i.expectedCrc})")
            }
            if (r.issues.size > maxFiles) append("\n… +").append(r.issues.size - maxFiles)
        }
    }

    /** Lines of the core log that MAME prints while loading ROMs (NOT FOUND / WRONG CHECKSUMS / …). */
    fun mameLoadLines(log: String): List<String> =
        log.lineSequence().map { it.trim() }.filter { MAME_LOAD_LINE.containsMatchIn(it) }
            .map { it.removePrefix("[MAME 2003+]").removePrefix("[MAME 2010]").trim() }.toList()

    companion object {
        private const val TAG = "ArcadeRomChecker"
        private const val ROMDB_GZ = "romdb.tsv.gz"
        private const val ROMDB_PLAIN = "romdb.tsv"
        private val MAME_LOAD_LINE = Regex(
            "NOT FOUND|NO GOOD DUMP|WRONG CHECKSUMS|WRONG LENGTH|ROM NEEDS REDUMP|Required files are missing|Warnings flagged|EXPECTED:|FOUND:|CRC\\(",
        )

        @Volatile private var instance: ArcadeRomChecker? = null
        fun get(context: Context): ArcadeRomChecker = instance ?: synchronized(this) {
            instance ?: ArcadeRomChecker(context.applicationContext).also { instance = it }
        }
    }
}
