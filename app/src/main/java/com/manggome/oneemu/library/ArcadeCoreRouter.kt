package com.manggome.oneemu.library

import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import java.io.File

/**
 * Decides which bundled MAME core runs an arcade zip: the first core (in [CORE_IDS] order) whose DAT lists
 * the zip's short name — unless that DAT rates the game's driver "preliminary" (or the driver is known to
 * crash on arm64, [ArcadeRomCheck.UNSTABLE_DRIVERS]) and a later core rates it no worse, in which case the
 * later core takes it (Tecmo World Cup '98 on ST-V: MAME 2003-Plus crashes, MAME 2010 runs). The rule itself
 * is [ArcadeRomCheck.routeByName]; used by the library's launch check and by EmulatorActivity so both agree.
 *
 * Only name lookups happen here (no zip IO); the per-file diagnosis lives in [ArcadeRomChecker].
 */
object ArcadeCoreRouter {
    const val MAME2003PLUS = "mame2003plus"
    const val MAME2010 = "mame2010"
    /** Current libretro MAME (pinned commit = upstream 0.289); a `distribution: download` core, installed on demand. */
    const val MAME = "mame"

    /**
     * Arcade cores in preference order — a game present in an earlier DAT runs there. MAME 2010 and current
     * MAME are downloadable cores: routing ignores whether they are installed ([Route.neededCoreId] then asks
     * the user to download).
     */
    val CORE_IDS: List<String> = listOf(MAME2003PLUS, MAME2010, MAME)

    /** MAME version whose romset the core expects (shown next to the core name). */
    fun mameVersion(coreId: String): String = when (coreId) {
        MAME2003PLUS -> "0.78"
        MAME2010 -> "0.139"
        MAME -> "0.289"
        else -> ""
    }

    /** Sub-folder the core uses under the libretro system dir (samples/, artwork/, *.dat). */
    fun systemSubdir(coreId: String): String = when (coreId) {
        MAME2003PLUS -> "mame2003-plus"
        MAME2010 -> "mame2010"
        MAME -> "mame"
        else -> coreId
    }

    /** MAME 2003-Plus is built without CHD support; MAME 0.139 and current MAME read CHDs from <romdir>/<game>/. */
    fun chdSupported(coreId: String): Boolean = coreId == MAME2010 || coreId == MAME

    /** Display name from core.json when known, else a sensible default (the DB can name a core the APK lacks). */
    fun displayName(cores: CoreRegistry, coreId: String): String = cores.core(coreId)?.displayName ?: when (coreId) {
        MAME2003PLUS -> "MAME 2003-Plus"
        MAME2010 -> "MAME 2010"
        MAME -> "MAME"
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
     */
    fun route(game: GameEntity, app: OneEmuApp = OneEmuApp.get()): Route {
        val cores = app.cores
        val system = SystemId.fromId(game.system)
        val override = game.coreId?.let { cores.core(it) }
        if (override != null && cores.isAvailable(override)) return Route(override, null, null)
        val default = system?.let { cores.defaultCoreFor(it) } ?: override
        if (system != SystemId.ARCADE) return Route(default, null, null)

        val checker = ArcadeRomChecker.get(app)
        val shortName = File(game.path).nameWithoutExtension
        val rt = checker.routeByName(shortName) ?: return Route(default, null, null)
        val core = cores.core(rt.coreId)
        val status = rt.game.driverStatus
        return if (core != null && cores.isAvailable(core)) Route(core, rt.coreId, null, rt.reason, rt.skippedCoreId, status, rt.knownUnstable)
        else Route(null, rt.coreId, rt.coreId, rt.reason, rt.skippedCoreId, status, rt.knownUnstable)
    }

    /** Core to launch [game] with, or null when the required core is not bundled (see [route] for the reason). */
    fun pick(game: GameEntity, app: OneEmuApp = OneEmuApp.get()): CoreInfo? = route(game, app).core
}
