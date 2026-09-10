package com.manggome.oneemu.emu

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.manggome.oneemu.OneEmuApp
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

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data object Running : State()
        data object Paused : State()
        data class Error(val message: String) : State()
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
        val libPath = app.cores.libraryPath(core)
        if (!libPath.exists()) {
            _state.value = State.Error("이 APK에는 ${core.displayName} 코어가 포함되어 있지 않습니다.")
            return@withContext false
        }
        app.cores.installAssets(core, dirs.system)
        val overrides = buildOptionOverrides()
        if (!NativeBridge.loadCore(libPath.absolutePath, dirs.system.absolutePath, dirs.saves(system.id).absolutePath, overrides)) {
            _state.value = State.Error("코어를 불러오지 못했습니다: ${NativeBridge.lastError()}")
            return@withContext false
        }
        val romPath = resolveRomPath() ?: run {
            _state.value = State.Error("ROM 파일을 열 수 없습니다.")
            return@withContext false
        }
        if (!NativeBridge.loadGame(romPath)) {
            val err = NativeBridge.lastError().ifEmpty { "코어가 게임을 불러오지 못했습니다" }
            _state.value = State.Error(err)
            NativeBridge.unload()
            return@withContext false
        }
        applyVideoSettings()
        startedAt = System.currentTimeMillis()
        _state.value = State.Paused
        true
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
    suspend fun applyCheats() = withContext(Dispatchers.IO) {
        NativeBridge.resetCheats()
        app.db.cheats().forGame(game.id).filter { it.enabled }.forEachIndexed { i, c -> NativeBridge.setCheat(i, true, c.code) }
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
    override fun onCoreShutdown() { _state.value = State.Error("코어가 종료를 요청했습니다") }
    override fun onFatal(what: String) { Log.e("OneEmu", "fatal: $what"); _state.value = State.Error(what) }

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
