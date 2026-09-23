package com.manggome.oneemu.emu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.manggome.oneemu.emu.input.MotionSensors
import com.manggome.oneemu.emu.input.StickDpad
import com.manggome.oneemu.emu.pad.isPlayStation
import kotlinx.coroutines.flow.flatMapLatest
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.input.GamepadDevices
import com.manggome.oneemu.emu.input.GamepadInput
import com.manggome.oneemu.emu.input.GamepadMapping
import com.manggome.oneemu.emu.pad.PadInput
import com.manggome.oneemu.library.ArcadeCoreRouter
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.theme.OneEmuTheme
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Compose-observable state shared between [EmulatorActivity] and [EmulatorScreen]. */
class EmulatorUiState {
    var session by mutableStateOf<EmulatorSession?>(null)
    var title by mutableStateOf("")
    /** Error raised before/outside the session (missing core, BIOS, game row). */
    var error by mutableStateOf<String?>(null)
    /** A `distribution: download` core the game needs but which is not installed: the screen offers the download, then [EmulatorActivity.retryStart]. */
    var downloadCore by mutableStateOf<CoreInfo?>(null)
    var menuOpen by mutableStateOf(false)
    var gamepadConnected by mutableStateOf(false)
    var fastForward by mutableStateOf(false)
    /** 연사: the buttons chosen in the settings are tapped for you while this is on. */
    var turbo by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    /** A pad button asked for the save / load slot list; the screen opens it and clears this. */
    var slotRequest by mutableStateOf<SlotRequest?>(null)
}

enum class SlotRequest { SAVE, LOAD }

/**
 * Full-screen game activity. Launched with [EXTRA_GAME_ID]; owns one [EmulatorSession], the
 * SurfaceView hand-off to the native renderer, input merging (virtual pad ∪ physical gamepad) and
 * the pause/resume policy (paused whenever the activity is not resumed, has no surface, or an
 * overlay such as the menu is open).
 */
class EmulatorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_CORE_ID = "core_id"
        /** Debug builds only: "vulkan" / "gles3" forces the graphics API offered to the core (adb diagnostics). */
        const val EXTRA_HW_API = "hw_api"

        fun intent(context: Context, gameId: Long): Intent =
            Intent(context, EmulatorActivity::class.java).putExtra(EXTRA_GAME_ID, gameId)
    }

    internal val ui = EmulatorUiState()
    internal lateinit var haptics: Haptics
    private lateinit var gamepad: GamepadInput
    private val app get() = OneEmuApp.get()
    private val settings: Settings get() = app.settings

    /** Survives lifecycle cancellation so close/auto-save always complete. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var surface: Surface? = null
    private var isResumed = false
    private var overlayOpen = false
    private var closed = false
    private var padInput = PadInput()
    /** [StickDpad] for the pad being played; read on every input push. */
    @Volatile private var stickDpad = false
    /** [GamepadMapping.PS_POSITIONAL] applies: a PlayStation game with the setting on. */
    @Volatile private var psPositional = false
    private var psPositionalJob: kotlinx.coroutines.Job? = null
    private var stickDpadJob: kotlinx.coroutines.Job? = null
    /** One entry per player; pads publish on the port they were assigned. */
    private val gamepadInputs = Array(GamepadDevices.MAX_PLAYERS) { PadInput() }
    private var gameId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = Haptics(this)
        gamepad = GamepadInput(
            this,
            onChanged = { port, mask, lx, ly, rx, ry ->
                if (port in gamepadInputs.indices) {
                    gamepadInputs[port] = PadInput(mask, lx, ly, rx, ry)
                    pushInput(port)
                }
            },
            onMenu = { toggleMenu() },
            onTurbo = { toggleTurbo() },
            onAction = { action ->
                when (action) {
                    GamepadMapping.FAST_FORWARD -> setFastForward(!ui.fastForward)
                    GamepadMapping.SPEED -> cycleSpeed()
                    GamepadMapping.SAVE_STATE -> if (ui.session != null) ui.slotRequest = SlotRequest.SAVE
                    GamepadMapping.LOAD_STATE -> if (ui.session != null) ui.slotRequest = SlotRequest.LOAD
                }
            },
        )
        gamepad.startWatching { ui.gamepadConnected = gamepad.isGamepadConnected() }
        ui.gamepadConnected = gamepad.isGamepadConnected()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        onBackPressedDispatcher.addCallback(this) { toggleMenu() }

        gameId = intent.getLongExtra(EXTRA_GAME_ID, -1L)
        lifecycleScope.launch { startSession(gameId) }

        setContent { OneEmuTheme { EmulatorScreen(this) } }
    }

    private suspend fun startSession(gameId: Long) {
        val game = app.db.games().get(gameId) ?: run { ui.error = getString(R.string.emu_game_not_found); return }
        if (CrashMarker.crashedJustNow(this, game.path)) {
            // The system relaunches a crashed foreground app into the same activity (no saved state). Running the
            // game that died a moment ago would loop and wipe its crash records: show the library and the report.
            startActivity(Intent(this, com.manggome.oneemu.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        ui.title = game.title
        val system = SystemId.fromId(game.system)
        // Arcade zips are routed to the MAME core whose DAT lists them (same rule as the library's launch check).
        val route = if (system == SystemId.ARCADE) ArcadeCoreRouter.route(game, app) else null
        if (route?.neededCoreId != null) {
            // Downloadable core (MAME 2010 / current MAME) not installed yet: offer the download instead of a plain error.
            ArcadeCoreRouter.needsDownload(app.cores, route.neededCoreId)?.let { ui.downloadCore = it; return }
            ui.error = getString(R.string.lib_launch_core_needed, ArcadeCoreRouter.displayName(app.cores, route.neededCoreId), ArcadeCoreRouter.mameVersion(route.neededCoreId))
            return
        }
        // Optional explicit core (used by "이 코어로 실행" and for diagnostics); falls back to routing/defaults.
        val forced = intent.getStringExtra(EXTRA_CORE_ID)?.let { app.cores.core(it) }
        if (forced != null && !app.cores.isAvailable(forced) && forced.isDownloadable) { ui.downloadCore = forced; return }
        val forcedCore = forced?.takeIf { app.cores.isAvailable(it) }
        val core = forcedCore ?: route?.core ?: game.coreId?.let { app.cores.core(it) } ?: system?.let { app.cores.defaultCoreFor(it) }
        if (core == null) { ui.error = getString(R.string.emu_no_core); return }
        if (!app.cores.isAvailable(core) && core.isDownloadable) { ui.downloadCore = core; return }
        val hwApi = if (com.manggome.oneemu.BuildConfig.DEBUG) intent.getStringExtra(EXTRA_HW_API) else null
        val session = EmulatorSession(game, core, hwApi)
        val missing = session.missingRequiredBios()
        if (missing.isNotEmpty()) { ui.error = getString(R.string.emu_missing_bios, missing.joinToString(", ")); return }
        // Motion sensors, for the one core that reads them (Dolphin: the Wii Remote's tilt, swing and, in
        // IR mode 3, its pointer). Reported before the core loads, because it asks during its first frames.
        motion?.stop()
        motion = if (session.wantsMotion) MotionSensors(this) { displayRotation } else null
        session.gyroAvailable = motion?.hasGyroscope == true
        NativeBridge.setSensorsAvailable(motion?.hasAccelerometer == true, motion?.hasGyroscope == true)
        ui.session = session
        stickDpadJob?.cancel()
        stickDpadJob = lifecycleScope.launch {
            // The pad can change after load (Dolphin decides Wii Remote or GameCube pad from the disc).
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            session.padProfile.flatMapLatest { StickDpad.observe(settings, it) }.collect { on ->
                stickDpad = on
                for (port in gamepadInputs.indices) pushInput(port)
            }
        }
        psPositionalJob?.cancel()
        psPositionalJob = if (!session.system.isPlayStation) null else lifecycleScope.launch {
            settings.observe(GamepadMapping.PS_POSITIONAL, true).collect { on ->
                psPositional = on
                for (port in gamepadInputs.indices) pushInput(port)
            }
        }
        psPositional = false
        CrashMarker.write(this, game.title, game.path, core.id)
        if (session.load()) {
            session.applyCheats()
            updateRunning()
            if (settings.get(MotionSensors.AUTO_CENTER, true)) recenterGyro(announce = true)
        }
    }

    /** After a core download finished in the download dialog: start the session for real. */
    fun retryStart() {
        ui.downloadCore = null
        ui.error = null
        lifecycleScope.launch { startSession(gameId) }
    }

    // ---- surface (called from EmulatorScreen's SurfaceHolder.Callback, main thread) ----
    fun onSurfaceCreated(s: Surface) {
        surface = s
        NativeBridge.setSurface(s)
        updateRunning()
    }

    fun onSurfaceChanged(width: Int, height: Int) = NativeBridge.setSurfaceSize(width, height)

    fun onSurfaceDestroyed() {
        surface = null
        updateRunning()
        // Blocks until the emu thread has let go of the window, so the holder can release it safely.
        NativeBridge.setSurface(null)
    }

    // ---- pause / resume policy ----
    fun setOverlayOpen(open: Boolean) {
        overlayOpen = open
        updateRunning()
    }

    private fun updateRunning() {
        val s = ui.session ?: return
        val shouldRun = isResumed && surface != null && !overlayOpen && !closed
        if (shouldRun) s.resume() else s.pause()
        // Sensors only while the game actually runs: they cost battery, and a paused remote should not drift.
        if (shouldRun) motion?.start() else motion?.stop()
    }

    /** The phone's motion, when the running core reads it; see [MotionSensors]. */
    private var motion: MotionSensors? = null

    /** Cached: MotionSensors asks on every reading, and the display only turns on a configuration change. */
    @Volatile private var displayRotation: Int = Surface.ROTATION_0

    private fun readDisplayRotation() {
        displayRotation = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display?.rotation
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }.getOrNull() ?: Surface.ROTATION_0
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val before = displayRotation
        readDisplayRotation()
        if (displayRotation != before) lifecycleScope.launch {
            if (settings.get(MotionSensors.AUTO_CENTER, true)) recenterGyro(announce = true)
        }
    }

    /** Held into port 0 on top of everything else, for presses the app makes itself. */
    @Volatile private var syntheticMask = 0
    private var recenterJob: kotlinx.coroutines.Job? = null

    /**
     * When the phone's motion aims the Wii Remote, Dolphin counts "straight ahead" as the remote held level
     * until 재조준 is pressed. Nobody holds a phone level - it is tipped back towards the face - so the
     * pointer started below the screen and simply never appeared. Pressing 재조준 once, shortly after the game
     * is running and again whenever the screen turns, makes however the phone is held right now the middle.
     */
    fun recenterGyro(delayMs: Long = 1500, announce: Boolean = false) {
        if (ui.session?.irMode != EmulatorSession.IR_MODE_GYRO) return
        recenterJob?.cancel()
        recenterJob = lifecycleScope.launch {
            if (announce) ui.toast = getString(R.string.gyro_center_hold)
            kotlinx.coroutines.delay(delayMs)
            syntheticMask = EmulatorSession.Buttons.L3
            pushInput(0)
            kotlinx.coroutines.delay(150)
            syntheticMask = 0
            pushInput(0)
            if (announce) ui.toast = getString(R.string.gyro_center_done)
        }
    }

    /** True while the Wii Remote points with the phone's motion, which is when 자이로 가운데 맞추기 means anything. */
    val gyroAiming: Boolean get() = ui.session?.irMode == EmulatorSession.IR_MODE_GYRO

    private fun toggleMenu() {
        if (closed || ui.session == null) return
        ui.menuOpen = !ui.menuOpen
    }

    // ---- input ----
    fun onPadInput(input: PadInput) {
        padInput = input
        pushInput(0)
    }

    /** The on-screen pad is always player 1; every other port is whatever its controller reports. */
    private fun pushInput(port: Int) {
        val s = ui.session ?: return
        val raw = gamepadInputs[port]
        // Only the physical pad: the on-screen PlayStation pad already draws ×○□△ where they belong.
        val g = if (psPositional) raw.copy(mask = GamepadMapping.toPositional(raw.mask)) else raw
        if (port != 0) {
            s.setInput(g.mask or (if (stickDpad) StickDpad.mask(g.lx, g.ly) else 0), g.lx, g.ly, g.rx, g.ry, port)
            return
        }
        val p = padInput
        val leftFromPad = p.lx != 0 || p.ly != 0
        val rightFromPad = p.rx != 0 || p.ry != 0
        val lx = if (leftFromPad) p.lx else g.lx
        val ly = if (leftFromPad) p.ly else g.ly
        s.setInput(
            p.mask or g.mask or syntheticMask or (if (stickDpad) StickDpad.mask(lx, ly) else 0),
            lx,
            ly,
            if (rightFromPad) p.rx else g.rx,
            if (rightFromPad) p.ry else g.ry,
        )
    }

    /** 연사 on/off. The core does the tapping itself, so the rate holds however the game runs. */
    fun toggleTurbo() {
        val s = ui.session ?: return
        lifecycleScope.launch {
            val mask = settings.get(Settings.Keys.turboMask, Settings.DEFAULT_TURBO_MASK)
            val rate = settings.get(Settings.Keys.turboRate, Settings.DEFAULT_TURBO_RATE)
            if (mask == 0) {
                ui.toast = getString(R.string.turbo_no_buttons)
                return@launch
            }
            ui.turbo = !ui.turbo
            s.setTurbo(if (ui.turbo) mask else 0, rate)
            ui.toast = getString(if (ui.turbo) R.string.turbo_on else R.string.turbo_off)
        }
    }

    /** 배속 button: steps through 1× (normal) → 2 → 3 → 5 → 10 → 무제한 and applies it at once. */
    fun cycleSpeed() {
        val s = ui.session ?: return
        lifecycleScope.launch {
            val current = if (ui.fastForward) settings.get(Settings.Keys.fastForwardSpeed, Settings.DEFAULT_FF_SPEED) else 1
            val next = Settings.nextSpeed(current)
            if (next != 1) settings.set(Settings.Keys.fastForwardSpeed, next)
            ui.fastForward = next != 1
            s.setFastForward(next != 1, next)
            ui.toast = if (next == 0) getString(R.string.ff_unlimited) else getString(R.string.ff_speed_x, next)
        }
    }

    fun setFastForward(enabled: Boolean) {
        val s = ui.session ?: return
        ui.fastForward = enabled
        lifecycleScope.launch {
            s.setFastForward(enabled, settings.get(Settings.Keys.fastForwardSpeed, Settings.DEFAULT_FF_SPEED))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK && GamepadInput.isControllerEvent(event) && gamepad.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // ---- lifecycle ----
    override fun onResume() {
        super.onResume()
        readDisplayRotation()
        isResumed = true
        applyImmersive()
        updateRunning()
        // Cheats may have been edited from the library while this activity was in the background.
        ui.session?.let { s -> lifecycleScope.launch { s.applyCheats() } }
    }

    override fun onPause() {
        isResumed = false
        updateRunning()
        autoSave()
        super.onPause()
    }

    override fun onStop() {
        // Stopped for a long time (home screen, another app): the session is already paused and SRAM
        // flushed by pause(); the surface is destroyed by the system, which detaches the renderer.
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        if (!closed) {
            closed = true
            val s = ui.session
            ioScope.launch { s?.close(); CrashMarker.clear(this@EmulatorActivity) }
        }
        motion?.stop()
        gamepad.stopWatching()
        haptics.cancel()
        super.onDestroy()
    }

    /** Saves the auto slot if enabled; runs on [ioScope] so it completes even mid-teardown. */
    private fun autoSave() {
        val s = ui.session ?: return
        if (closed) return
        val st = s.state.value
        if (st !is EmulatorSession.State.Paused && st !is EmulatorSession.State.Running) return
        ioScope.launch {
            if (settings.get(Settings.Keys.autoSaveState, true)) s.saveState(AppDirs.AUTO_SLOT)
        }
    }

    /** 종료 flow: auto-save, flush SRAM, unload the core, then finish. */
    fun closeAndFinish() {
        if (closed) return
        closed = true
        val s = ui.session
        ui.session?.setInput(0)
        lifecycleScope.launch {
            if (s != null) {
                val st = s.state.value
                if ((st is EmulatorSession.State.Paused || st is EmulatorSession.State.Running) &&
                    settings.get(Settings.Keys.autoSaveState, true)
                ) s.saveState(AppDirs.AUTO_SLOT)
                s.close()
            }
            CrashMarker.clear(this@EmulatorActivity)
            finish()
        }
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
