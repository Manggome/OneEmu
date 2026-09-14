package com.manggome.oneemu.library

import android.content.Context
import android.util.Log
import com.manggome.oneemu.R
import com.manggome.oneemu.library.ArcadeRomCheck.Report
import com.manggome.oneemu.library.ArcadeRomCheck.Status
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android side of [ArcadeRomCheck]: loads the DB from the APK once, caches one [Report] per zip
 * (invalidated when the file's size/mtime changes), and bounds concurrent checks so the library
 * list can request them lazily while scrolling.
 */
class ArcadeRomChecker private constructor(private val context: Context) {
    private val dirs = AppDirs(context)

    val db: ArcadeRomCheck.Db by lazy {
        runCatching {
            val t = System.currentTimeMillis()
            // AGP unpacks *.gz assets at build time and drops the suffix, so the APK usually holds
            // the plain TSV; keep the gzip path for other packaging setups.
            val plain = ASSET.removeSuffix(".gz")
            val assetNames = context.assets.list(plain.substringBeforeLast('/'))?.toSet() ?: emptySet()
            if (plain.substringAfterLast('/') in assetNames && ASSET.substringAfterLast('/') !in assetNames) {
                context.assets.open(plain).use { ArcadeRomCheck.Db.parse(it.buffered(64 * 1024)) }
            } else {
                context.assets.open(ASSET).use { ArcadeRomCheck.Db.parseGzip(it) }
            }
                .also { Log.i(TAG, "romdb: ${it.size} games in ${System.currentTimeMillis() - t} ms") }
        }.getOrElse { e ->
            Log.e(TAG, "romdb load failed", e)
            ArcadeRomCheck.Db(emptyMap())
        }
    }

    /** `<system>/mame2003-plus/samples` — where MAME 2003-Plus looks for sample zips. */
    val samplesDir: File get() = File(dirs.system, "mame2003-plus/samples")

    /** `<system>/mame2003-plus` — cheat.dat / hiscore.dat / history.dat. */
    val datDir: File get() = File(dirs.system, "mame2003-plus")

    private class Cached(val stamp: Long, val report: Report)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val gate = Semaphore(2)

    /** Folder listing cache for case-insensitive sibling lookups: dir path → (dir mtime, lowercase name → File). */
    private class DirIndex(val stamp: Long, val files: Map<String, File>)
    private val dirIndex = ConcurrentHashMap<String, DirIndex>()

    private fun stampOf(f: File): Long = f.length() * 31 + f.lastModified()

    /** Last result for [path] if the file has not changed since; never touches the zip contents. */
    fun cached(path: String): Report? {
        val c = cache[path] ?: return null
        val f = File(path)
        return if (c.stamp == stampOf(f)) c.report else null
    }

    /** Runs the check on IO (at most two at a time). */
    suspend fun check(path: String, force: Boolean = false): Report = withContext(Dispatchers.IO) {
        if (!force) cached(path)?.let { return@withContext it }
        gate.withPermit { checkNow(File(path)) }
    }

    /** Synchronous check; safe from any background thread (used by EmulatorSession's error path). */
    fun checkNow(file: File): Report {
        val report = runCatching { ArcadeRomCheck.check(db, file, samplesDir, siblingResolver(file.parentFile)) }
            .getOrElse { e ->
                Log.w(TAG, "check failed for ${file.name}", e)
                Report(file.nameWithoutExtension.lowercase(), Status.NOT_IN_DAT, null)
            }
        cache[file.absolutePath] = Cached(stampOf(file), report)
        return report
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

    /** One-line status, e.g. "BIOS 필요: neogeo". */
    fun statusText(r: Report): String = when (r.status) {
        Status.OK -> context.getString(R.string.lib_arcade_status_ok)
        Status.MISSING_FILES -> context.getString(R.string.lib_arcade_status_missing)
        Status.WRONG_SET -> context.getString(R.string.lib_arcade_status_wrong_set)
        Status.NEEDS_PARENT -> context.getString(R.string.lib_arcade_status_needs_parent, "${r.neededZip ?: r.parentZip}.zip")
        Status.NEEDS_BIOS -> context.getString(R.string.lib_arcade_status_needs_bios, "${r.neededZip ?: r.biosZip}.zip")
        Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_status_needs_samples, "${r.sampleZip}.zip")
        Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_status_chd)
        Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_status_not_in_dat)
    }

    /** Longer explanation of the status for the detail card / error dialog. */
    fun explanation(r: Report): String = when (r.status) {
        Status.OK -> context.getString(R.string.lib_arcade_desc_ok)
        Status.MISSING_FILES -> context.getString(R.string.lib_arcade_desc_missing, r.missing.size)
        Status.WRONG_SET -> context.getString(R.string.lib_arcade_desc_wrong_set, r.mismatched.size)
        Status.NEEDS_PARENT -> {
            val p = r.neededZip ?: r.parentZip ?: ""
            context.getString(R.string.lib_arcade_desc_needs_parent, db[p]?.description ?: p, p)
        }
        Status.NEEDS_BIOS -> context.getString(R.string.lib_arcade_desc_needs_bios, r.neededZip ?: r.biosZip ?: "")
        Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_desc_needs_samples, r.sampleZip ?: "")
        Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_desc_chd, r.disks)
        Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_desc_not_in_dat, r.shortName)
    }

    /** Facts about companion zips ("neogeo.zip BIOS가 같은 폴더에 없습니다" …), one per line; empty when nothing to say. */
    fun companionNotes(r: Report): List<String> = buildList {
        r.biosZip?.let { b ->
            add(if (r.biosPresent) context.getString(R.string.lib_arcade_in_folder_ok, b) else context.getString(R.string.lib_arcade_bios_missing_folder, b))
        }
        r.parentZip?.takeIf { it != r.biosZip }?.let { p ->
            add(if (r.parentPresent) context.getString(R.string.lib_arcade_in_folder_ok, p) else context.getString(R.string.lib_arcade_parent_missing_folder, p))
        }
    }

    /** Compact multi-line summary for the emulator error dialog (status, explanation, companion notes, first files). */
    fun summary(r: Report, maxFiles: Int = 6): String = buildString {
        append(statusText(r))
        append(" — ").append(explanation(r))
        for (n in companionNotes(r)) append('\n').append(n)
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
        log.lineSequence().map { it.trim() }.filter { MAME_LOAD_LINE.containsMatchIn(it) }.map { it.removePrefix("[MAME 2003+]").trim() }.toList()

    companion object {
        private const val TAG = "ArcadeRomChecker"
        const val ASSET = "coreassets/mame2003plus/romdb.tsv.gz"
        private val MAME_LOAD_LINE = Regex(
            "NOT FOUND|NO GOOD DUMP|WRONG CHECKSUMS|WRONG LENGTH|ROM NEEDS REDUMP|Required files are missing|Warnings flagged|EXPECTED:|FOUND:|CRC\\(",
        )

        @Volatile private var instance: ArcadeRomChecker? = null
        fun get(context: Context): ArcadeRomChecker = instance ?: synchronized(this) {
            instance ?: ArcadeRomChecker(context.applicationContext).also { instance = it }
        }
    }
}
