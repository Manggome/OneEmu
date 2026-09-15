package com.manggome.oneemu.library

import android.content.Context
import android.util.Log
import com.manggome.oneemu.R
import com.manggome.oneemu.library.ArcadeRomCheck.DriverStatus
import com.manggome.oneemu.library.ArcadeRomCheck.Report
import com.manggome.oneemu.library.ArcadeRomCheck.RouteReason
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

    /** Core for [shortName] per [ArcadeRomCheck.routeByName] (status-aware, preference order); name lookup only, no zip IO. */
    fun routeByName(shortName: String): ArcadeRomCheck.Routing? = ArcadeRomCheck.routeByName(orderedDbs(), shortName)

    /** Core id [routeByName] picks for [shortName], or null when no DAT lists it. */
    fun coreIdFor(shortName: String): String? = routeByName(shortName)?.coreId

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

    /** "MAME 2010 (MAME 0.139 롬셋)"; current MAME: "MAME (최신 MAME 롬셋)". */
    fun coreLabel(coreId: String): String =
        if (coreId == ArcadeCoreRouter.MAME) context.getString(R.string.lib_arcade_core_label_latest, coreName(coreId))
        else context.getString(R.string.lib_arcade_core_label, coreName(coreId), ArcadeCoreRouter.mameVersion(coreId))

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
            Status.NEEDS_DEVICE -> context.getString(R.string.lib_arcade_status_needs_device, "${r.neededZip ?: r.deviceZips.firstOrNull()}.zip")
            Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_status_needs_samples, "${r.sampleZip}.zip")
            Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_status_chd)
            Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_status_not_in_dat, allCoreNames())
            Status.RENAME_SUGGESTED -> context.getString(R.string.lib_arcade_status_rename, "${r.suggestedName}.zip")
            Status.UNVERIFIED -> context.getString(R.string.lib_arcade_status_unverified)
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
            Status.NEEDS_DEVICE -> context.getString(R.string.lib_arcade_desc_needs_device, r.neededZip ?: r.deviceZips.firstOrNull() ?: "")
            Status.NEEDS_SAMPLES -> context.getString(R.string.lib_arcade_desc_needs_samples, r.sampleZip ?: "")
            Status.CHD_UNSUPPORTED -> context.getString(R.string.lib_arcade_desc_chd, r.disks, name)
            Status.NOT_IN_DAT -> context.getString(R.string.lib_arcade_desc_not_in_dat, r.shortName, allCoreNames())
            Status.RENAME_SUGGESTED -> context.getString(
                R.string.lib_arcade_desc_rename, name, r.suggestedName ?: "", r.game?.description ?: "", r.shortName,
            )
            Status.UNVERIFIED -> context.getString(R.string.lib_arcade_desc_unverified, coreLabel(coreId))
        }
    }

    /** "실행 코어: MAME 2010 (MAME 0.139 롬셋)" or null when no core lists the game. */
    fun runCoreText(res: Resolution): String? = res.coreId?.let { context.getString(R.string.lib_arcade_run_core, coreLabel(it)) }

    /** "MAME 2003-Plus에서는 미완성 드라이버라 MAME 2010으로 실행" — why the game left the preferred core; null for a plain first match. */
    fun routeReasonText(res: Resolution): String? = routeReasonText(res.reason, res.skippedCoreId, res.coreId)

    fun routeReasonText(route: ArcadeCoreRouter.Route): String? = routeReasonText(route.reason, route.skippedCoreId, route.resolvedCoreId)

    private fun routeReasonText(reason: RouteReason, skippedCoreId: String?, chosenCoreId: String?): String? {
        if (skippedCoreId == null || chosenCoreId == null) return null
        return when (reason) {
            RouteReason.NONE -> null
            RouteReason.PREFERRED_PRELIMINARY -> context.getString(R.string.lib_arcade_route_preliminary, coreName(skippedCoreId), coreName(chosenCoreId))
            RouteReason.PREFERRED_UNSTABLE -> context.getString(R.string.lib_arcade_route_unstable, coreName(skippedCoreId), coreName(chosenCoreId))
        }
    }

    /** "에뮬레이션 상태: 양호 / 불완전(그래픽·사운드 문제 가능) / 미완성(실행 불안정)"; null when the DAT has no rating. */
    fun driverStatusText(status: DriverStatus): String? = when (status) {
        DriverStatus.GOOD -> context.getString(R.string.lib_arcade_driver_good)
        DriverStatus.IMPERFECT -> context.getString(R.string.lib_arcade_driver_imperfect)
        DriverStatus.PRELIMINARY -> context.getString(R.string.lib_arcade_driver_preliminary)
        DriverStatus.UNKNOWN -> null
    }?.let { context.getString(R.string.lib_arcade_driver_status, it) }

    fun driverStatusText(res: Resolution): String? = driverStatusText(res.driverStatus)

    /** Status of [res]'s game in [coreId]'s own DAT (the running core may differ from the routed one). */
    fun driverStatusIn(res: Resolution, coreId: String): DriverStatus {
        val name = res.report.game?.name ?: return DriverStatus.UNKNOWN
        return db(coreId)[name]?.driverStatus ?: DriverStatus.UNKNOWN
    }

    /**
     * "이 게임은 선택한 코어에서 미완성 상태입니다. 다른 코어를 선택해 보세요" when the game is rated preliminary in
     * [runningCoreId] (default: the routed core) — for the launch error dialog; null otherwise.
     */
    fun preliminaryNote(res: Resolution, runningCoreId: String? = res.coreId): String? {
        val status = if (runningCoreId == null || runningCoreId == res.coreId) res.driverStatus else driverStatusIn(res, runningCoreId)
        return if (status == DriverStatus.PRELIMINARY) context.getString(R.string.lib_arcade_preliminary_launch) else null
    }

    /** Korean board name for a driver source file ("stv" → "세가 ST-V"); null when the board has no name here. */
    private fun boardName(game: ArcadeRomCheck.Game): String? = when (game.driverName) {
        "stv", "stvinit", "stvhacks" -> context.getString(R.string.lib_arcade_board_stv)
        else -> null
    }

    /**
     * "이 게임의 기판(세가 ST-V)은 현재 포함된 MAME 코어에서 강제 종료됩니다. 실행은 가능하지만 앱이 꺼질 수 있습니다." —
     * the process-crash warning for a game whose driver is in [ArcadeRomCheck.UNSTABLE_DRIVERS] for the core it
     * will run with ([coreId], default: the routed core). Null when that core is not known to crash on it.
     */
    fun unstableNote(game: ArcadeRomCheck.Game?, coreId: String?): String? {
        if (game == null || coreId == null || !ArcadeRomCheck.isUnstableDriver(coreId, game)) return null
        val board = boardName(game)
        return if (board != null) context.getString(R.string.lib_arcade_unstable_note_board, board)
        else context.getString(R.string.lib_arcade_unstable_note_generic)
    }

    /** [unstableNote] for a resolution (its routed core), i.e. non-null exactly when [Resolution.knownUnstable]. */
    fun unstableNote(res: Resolution): String? = unstableNote(res.report.game, res.coreId)

    /** [unstableNote] for the core [shortName] is about to run with (routing result or a user override). */
    fun unstableNote(shortName: String, runningCoreId: String): String? = unstableNote(db(runningCoreId)[shortName], runningCoreId)

    /**
     * When the game was launched with a core other than the one its DAT belongs to (user override), explains
     * that mismatch; null otherwise.
     */
    fun coreMismatchNote(res: Resolution, runningCoreId: String): String? {
        val target = res.coreId ?: return null
        if (target == runningCoreId || runningCoreId !in ArcadeCoreRouter.CORE_IDS) return null
        return context.getString(R.string.lib_arcade_core_mismatch, coreName(runningCoreId), coreName(target))
    }

    /**
     * Facts about companion zips ("neogeo.zip BIOS가 같은 폴더에 없습니다", "segabill.zip 장치 롬이 같은 폴더에 없습니다",
     * "stvbios.zip에 epr-23603.ic8 없음 — 이 코어(MAME 0.289) 기준 BIOS 세트가 필요합니다" …), one per line; empty when
     * nothing to say. An absent device zip is only mentioned when the game actually lacks that device's files (MAME
     * also finds them inside the game/parent/BIOS zips).
     */
    fun companionNotes(res: Resolution): List<String> = buildList {
        val r = res.report
        val coreId = res.coreId ?: ArcadeCoreRouter.MAME2003PLUS
        r.biosZip?.let { b ->
            add(if (r.biosPresent) context.getString(R.string.lib_arcade_in_folder_ok, b) else context.getString(R.string.lib_arcade_bios_missing_folder, b))
        }
        r.outdatedBiosFiles.takeIf { it.isNotEmpty() }?.let { files ->
            add(context.getString(R.string.lib_arcade_bios_outdated, r.biosZip ?: "", files.joinToString(", ") { it.name }, ArcadeCoreRouter.mameVersion(coreId)))
        }
        r.parentZip?.takeIf { it != r.biosZip }?.let { p ->
            add(if (r.parentPresent) context.getString(R.string.lib_arcade_in_folder_ok, p) else context.getString(R.string.lib_arcade_parent_missing_folder, p))
        }
        for (d in r.deviceZips) {
            when {
                r.zipPresent(d) -> add(context.getString(R.string.lib_arcade_device_in_folder_ok, d))
                r.missing.any { it.owner == d } -> add(context.getString(R.string.lib_arcade_device_missing_folder, d))
            }
        }
        if (r.disks > 0 && r.status != Status.CHD_UNSUPPORTED) {
            add(context.getString(R.string.lib_arcade_chd_note, r.disks, r.game?.name ?: r.shortName))
        }
    }

    /**
     * "최신 MAME 코어는 0.289 롬셋 기준입니다. BIOS/장치 zip도 같은 버전이어야 합니다." — for file/zip problems of a game
     * routed to the current MAME core (its DAT moves with every release, unlike the frozen 0.78/0.139 cores); null otherwise.
     */
    fun versionNote(res: Resolution): String? {
        if (res.coreId != ArcadeCoreRouter.MAME) return null
        return when (res.status) {
            Status.MISSING_FILES, Status.WRONG_SET, Status.NEEDS_PARENT, Status.NEEDS_BIOS, Status.NEEDS_DEVICE ->
                context.getString(R.string.lib_arcade_mame_version_note, ArcadeCoreRouter.mameVersion(ArcadeCoreRouter.MAME))
            else -> null
        }
    }

    /** "BIOS" / "부모 롬" / "장치 롬" for an owner zip of [r]; null for the game's own zip. */
    fun ownerKindLabel(r: Report, owner: String): String? = when (r.ownerKind(owner)) {
        ArcadeRomCheck.OwnerKind.GAME -> null
        ArcadeRomCheck.OwnerKind.BIOS -> context.getString(R.string.lib_arcade_kind_bios)
        ArcadeRomCheck.OwnerKind.PARENT -> context.getString(R.string.lib_arcade_kind_parent)
        ArcadeRomCheck.OwnerKind.DEVICE -> context.getString(R.string.lib_arcade_kind_device)
    }

    /** True when the owner zip of some issues is not in the folder at all (the game's own zip is never "absent" here). */
    fun ownerAbsent(r: Report, owner: String): Boolean = r.ownerKind(owner) != ArcadeRomCheck.OwnerKind.GAME && !r.zipPresent(owner)

    /** "없음" for a missing file, "CRC 1234abcd ≠ 5678ef01" for a wrong one. */
    fun issueText(i: ArcadeRomCheck.FileIssue): String =
        if (i.missing) context.getString(R.string.lib_arcade_file_missing) else "${context.getString(R.string.lib_arcade_file_found, i.foundCrc ?: "?")} ≠ ${i.expectedCrc}"

    /**
     * The problem files grouped by owner zip, one line each: "stvbios.zip: epr-23603.ic8" (the zip is there but lacks
     * / has wrong versions of these files) or "segabill.zip: 없음 (장치 롬) · epr-18022.ic2" (the zip itself is absent).
     * At most [maxFiles] file names in total, then "… +n".
     */
    fun ownerLines(r: Report, maxFiles: Int = 6): List<String> {
        var budget = maxFiles
        return r.issuesByOwner.mapNotNull { (owner, list) ->
            if (budget <= 0) return@mapNotNull null
            val shown = list.take(budget)
            budget -= shown.size
            val files = buildString {
                append(shown.joinToString(", ") { i -> if (i.missing) i.name else "${i.name} (${issueText(i)})" })
                if (shown.size < list.size) append(' ').append(context.getString(R.string.lib_arcade_owner_more, list.size - shown.size))
            }
            if (ownerAbsent(r, owner)) "${context.getString(R.string.lib_arcade_owner_absent, owner, ownerKindLabel(r, owner) ?: "")} · $files"
            else context.getString(R.string.lib_arcade_owner_files, owner, files)
        }
    }

    /** Compact multi-line summary for the emulator error dialog (status, explanation, companion notes, files by owner zip). */
    fun summary(res: Resolution, maxFiles: Int = 6): String = buildString {
        val r = res.report
        append(statusText(res))
        append(" — ").append(explanation(res))
        preliminaryNote(res)?.let { append('\n').append(it) }
        unstableNote(res)?.let { append('\n').append(it) }
        for (n in companionNotes(res)) append('\n').append(n)
        versionNote(res)?.let { append('\n').append(it) }
        if (r.issues.isNotEmpty()) {
            append('\n')
            val lines = ownerLines(r, maxFiles)
            for (line in lines) append("\n• ").append(line)
            if (lines.size < r.issuesByOwner.size) append('\n').append(context.getString(R.string.lib_arcade_owner_more, r.issuesByOwner.size - lines.size))
        }
    }

    /** Lines of the core log that MAME prints while loading ROMs (NOT FOUND / WRONG CHECKSUMS / …). */
    fun mameLoadLines(log: String): List<String> =
        log.lineSequence().map { it.trim() }.filter { MAME_LOAD_LINE.containsMatchIn(it) }
            .map { it.removePrefix("[MAME 2003+]").removePrefix("[MAME 2010]").removePrefix("[MAME]").trim() }.toList()

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
