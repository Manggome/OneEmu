package com.manggome.oneemu.emu

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.ArcadeRomCheck
import com.manggome.oneemu.emu.input.WiiController
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.library.RomInfo
import com.manggome.oneemu.library.ArcadeRomChecker
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * One running game. Wraps [NativeBridge] with everything the UI needs: path resolution,
 * BIOS checks, zip extraction for need_fullpath cores, save-state slots with thumbnails,
 * core option overrides, and libretro button constants.
 *
 * Lifecycle: [load] → (surface attach via [setSurface]) → [resume]/[pause] → [close].
 */
class EmulatorSession(val game: GameEntity, val core: CoreInfo, private val hwApiOverride: String? = null) : NativeBridge.Listener {
    private val app = OneEmuApp.get()
    private val dirs: AppDirs get() = app.dirs
    val system: SystemId = SystemId.fromId(game.system) ?: SystemId.GBA

    /** Why a load (or a later fatal event) failed; mirrors the C++ `LoadError` codes. */
    enum class ErrorKind(val code: Int) {
        UNKNOWN(0), CORE_MISSING(1), DLOPEN_FAILED(2), CORE_INIT_FAILED(3), ROM_READ_FAILED(4), ROM_LOAD_FAILED(5),
        ROM_ENCRYPTED(6), GLES_UNSUPPORTED(7), GL_INIT_FAILED(8), ROM_EMPTY(9), DISC_IMAGE_CORRUPT(10), VULKAN_UNAVAILABLE(11), CORE_SHUTDOWN(100);

        companion object {
            fun fromCode(code: Int): ErrorKind = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data object Running : State()
        data object Paused : State()

        /**
         * [message] is the Korean text for the user; [detail] holds the raw reason plus the last core log lines
         * for a "자세히" expander / copy button.
         */
        data class Error(val message: String, val detail: String? = null, val kind: ErrorKind = ErrorKind.UNKNOWN) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = _state

    private val _messages = MutableStateFlow<String?>(null)
    /** Last on-screen message from the core (toast it and clear). */
    val messages: StateFlow<String?> get() = _messages

    private val _rumble = MutableStateFlow(0)
    /** 0..65535 rumble strength requested by the core (port 0). */
    val rumble: StateFlow<Int> get() = _rumble

    /**
     * Which on-screen pad this game wants. Usually the system's own, but a Dolphin disc is either a
     * GameCube pad or a Wii Remote, and that is only known once the disc header has been read.
     */
    private val _padProfile = MutableStateFlow(PadProfile(system))
    val padProfile: StateFlow<PadProfile> get() = _padProfile

    private val _geometry = MutableStateFlow(Geometry(0, 0, 0f))
    val geometry: StateFlow<Geometry> get() = _geometry
    data class Geometry(val width: Int, val height: Int, val aspect: Float)

    val romBaseName: String = File(game.path).nameWithoutExtension
    private var tempRom: File? = null
    private var startedAt = 0L

    /** Missing *required* BIOS files, if any; check before [load] to show a friendly message. */
    fun missingRequiredBios(): List<String> = core.bios.filter { it.required && !File(dirs.system, it.file).exists() }.map { it.file }
    fun missingOptionalBios(): List<String> = core.bios.filter { !it.required && !File(dirs.system, it.file).exists() }.map { it.file }

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        _state.value = State.Loading
        NativeBridge.listener = this@EmulatorSession
        // The 3DS core has no .cia installer; the scanner lists .cia files so users get a clear message here.
        if (system == SystemId.N3DS && File(game.path).extension.equals("cia", true)) {
            _state.value = makeError(ErrorKind.ROM_ENCRYPTED, ".cia files cannot be run directly by the Azahar libretro core")
            return@withContext false
        }
        // A 0-byte file (interrupted copy, cloud placeholder) makes some cores crash instead of failing; say so first.
        File(game.path).let { f -> if (f.isFile && f.length() == 0L) {
            _state.value = makeError(ErrorKind.ROM_EMPTY, "ROM file is empty (0 bytes): ${game.path}")
            return@withContext false
        } }
        // A raw disc image is a whole number of 2048- or 2352-byte sectors; anything else was cut short or damaged
        // (PCSX-ReARMed only reports "unsupported/invalid CD image" for these).
        File(game.path).let { f ->
            val ext = f.extension.lowercase()
            if (f.isFile && system in DISC_SYSTEMS && ext in RAW_DISC_EXTS && f.length() % 2048L != 0L && f.length() % 2352L != 0L) {
                _state.value = makeError(ErrorKind.DISC_IMAGE_CORRUPT, "disc image size ${f.length()} is not a multiple of 2048 or 2352 bytes: ${game.path}")
                return@withContext false
            }
        }
        resolveWiiController()
        NativeBridge.openSessionLog(CrashMarker.sessionLogFile(app).absolutePath)
        NativeBridge.sessionLogLine("session: game=\"${game.title}\" path=${game.path} system=${system.id} core=${core.id} app=${com.manggome.oneemu.BuildConfig.VERSION_NAME} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE}")
        val libPath = app.cores.libraryPath(core)
        if (!libPath.exists()) {
            _state.value = makeError(ErrorKind.CORE_MISSING, (if (core.isDownloadable) "downloadable core not installed: " else "core library not in APK: ") + libPath.absolutePath)
            return@withContext false
        }
        app.cores.installAssets(core, dirs.system)
        val hwApi = hwApiOverride ?: app.settings.graphicsApi(core.id) ?: core.hwRender
        val overrides = buildOptionOverrides(hwApi)
        NativeBridge.sessionLogLine("core options: " + overrides.lines().joinToString(" "))
        val strictGles = core.glesMinVersion.isNotEmpty()
        NativeBridge.sessionLogLine("graphics api offered: $hwApi")
        // Jazz² Resurrection looks for the original game files in the system directory and for its own engine
        // data in the core assets directory. Pointing the first at the folder the entry came from lets the game
        // live anywhere, without writing anything into it (the app has no permission to, outside its own dirs).
        val gameDir = java.io.File(game.path).takeIf { it.isFile }?.parentFile
        val useGameDirAsSystem = core.id == "jazz2" && gameDir != null
        val systemDirForCore = if (useGameDirAsSystem) gameDir!!.absolutePath else dirs.system.absolutePath
        val coreAssetsDir = core.assetsInstallDir.takeIf { it.isNotEmpty() }
            ?.let { java.io.File(dirs.system, it).parentFile?.absolutePath }
            ?: dirs.system.absolutePath
        if (!NativeBridge.loadCore(libPath.absolutePath, systemDirForCore, dirs.saves(system.id).absolutePath, overrides, strictGles, hwApi, core.keepLoaded, coreAssetsDir)) {
            _state.value = makeError(ErrorKind.fromCode(NativeBridge.lastErrorCode()), NativeBridge.lastError())
            return@withContext false
        }
        if (core.supportsNoContent && game.path.startsWith(NO_CONTENT_PREFIX)) {
            if (!NativeBridge.loadGame("")) {
                _state.value = makeError(ErrorKind.fromCode(NativeBridge.lastErrorCode()), NativeBridge.lastError())
                NativeBridge.unload()
                return@withContext false
            }
            if (_state.value !is State.Error) _state.value = State.Paused
            startedAt = System.currentTimeMillis()
            return@withContext true
        }
        dropShaderCacheAfterCrash()
        val romPath = resolveRomPath() ?: run {
            _state.value = makeError(ErrorKind.ROM_READ_FAILED, "ROM not found: ${game.path}")
            NativeBridge.unload()
            return@withContext false
        }
        if (!NativeBridge.loadGame(romPath)) {
            _state.value = makeError(ErrorKind.fromCode(NativeBridge.lastErrorCode()), NativeBridge.lastError())
            NativeBridge.unload()
            return@withContext false
        }
        // A HW-render core may already have failed inside loadGame (context_reset runs there when the surface is
        // attached): onFatal has set State.Error and we must not overwrite it with Paused.
        if (_state.value is State.Error) {
            NativeBridge.unload()
            return@withContext false
        }
        val device = if (wiiDevice != 0) wiiDevice else core.controllerDevice
        if (device != 0) NativeBridge.setControllerPortDevice(0, device)
        applyVideoSettings()
        startedAt = System.currentTimeMillis()
        _state.value = State.Paused
        true
    }

    /** Builds the user-facing [State.Error] for [kind]: Korean summary + raw reason + recent core log. */
    private fun makeError(kind: ErrorKind, reason: String): State.Error {
        val name = core.displayName
        var message = when (kind) {
            ErrorKind.CORE_MISSING -> if (core.isDownloadable) app.getString(R.string.emu_err_core_not_downloaded, name) else app.getString(R.string.emu_err_core_missing, name)
            ErrorKind.DLOPEN_FAILED -> app.getString(R.string.emu_err_dlopen, name)
            ErrorKind.CORE_INIT_FAILED -> app.getString(R.string.emu_err_core_init, name)
            ErrorKind.ROM_READ_FAILED -> app.getString(R.string.emu_err_rom_read)
            ErrorKind.ROM_LOAD_FAILED -> app.getString(R.string.emu_err_rom_load)
            ErrorKind.ROM_ENCRYPTED -> app.getString(R.string.emu_err_rom_encrypted)
            ErrorKind.ROM_EMPTY -> app.getString(R.string.emu_err_rom_empty)
            ErrorKind.DISC_IMAGE_CORRUPT -> app.getString(R.string.emu_err_disc_corrupt)
            ErrorKind.GLES_UNSUPPORTED -> {
                // reason: "core requires OpenGL ES 3.2 but the device context is OpenGL ES 3.1 ..."
                val need = Regex("requires OpenGL ES (\\d\\.\\d)").find(reason)?.groupValues?.get(1) ?: "3.2"
                val have = Regex("context is (OpenGL ES[^(]*)").find(reason)?.groupValues?.get(1)?.trim() ?: "?"
                app.getString(R.string.emu_err_gles, name, need, have)
            }
            ErrorKind.GL_INIT_FAILED -> app.getString(R.string.emu_err_gl_init)
            ErrorKind.VULKAN_UNAVAILABLE -> app.getString(R.string.emu_err_vulkan, name)
            ErrorKind.CORE_SHUTDOWN -> app.getString(R.string.emu_err_shutdown)
            ErrorKind.UNKNOWN -> app.getString(R.string.emu_err_unknown)
        }
        val log = runCatching { NativeBridge.getRecentCoreLog() }.getOrDefault("").trim()
        // PCSX-ReARMed: the last core message is the harmless BIOS notice; the real failure is the disc image.
        if (kind == ErrorKind.ROM_LOAD_FAILED && system == SystemId.PSX && "unsupported/invalid CD image" in log) {
            message = app.getString(R.string.emu_err_disc_corrupt)
        }
        // Arcade: say what the MAME set lacks (BIOS/parent zip, wrong-version files) instead of a generic load failure,
        // and pull MAME's own "NOT FOUND" / "WRONG CHECKSUMS" lines out of the log for the detail box.
        var arcadeDetail = ""
        if (system == SystemId.ARCADE && kind !in ARCADE_UNRELATED_KINDS) {
            val checker = ArcadeRomChecker.get(app)
            val res = runCatching { checker.resolveNow(File(game.path)) }.getOrNull()
            if (res != null) {
                // The DAT may belong to another bundled MAME core (user forced this one): say so first.
                checker.coreMismatchNote(res, core.id)?.let { message = message + "\n\n" + it }
                checker.preliminaryNote(res, core.id)?.let { if (it !in message) message = message + "\n\n" + it }
                if (res.report.severity != ArcadeRomCheck.Severity.OK && res.status != ArcadeRomCheck.Status.NEEDS_SAMPLES) {
                    message = message + "\n\n" + checker.summary(res)
                }
            }
            val mame = checker.mameLoadLines(log)
            if (mame.isNotEmpty()) arcadeDetail = "\n\n" + app.getString(R.string.lib_arcade_log_title) + ":\n" + mame.joinToString("\n")
        }
        val detail = buildString {
            append(app.getString(R.string.emu_error_reason, reason.ifBlank { kind.name }))
            append("\n").append("core=").append(core.id).append(" system=").append(system.id)
            append("\nrom=").append(game.path)
            append(arcadeDetail)
            if (log.isNotEmpty()) append("\n\n").append(app.getString(R.string.emu_error_core_log)).append(":\n").append(log)
        }
        Log.e("OneEmu", "load error [$kind]: $reason\n$log")
        return State.Error(message, detail, kind)
    }

    /** libretro device for port 0 chosen by [WiiController]; 0 = leave the core's own default. */
    private var wiiDevice = 0

    /**
     * GameCube disc or Wii disc, and therefore which controller and which on-screen pad. Dolphin starts a
     * Wii title on a bare Wii Remote, so anything built around the nunchuk stops on "connect the Nunchuk"
     * until the frontend says otherwise.
     */
    private suspend fun resolveWiiController() {
        if (core.id != "dolphin") return
        val platform = runCatching { RomInfo.discPlatform(File(game.path)) }.getOrDefault(RomInfo.DiscPlatform.UNKNOWN)
        val choice = WiiController.fromKey(app.settings.get(com.manggome.oneemu.data.Settings.Keys.wiiController(game.id), ""))
        wiiDevice = choice.deviceFor(platform)
        _padProfile.value = choice.padProfile(platform)
        Log.i("OneEmu", "dolphin: disc=$platform controller=${choice.key} device=$wiiDevice pad=${_padProfile.value.key}")
    }

    private suspend fun buildOptionOverrides(hwApi: String): String {
        val merged = core.defaultOptions.toMutableMap()
        // Options a specific game needs to behave; the user's own settings still sit on top of them.
        merged.putAll(GameQuirks.optionsFor(core.id, game))
        // Cores that pick their backend through their own option must agree with the API the frontend offers;
        // the user's explicit override of that option still wins.
        val user = app.settings.coreOptionOverrides(core.id)
        // Anything set for this one game sits on top of the core-wide settings. A game with none
        // behaves exactly as before.
        val perGame = app.settings.gameOptionOverrides(game.id)
        val vulkan = hwApi == "vulkan"
        BACKEND_OPTIONS[core.id]?.let { (key, vk, gl) -> if (key !in user && key !in perGame) merged[key] = if (vulkan) vk else gl }
        merged.putAll(user)
        merged.putAll(perGame)
        return merged.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }




    /**
     * Jazz² keeps compiled GL programs in Cache/Shaders next to the game. A session killed while that cache is
     * being written leaves a truncated entry behind, and every later launch dies reading it: the game crashed
     * about a second in, for ever, until the folder was removed by hand (confirmed on a Fold 7 - deleting it
     * took the same install from crashing to 57 fps). A previous session that did not end cleanly is the only
     * signal available, so the cache is rebuilt then; it costs a few seconds of shader compilation once.
     */
    private fun dropShaderCacheAfterCrash() {
        if (core.id != "jazz2") return
        val marker = CrashMarker.read(app) ?: return
        if (marker.path != game.path) return
        val file = java.io.File(game.path).takeIf { it.isFile } ?: return
        val dir = file.parentFile ?: return
        val gameDir = if (dir.name.equals("Source", ignoreCase = true)) dir.parentFile ?: dir else dir
        for (root in listOf(gameDir, java.io.File(dirs.system, "jazz2"))) {
            val shaders = java.io.File(root, "Cache/Shaders")
            if (shaders.isDirectory && shaders.deleteRecursively()) {
                Log.i("OneEmu", "jazz2: dropped the shader cache left by a crashed session ($shaders)")
            }
        }
    }

    private suspend fun applyVideoSettings() {
        val s = app.settings
        NativeBridge.setVideoConfig(
            s.get(com.manggome.oneemu.data.Settings.Keys.videoLinearFilter, false),
            s.get(com.manggome.oneemu.data.Settings.Keys.videoAspect, 0),
            s.get(com.manggome.oneemu.data.Settings.Keys.videoRotation(game.id), 0),
            s.get(com.manggome.oneemu.data.Settings.Keys.videoFilter, com.manggome.oneemu.data.Settings.FILTER_NONE),
            s.get(com.manggome.oneemu.data.Settings.Keys.videoFilterStrength, com.manggome.oneemu.data.Settings.DEFAULT_FILTER_STRENGTH),
        )
        NativeBridge.setAudioMuted(!s.get(com.manggome.oneemu.data.Settings.Keys.audioEnabled, true))
    }

    /** Cores that need a real path cannot read zipped ROMs; extract the first matching entry. */
    private fun resolveRomPath(): String? {
        val f = File(game.path)
        if (!f.exists()) return null
        if (!f.extension.equals("zip", true) || !core.needFullPath || system == SystemId.ARCADE) return f.absolutePath
        return runCatching {
            ZipFile(f).use { zip ->
                val wanted = system.extensions
                val entry = zip.entries().asSequence().firstOrNull { e -> !e.isDirectory && wanted.any { e.name.lowercase().endsWith(".$it") } }
                    ?: return@runCatching null
                val out = File(dirs.temp, "${romBaseName}_${File(entry.name).name}")
                if (!out.exists() || out.length() != entry.size) {
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                tempRom = out
                out.absolutePath
            }
        }.getOrNull()
    }

    fun setSurface(surface: Surface?) = NativeBridge.setSurface(surface)
    fun setSurfaceSize(w: Int, h: Int) = NativeBridge.setSurfaceSize(w, h)

    fun resume() {
        if (_state.value is State.Paused || _state.value is State.Running) {
            NativeBridge.setPaused(false)
            _state.value = State.Running
        }
    }

    fun pause() {
        if (_state.value is State.Running) {
            NativeBridge.setPaused(true)
            _state.value = State.Paused
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        if (_state.value is State.Running || _state.value is State.Paused) {
            NativeBridge.saveSram()
            val played = (System.currentTimeMillis() - startedAt) / 1000
            app.db.games().markPlayed(game.id, System.currentTimeMillis(), played)
        }
        NativeBridge.unload()
        NativeBridge.listener = null
        tempRom?.delete()
        _state.value = State.Idle
    }

    // ---- input ----
    fun setInput(buttons: Int, lx: Int = 0, ly: Int = 0, rx: Int = 0, ry: Int = 0, port: Int = 0) =
        NativeBridge.setInput(port, buttons, lx, ly, rx, ry)

    /** [x],[y] normalized 0..1 over the core's framebuffer (for NDS the whole 2-screen image). */
    fun setPointer(x: Float, y: Float, pressed: Boolean) {
        val px = ((x.coerceIn(0f, 1f) * 2f - 1f) * 0x7fff).toInt()
        val py = ((y.coerceIn(0f, 1f) * 2f - 1f) * 0x7fff).toInt()
        NativeBridge.setPointer(px, py, pressed)
    }

    fun setFastForward(enabled: Boolean, speed: Int) = NativeBridge.setFastForward(if (enabled) (if (speed <= 0) -1 else speed) else 0)

    /**
     * Autofire. [mask] is the set of buttons to tap, [rate] the presses per second; the core is
     * driven in frames, so the rate is converted against the usual 60 Hz.
     */
    fun setTurbo(mask: Int, rate: Int) =
        NativeBridge.setTurbo(mask, if (rate <= 0) 6 else (60f / rate).toInt().coerceAtLeast(2))
    fun reset() = NativeBridge.reset()

    // ---- states ----
    fun statePath(slot: Int): File = dirs.statePath(system.id, romBaseName, slot)
    fun stateThumbPath(slot: Int): File = dirs.stateThumbPath(system.id, romBaseName, slot)

    suspend fun saveState(slot: Int): Boolean = withContext(Dispatchers.IO) {
        val ok = NativeBridge.saveState(statePath(slot).absolutePath)
        if (ok) screenshotBitmap()?.let { bmp ->
            val small = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            stateThumbPath(slot).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        ok
    }

    suspend fun loadState(slot: Int): Boolean = withContext(Dispatchers.IO) {
        val f = statePath(slot)
        f.exists() && NativeBridge.loadState(f.absolutePath)
    }

    fun hasState(slot: Int): Boolean = statePath(slot).exists()

    suspend fun screenshotBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val arr = NativeBridge.screenshot() ?: return@withContext null
        val w = arr[0]; val h = arr[1]
        if (w <= 0 || h <= 0) return@withContext null
        Bitmap.createBitmap(arr, 2, w, w, h, Bitmap.Config.ARGB_8888)
    }

    suspend fun saveScreenshot(): File? = withContext(Dispatchers.IO) {
        val bmp = screenshotBitmap() ?: return@withContext null
        val out = File(dirs.screenshots, "${AppDirs.sanitize(game.title)}_${System.currentTimeMillis()}.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    // ---- cheats ----
    /**
     * Pushes the enabled cheats of this game to the core: `retro_cheat_reset` then one `retro_cheat_set` per cheat.
     * Multi-line codes are joined with '+' (the libretro convention; mGBA/FCEUmm/melonDS/PPSSPP all split on it),
     * because several cores do not treat a newline as a separator.
     */
    suspend fun applyCheats() = withContext(Dispatchers.IO) {
        if (_state.value !is State.Running && _state.value !is State.Paused) return@withContext
        NativeBridge.resetCheats()
        app.db.cheats().forGame(game.id).filter { it.enabled }
            .mapNotNull { c -> CheatCodes.normalize(c.code).takeIf { it.isNotEmpty() } }
            .forEachIndexed { i, code -> NativeBridge.setCheat(i, true, code) }
    }

    // ---- core options ----
    data class CoreOption(val key: String, val desc: String, val info: String, val category: String, val current: String, val default: String, val visible: Boolean, val values: List<Pair<String, String>>)

    fun coreOptions(): List<CoreOption> = NativeBridge.getOptions().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 8) return@mapNotNull null
        val values = p[7].split('|').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', it) }
        CoreOption(p[0], p[1], p[2], p[3], p[4], p[5], p[6] == "1", values)
    }.toList()

    suspend fun setCoreOption(key: String, value: String) {
        NativeBridge.setOption(key, value)
        app.settings.setCoreOptionOverride(core.id, key, value)
    }

    // ---- NativeBridge.Listener ----
    override fun onCoreMessage(message: String, durationMs: Int, priority: Int) { _messages.value = message }
    override fun onRumble(port: Int, strength: Int) { if (port == 0) _rumble.value = strength }
    override fun onGeometryChanged(width: Int, height: Int, aspect: Float) { _geometry.value = Geometry(width, height, aspect) }
    companion object {
        /** Library path of a game that is really "just start this core" (see CoreInfo.supportsNoContent). */
        const val NO_CONTENT_PREFIX = "core:"

        /** core id -> (option key, value for Vulkan, value for OpenGL ES) */
        private val BACKEND_OPTIONS = mapOf(
            "ppsspp" to Triple("ppsspp_backend", "vulkan", "opengl"),
            "azaharplus" to Triple("citra_graphics_api", "Vulkan", "OpenGL"),
        )
        private val DISC_SYSTEMS = setOf(SystemId.PSX, SystemId.PS2, SystemId.PSP, SystemId.GC)
        private val RAW_DISC_EXTS = setOf("iso", "bin", "img")
        /** Failures that have nothing to do with the ROM set; the arcade ROM check is skipped for these. */
        private val ARCADE_UNRELATED_KINDS = setOf(
            ErrorKind.CORE_MISSING, ErrorKind.DLOPEN_FAILED, ErrorKind.ROM_READ_FAILED, ErrorKind.GLES_UNSUPPORTED, ErrorKind.GL_INIT_FAILED,
        )
    }

    override fun onCoreShutdown() { _state.value = makeError(ErrorKind.CORE_SHUTDOWN, "RETRO_ENVIRONMENT_SHUTDOWN") }
    override fun onFatal(what: String, errorCode: Int) { _state.value = makeError(ErrorKind.fromCode(errorCode), what) }

    fun consumeMessage() { _messages.value = null }

    /** libretro RETRO_DEVICE_ID_JOYPAD_* bit positions, for building the [setInput] mask. */
    object Buttons {
        const val B = 1 shl 0
        const val Y = 1 shl 1
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val A = 1 shl 8
        const val X = 1 shl 9
        const val L = 1 shl 10
        const val R = 1 shl 11
        const val L2 = 1 shl 12
        const val R2 = 1 shl 13
        const val L3 = 1 shl 14
        const val R3 = 1 shl 15
    }
}

/** Cheat-code text helpers shared by the session and the editors. */
object CheatCodes {
    /**
     * Trims every line, drops blanks/comments and joins the rest with '+', which every bundled core accepts as a
     * separator. Full-width/no-break spaces (common with Korean IMEs) and zero-width characters are normalized too.
     */
    fun normalize(code: String): String =
        code.replace('\u3000', ' ').replace('\u00A0', ' ').replace(Regex("[\u200B\u200C\u200D\uFEFF]"), "")
            .lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .joinToString("+")

    data class Imported(val name: String, val code: String, val enabled: Boolean)

    /**
     * Parses a RetroArch `.cht` file:
     * ```
     * cheats = 2
     * cheat0_desc = "Infinite lives"
     * cheat0_code = "82000000 0001+82000002 0063"
     * cheat0_enable = false
     * ```
     * Unknown keys are ignored; entries without a code are skipped. '+' separators are turned back into lines for editing.
     */
    fun parseCht(text: String): List<Imported> {
        val re = Regex("""^\s*cheat(\d+)_(desc|code|enable)\s*=\s*(.*?)\s*$""")
        val desc = HashMap<Int, String>(); val code = HashMap<Int, String>(); val enable = HashMap<Int, Boolean>()
        for (line in text.lineSequence()) {
            val m = re.find(line) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val value = m.groupValues[3].trim().removeSurrounding("\"")
            when (m.groupValues[2]) {
                "desc" -> desc[idx] = value
                "code" -> code[idx] = value
                "enable" -> enable[idx] = value.equals("true", true) || value == "1"
            }
        }
        return code.keys.sorted().mapNotNull { i ->
            val c = code[i]?.trim().orEmpty()
            if (c.isEmpty()) return@mapNotNull null
            val lines = c.split('+').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            Imported(desc[i]?.ifBlank { null } ?: lines.lines().first(), lines, enable[i] ?: true)
        }
    }
}
