package com.manggome.oneemu.emu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
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
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.input.GamepadInput
import com.manggome.oneemu.emu.pad.PadInput
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
    var menuOpen by mutableStateOf(false)
    var gamepadConnected by mutableStateOf(false)
    var fastForward by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
}

/**
 * Full-screen game activity. Launched with [EXTRA_GAME_ID]; owns one [EmulatorSession], the
 * SurfaceView hand-off to the native renderer, input merging (virtual pad ∪ physical gamepad) and
 * the pause/resume policy (paused whenever the activity is not resumed, has no surface, or an
 * overlay such as the menu is open).
 */
class EmulatorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_GAME_ID = "game_id"

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
    private var gamepadInput = PadInput()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = Haptics(this)
        gamepad = GamepadInput(
            this,
            onChanged = { mask, lx, ly, rx, ry -> gamepadInput = PadInput(mask, lx, ly, rx, ry); pushInput() },
            onMenu = { toggleMenu() },
        )
        gamepad.startWatching { ui.gamepadConnected = gamepad.isGamepadConnected() }
        ui.gamepadConnected = gamepad.isGamepadConnected()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        onBackPressedDispatcher.addCallback(this) { toggleMenu() }

        val gameId = intent.getLongExtra(EXTRA_GAME_ID, -1L)
        lifecycleScope.launch { startSession(gameId) }

        setContent { OneEmuTheme { EmulatorScreen(this) } }
    }

    private suspend fun startSession(gameId: Long) {
        val game = app.db.games().get(gameId) ?: run { ui.error = getString(R.string.emu_game_not_found); return }
        ui.title = game.title
        val system = SystemId.fromId(game.system)
        val core = game.coreId?.let { app.cores.core(it) } ?: system?.let { app.cores.defaultCoreFor(it) }
        if (core == null) { ui.error = getString(R.string.emu_no_core); return }
        val session = EmulatorSession(game, core)
        val missing = session.missingRequiredBios()
        if (missing.isNotEmpty()) { ui.error = getString(R.string.emu_missing_bios, missing.joinToString(", ")); return }
        ui.session = session
        if (session.load()) {
            session.applyCheats()
            updateRunning()
        }
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
    }

    private fun toggleMenu() {
        if (closed || ui.session == null) return
        ui.menuOpen = !ui.menuOpen
    }

    // ---- input ----
    fun onPadInput(input: PadInput) {
        padInput = input
        pushInput()
    }

    private fun pushInput() {
        val s = ui.session ?: return
        val p = padInput
        val g = gamepadInput
        val leftFromPad = p.lx != 0 || p.ly != 0
        val rightFromPad = p.rx != 0 || p.ry != 0
        s.setInput(
            p.mask or g.mask,
            if (leftFromPad) p.lx else g.lx,
            if (leftFromPad) p.ly else g.ly,
            if (rightFromPad) p.rx else g.rx,
            if (rightFromPad) p.ry else g.ry,
        )
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
        isResumed = true
        applyImmersive()
        updateRunning()
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
            ioScope.launch { s?.close() }
        }
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
