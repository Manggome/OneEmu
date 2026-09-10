package com.manggome.oneemu.emu

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.menu.CheatsSheet
import com.manggome.oneemu.emu.menu.InGameMenuDialog
import com.manggome.oneemu.emu.menu.MenuAction
import com.manggome.oneemu.emu.menu.QuickSettingsSheet
import com.manggome.oneemu.emu.menu.SlotMode
import com.manggome.oneemu.emu.menu.SlotPickerSheet
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.PadLayoutStore
import com.manggome.oneemu.emu.skin.PadHost
import com.manggome.oneemu.emu.pad.computeGameRect
import com.manggome.oneemu.ui.layout.LayoutEditor
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Sheet { NONE, SAVE, LOAD, CHEATS, SETTINGS }

/**
 * Whole emulator window: SurfaceView underneath, virtual pad + HUD on top, then the pause menu and
 * its sheets. Any open overlay pauses the session through [EmulatorActivity.setOverlayOpen].
 */
@Composable
internal fun EmulatorScreen(host: EmulatorActivity) {
    val ui = host.ui
    val settings = remember { OneEmuApp.get().settings }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val session = ui.session

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var editorOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val overlayOpen = ui.menuOpen || sheet != Sheet.NONE || editorOpen || confirmExit || confirmReset
    LaunchedEffect(overlayOpen) { host.setOverlayOpen(overlayOpen) }

    val showFps by settings.observe(Settings.Keys.showFps, false).collectAsState(false)
    val padOpacity by settings.observe(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY).collectAsState(Settings.DEFAULT_PAD_OPACITY)
    val padScale by settings.observe(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE).collectAsState(Settings.DEFAULT_PAD_SCALE)
    val vibration by settings.observe(Settings.Keys.padVibration, true).collectAsState(true)
    val vibrationMs by settings.observe(Settings.Keys.padVibrationMs, Settings.DEFAULT_VIBRATION_MS).collectAsState(Settings.DEFAULT_VIBRATION_MS)
    val hideWithGamepad by settings.observe(Settings.Keys.padHideWithGamepad, true).collectAsState(true)
    val aspectMode by settings.observe(Settings.Keys.videoAspect, 0).collectAsState(0)
    val ffSpeed by settings.observe(Settings.Keys.fastForwardSpeed, Settings.DEFAULT_FF_SPEED).collectAsState(Settings.DEFAULT_FF_SPEED)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = host.onSurfaceCreated(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surfaceSize = IntSize(width, height)
                            host.onSurfaceChanged(width, height)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) = host.onSurfaceDestroyed()
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (session != null) {
            val state by session.state.collectAsState()
            val geometry by session.geometry.collectAsState()
            val layout by remember(session.system, landscape) { PadLayoutStore.observe(session.system, landscape) }
                .collectAsState(initial = DefaultLayouts.forSystem(session.system, landscape))
            val gameRect = if (session.system.hasTouchScreen) computeGameRect(surfaceSize, geometry, aspectMode) else null
            val hidePad = hideWithGamepad && ui.gamepadConnected

            if (!editorOpen) {
                PadHost(
                    layout = if (hidePad) PadLayout(emptyList()) else layout,
                    system = session.system,
                    opacity = padOpacity,
                    globalScale = padScale,
                    hapticMs = if (vibration) vibrationMs else -1,
                    haptics = host.haptics,
                    gameRect = gameRect,
                    fastForwardActive = ui.fastForward,
                    onInput = host::onPadInput,
                    onPointer = { x, y, pressed -> session.setPointer(x, y, pressed) },
                    onMenu = { ui.menuOpen = true },
                    onFastForward = host::setFastForward,
                )
            }

            LaunchedEffect(session) { session.rumble.collect { host.haptics.rumble(it) } }

            val message by session.messages.collectAsState()
            message?.let { msg ->
                Banner(msg, Modifier.align(Alignment.TopCenter))
                LaunchedEffect(msg) { delay(3000); session.consumeMessage() }
            }

            if (showFps) FpsOverlay(Modifier.align(Alignment.TopStart))

            if (state is EmulatorSession.State.Loading || state is EmulatorSession.State.Idle) LoadingOverlay(ui.title)

            val sessionError = state as? EmulatorSession.State.Error
            val error = ui.error ?: sessionError?.message
            if (error != null) ErrorDialog(error, sessionError?.detail) { host.closeAndFinish() }

            val ffLabel = (if (ffSpeed <= 0) stringResource(R.string.ff_unlimited) else stringResource(R.string.ff_speed_x, ffSpeed)) +
                " · " + stringResource(if (ui.fastForward) R.string.ff_on else R.string.ff_off)

            if (ui.menuOpen) {
                InGameMenuDialog(
                    title = ui.title,
                    fastForwardLabel = ffLabel,
                    onDismiss = { ui.menuOpen = false },
                    onAction = { action ->
                        ui.menuOpen = false
                        when (action) {
                            MenuAction.LOAD -> sheet = Sheet.LOAD
                            MenuAction.SAVE -> sheet = Sheet.SAVE
                            MenuAction.FAST_FORWARD -> host.setFastForward(!ui.fastForward)
                            MenuAction.CHEATS -> sheet = Sheet.CHEATS
                            MenuAction.SETTINGS -> sheet = Sheet.SETTINGS
                            MenuAction.SCREENSHOT -> scope.launch {
                                val file = session.saveScreenshot()
                                if (file != null) {
                                    Screenshots.exportToGallery(context, file)
                                    ui.toast = context.getString(R.string.screenshot_saved, file.name)
                                } else {
                                    ui.toast = context.getString(R.string.screenshot_failed)
                                }
                            }
                            MenuAction.RESET -> confirmReset = true
                            MenuAction.CLOSE -> confirmExit = true
                        }
                    },
                )
            }

            when (sheet) {
                Sheet.SAVE, Sheet.LOAD -> SlotPickerSheet(
                    mode = if (sheet == Sheet.SAVE) SlotMode.SAVE else SlotMode.LOAD,
                    session = session,
                    onDone = { msg -> sheet = Sheet.NONE; ui.toast = msg },
                    onDismiss = { sheet = Sheet.NONE },
                )
                Sheet.CHEATS -> CheatsSheet(
                    gameId = session.game.id,
                    core = session.core,
                    onChanged = { session.applyCheats() },
                    onMessage = { ui.toast = it },
                    onDismiss = { sheet = Sheet.NONE },
                )
                Sheet.SETTINGS -> QuickSettingsSheet(
                    session = session,
                    onEditLayout = { sheet = Sheet.NONE; editorOpen = true },
                    onDismiss = { sheet = Sheet.NONE },
                )
                Sheet.NONE -> {}
            }

            if (editorOpen) {
                LayoutEditor(system = session.system, landscape = landscape, showMockGame = false, onClose = { editorOpen = false })
            }

            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    text = { Text(stringResource(R.string.reset_confirm)) },
                    confirmButton = { TextButton(onClick = { confirmReset = false; session.reset() }) { Text(stringResource(R.string.menu_reset)) } },
                    dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        } else {
            LoadingOverlay(ui.title)
            ui.error?.let { ErrorDialog(it, null) { host.closeAndFinish() } }
        }

        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                text = { Text(stringResource(R.string.emu_exit_confirm)) },
                confirmButton = { TextButton(onClick = { confirmExit = false; host.closeAndFinish() }) { Text(stringResource(R.string.emu_exit)) } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        ui.toast?.let { msg ->
            Banner(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
            LaunchedEffect(msg) { delay(2200); if (ui.toast == msg) ui.toast = null }
        }
    }
}

/** Small translucent pill used for core messages and our own toasts. */
@Composable
private fun Banner(text: String, modifier: Modifier) {
    Surface(
        modifier.displayCutoutPadding().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC1E1E1E),
    ) {
        Text(text, color = OneEmuColors.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun FpsOverlay(modifier: Modifier) {
    var fps by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) {
            fps = NativeBridge.getFps()
            delay(500)
        }
    }
    Text(
        String.format(java.util.Locale.US, "%.0f", fps),
        color = OneEmuColors.Accent,
        fontSize = 11.sp,
        modifier = modifier.displayCutoutPadding().padding(6.dp).background(Color(0x66000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun LoadingOverlay(title: String) {
    Box(Modifier.fillMaxSize().background(OneEmuColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = OneEmuColors.Accent)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.emu_loading_title, title),
                color = OneEmuColors.OnSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/**
 * Load/fatal error dialog. [detail] (raw reason + recent core log) is hidden behind a "자세히" toggle and can be
 * copied to the clipboard so users can paste it into a bug report.
 */
@Composable
private fun ErrorDialog(message: String, detail: String?, onClose: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.emu_error_title)) },
        text = {
            Column {
                Text(message)
                if (detail != null && expanded) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        detail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = OneEmuColors.OnSurfaceMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .background(Color(0x22000000), RoundedCornerShape(6.dp))
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                    if (copied) {
                        Text(stringResource(R.string.emu_error_copied), style = MaterialTheme.typography.labelSmall, color = OneEmuColors.Accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = if (detail == null) null else {
            {
                Row {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.emu_error_details_hide else R.string.emu_error_details))
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString("$message\n\n$detail")); copied = true; expanded = true }) {
                        Text(stringResource(R.string.emu_error_copy))
                    }
                }
            }
        },
    )
}
