package com.manggome.oneemu.library

import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import java.io.File

/**
 * Decides which arcade core runs an arcade zip. The rule itself is [ArcadeRomCheck.routeByName]; used by the
 * library's launch check and by EmulatorActivity so both agree:
 *  1. Neo Geo and CPS1/2/3 games that FBNeo's DAT lists as working run on FBNeo ([ArcadeRomCheck.FBNEO_BOARDS]) —
 *     it emulates those boards better than the old MAME cores (Metal Slug renders wrongly in MAME 2003-Plus).
 *  2. Everything else goes to the first MAME core (in [CORE_IDS] order) whose DAT lists the zip's short name —
 *     unless that DAT rates the game's driver "preliminary" (or the driver is known to crash on arm64,
 *     [ArcadeRomCheck.UNSTABLE_DRIVERS]) and a later core rates it no worse, in which case the later core takes it
 *     (Tecmo World Cup '98 on ST-V: MAME 2003-Plus crashes, MAME 2010 runs).
 *  3. A game only FBNeo's DAT lists runs on FBNeo.
 * The MAME cores stay the fallback for FBNeo's boards: when the zip turns out to be a MAME-only set (e.g. an old
 * MAME 0.78 Neo Geo dump FBNeo cannot load), [ArcadeRomChecker]'s zip check says so and [route] follows it.
 * A per-game core choice (GameEntity.coreId, "코어 선택") always wins over all of this.
 *
 * Only name lookups happen here unless [route] is asked to inspect the zip; the per-file diagnosis lives in
 * [ArcadeRomChecker].
 */
object ArcadeCoreRouter {
    const val MAME2003PLUS = "mame2003plus"
    const val MAME2010 = "mame2010"
    /** Current libretro MAME (pinned commit = upstream 0.289); a `distribution: download` core, installed on demand. */
    const val MAME = "mame"
    /** FinalBurn Neo (libretro/FBNeo); a `distribution: download` core with its own DAT, first choice for Neo Geo / CPS. */
    const val FBNEO = "fbneo"

    /**
     * Arcade cores whose DBs the router consults: the MAME cores in preference order (a game present in an earlier
     * DAT runs there), then FBNeo, which [ArcadeRomCheck.routeByName] treats separately (first for its boards, else
     * only when no MAME DAT lists the game). MAME 2010, current MAME and FBNeo are downloadable cores: routing
     * ignores whether they are installed ([Route.neededCoreId] then asks the user to download).
     */
    val CORE_IDS: List<String> = listOf(MAME2003PLUS, MAME2010, MAME, FBNEO)

    /** MAME version whose romset the core expects; "" for FBNeo (its own DAT, see [romsetName]). */
    fun mameVersion(coreId: String): String = when (coreId) {
        MAME2003PLUS -> "0.78"
        MAME2010 -> "0.139"
        MAME -> "0.289"
        else -> ""
    }

    /** Which romset the core expects, for texts like "MAME 2010 (MAME 0.139 롬셋)": "MAME 0.139", "FBNeo". */
    fun romsetName(coreId: String): String = when (coreId) {
        FBNEO -> "FBNeo"
        else -> mameVersion(coreId).takeIf { it.isNotEmpty() }?.let { "MAME $it" } ?: coreId
    }

    /** Sub-folder the core uses under the libretro system dir (samples/, artwork/, *.dat). */
    fun systemSubdir(coreId: String): String = when (coreId) {
        MAME2003PLUS -> "mame2003-plus"
        MAME2010 -> "mame2010"
        MAME -> "mame"
        FBNEO -> "fbneo"
        else -> coreId
    }

    /** MAME 2003-Plus is built without CHD support; MAME 0.139, current MAME and FBNeo (INCLUDE_CHD_SUPPORT) read CHDs. */
    fun chdSupported(coreId: String): Boolean = coreId == MAME2010 || coreId == MAME || coreId == FBNEO

    /** Display name from core.json when known, else a sensible default (the DB can name a core the APK lacks). */
    fun displayName(cores: CoreRegistry, coreId: String): String = cores.core(coreId)?.displayName ?: when (coreId) {
        MAME2003PLUS -> "MAME 2003-Plus"
        MAME2010 -> "MAME 2010"
        MAME -> "MAME"
        FBNEO -> "FBNeo"
        else -> coreId
    }

    /** The routed core exists in core.json but is a downloadable core that is not installed yet. */
    fun needsDownload(cores: CoreRegistry, coreId: String?): CoreInfo? =
        coreId?.let { cores.core(it) }?.takeIf { it.isDownloadable && !cores.isAvailable(it) }

    /**
     * @param core the core to launch with, or null when nothing usable exists.
     * @param resolvedCoreId the core the DATs route the game to (null = not in any DAT / not arcade / user override).
     * @param neededCoreId set when the DATs say [resolvedCoreId] but that core's library is not present (not in this
     *   build, or a downloadable core that is not installed yet — see [needsDownload]).
     * @param reason why the game was taken away from an earlier core ([skippedCoreId]); NONE for the plain first match.
     * @param driverStatus the game's `<driver status>` in [resolvedCoreId]'s DAT.
     * @param knownUnstable [resolvedCoreId] is itself known to crash on this game's driver ([ArcadeRomCheck.UNSTABLE_DRIVERS])
     *   and no bundled core does better — the library asks before launching.
     */
    data class Route(
        val core: CoreInfo?,
        val resolvedCoreId: String?,
        val neededCoreId: String?,
        val reason: ArcadeRomCheck.RouteReason = ArcadeRomCheck.RouteReason.NONE,
        val skippedCoreId: String? = null,
        val driverStatus: ArcadeRomCheck.DriverStatus = ArcadeRomCheck.DriverStatus.UNKNOWN,
        val knownUnstable: Boolean = false,
    )

    /**
     * Routing for [game]. An explicit, available `coreId` override wins; otherwise arcade zips go to the core whose
     * DAT lists them, and every other case falls back to the system default.
     *
     * A Neo Geo / CPS game routed to FBNeo by name falls back to the MAME core when the zip check found that the
     * zip is a MAME-only set ([ArcadeRomCheck.RouteReason.FBNEO_SET_MISMATCH]). That needs the zip's file list: the
     * cached check result is used when there is one; with [inspectZip] (launch paths) a missing one is computed now
     * (reads the zip directories of the game and its BIOS/parent — only for these games).
     */
    fun route(game: GameEntity, app: OneEmuApp = OneEmuApp.get(), inspectZip: Boolean = false): Route {
        val cores = app.cores
        val system = SystemId.fromId(game.system)
        val override = game.coreId?.let { cores.core(it) }
        if (override != null && cores.isAvailable(override)) return Route(override, null, null)
        val default = system?.let { cores.defaultCoreFor(it) } ?: override
        if (system != SystemId.ARCADE) return Route(default, null, null)

        val checker = ArcadeRomChecker.get(app)
        val shortName = File(game.path).nameWithoutExtension
        val named = checker.routeByName(shortName) ?: return Route(default, null, null)
        val rt = named.fallback?.let { fb ->
            val res = checker.cached(game.path) ?: if (inspectZip) checker.resolveNow(File(game.path)) else null
            val to = res?.coreId
            if (res?.reason == ArcadeRomCheck.RouteReason.FBNEO_SET_MISMATCH && to != null && to != named.coreId &&
                res.status != ArcadeRomCheck.Status.RENAME_SUGGESTED) {
                // Usually the MAME pick itself; another MAME core when only that one takes the zip (see resolve).
                val g = if (to == fb.coreId) fb.game else res.report.game
                g?.let { ArcadeRomCheck.Routing(to, it, res.reason, named.coreId) }
            } else null
        } ?: named
        val core = cores.core(rt.coreId)
        val status = rt.game.driverStatus
        return if (core != null && cores.isAvailable(core)) Route(core, rt.coreId, null, rt.reason, rt.skippedCoreId, status, rt.knownUnstable)
        else Route(null, rt.coreId, rt.coreId, rt.reason, rt.skippedCoreId, status, rt.knownUnstable)
    }

    /** Core to launch [game] with, or null when the required core is not bundled (see [route] for the reason). */
    fun pick(game: GameEntity, app: OneEmuApp = OneEmuApp.get()): CoreInfo? = route(game, app).core
}
