package com.manggome.oneemu.emu

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreInfo
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
 * One running game. Wraps [NativeBridge] with everything the UI needs: path resolution,
 * BIOS checks, zip extraction for need_fullpath cores, save-state slots with thumbnails,
 * core option overrides, and libretro button constants.
 *
 * Lifecycle: [load] → (surface attach via [setSurface]) → [resume]/[pause] → [close].
 */
class EmulatorSession(val game: GameEntity, val core: CoreInfo) : NativeBridge.Listener {
    private val app = OneEmuApp.get()
    private val dirs: AppDirs get() = app.dirs
    val system: SystemId = SystemId.fromId(game.system) ?: SystemId.GBA

    /** Why a load (or a later fatal event) failed; mirrors the C++ `LoadError` codes. */
    enum class ErrorKind(val code: Int) {
        UNKNOWN(0), CORE_MISSING(1), DLOPEN_FAILED(2), CORE_INIT_FAILED(3), ROM_READ_FAILED(4), ROM_LOAD_FAILED(5),
        ROM_ENCRYPTED(6), GLES_UNSUPPORTED(7), GL_INIT_FAILED(8), CORE_SHUTDOWN(100);

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
        val libPath = app.cores.libraryPath(core)
        if (!libPath.exists()) {
            _state.value = makeError(ErrorKind.CORE_MISSING, "core library not in APK: ${libPath.absolutePath}")
            return@withContext false
        }
        app.cores.installAssets(core, dirs.system)
        val overrides = buildOptionOverrides()
        val strictGles = core.glesMinVersion.isNotEmpty()
        if (!NativeBridge.loadCore(libPath.absolutePath, dirs.system.absolutePath, dirs.saves(system.id).absolutePath, overrides, strictGles)) {
            _state.value = makeError(ErrorKind.fromCode(NativeBridge.lastErrorCode()), NativeBridge.lastError())
            return@withContext false
        }
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
        applyVideoSettings()
        startedAt = System.currentTimeMillis()
        _state.value = State.Paused
        true
    }

    /** Builds the user-facing [State.Error] for [kind]: Korean summary + raw reason + recent core log. */
    private fun makeError(kind: ErrorKind, reason: String): State.Error {
        val name = core.displayName
        val message = when (kind) {
            ErrorKind.CORE_MISSING -> app.getString(R.string.emu_err_core_missing, name)
            ErrorKind.DLOPEN_FAILED -> app.getString(R.string.emu_err_dlopen, name)
            ErrorKind.CORE_INIT_FAILED -> app.getString(R.string.emu_err_core_init, name)
            ErrorKind.ROM_READ_FAILED -> app.getString(R.string.emu_err_rom_read)
            ErrorKind.ROM_LOAD_FAILED -> app.getString(R.string.emu_err_rom_load)
            ErrorKind.ROM_ENCRYPTED -> app.getString(R.string.emu_err_rom_encrypted)
            ErrorKind.GLES_UNSUPPORTED -> {
                // reason: "core requires OpenGL ES 3.2 but the device context is OpenGL ES 3.1 ..."
                val need = Regex("requires OpenGL ES (\\d\\.\\d)").find(reason)?.groupValues?.get(1) ?: "3.2"
                val have = Regex("context is (OpenGL ES[^(]*)").find(reason)?.groupValues?.get(1)?.trim() ?: "?"
                app.getString(R.string.emu_err_gles, name, need, have)
            }
            ErrorKind.GL_INIT_FAILED -> app.getString(R.string.emu_err_gl_init)
            ErrorKind.CORE_SHUTDOWN -> app.getString(R.string.emu_err_shutdown)
            ErrorKind.UNKNOWN -> app.getString(R.string.emu_err_unknown)
        }
        val log = runCatching { NativeBridge.getRecentCoreLog() }.getOrDefault("").trim()
        val detail = buildString {
            append(app.getString(R.string.emu_error_reason, reason.ifBlank { kind.name }))
            append("\n").append("core=").append(core.id).append(" system=").append(system.id)
            append("\nrom=").append(game.path)
            if (log.isNotEmpty()) append("\n\n").append(app.getString(R.string.emu_error_core_log)).append(":\n").append(log)
        }
        Log.e("OneEmu", "load error [$kind]: $reason\n$log")
        return State.Error(message, detail, kind)
    }

    private suspend fun buildOptionOverrides(): String {
        val merged = core.defaultOptions.toMutableMap()
        merged.putAll(app.settings.coreOptionOverrides(core.id))
        return merged.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    private suspend fun applyVideoSettings() {
        val s = app.settings
        NativeBridge.setVideoConfig(
            s.get(com.manggome.oneemu.data.Settings.Keys.videoLinearFilter, false),
            s.get(com.manggome.oneemu.data.Settings.Keys.videoAspect, 0),
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
