package com.manggome.oneemu.emu.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Choice chips in one row; the selected one filled with the accent colour, like WIPI-X's segmented rows. */
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (on) OneEmuColors.Accent else OneEmuColors.SurfaceHigh, RoundedCornerShape(10.dp))
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFF111111) else OneEmuColors.OnSurface, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = OneEmuColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

/**
 * 화면 · 키패드: arrangement per orientation, keypad design, keypad and picture size, overlay opacity, whether the
 * on-screen keys show at all, and the entry to 게임패드 키매핑. Changes apply live behind the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WipiSettingsSheet(
    gameId: Long,
    padConnected: String?,
    lastPadMask: () -> Int,
    onPadKey: (android.view.KeyEvent) -> Boolean,
    onPadMotion: (android.view.MotionEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val state by remember { WipiPrefs.observe(settings) }.collectAsState(WipiPrefs.State())
    var mappingOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val arrangements = listOf(
        WipiPrefs.Arrangement.PHONE to stringResource(R.string.wipi_arr_phone),
        WipiPrefs.Arrangement.TWO_HAND to stringResource(R.string.wipi_arr_two_hand),
        WipiPrefs.Arrangement.OVERLAY to stringResource(R.string.wipi_arr_overlay),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = OneEmuColors.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.wipi_settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            SectionTitle(stringResource(R.string.wipi_settings_portrait))
            ChoiceRow(arrangements, state.portrait) { v -> scope.launch { settings.set(WipiPrefs.ARRANGEMENT_PORTRAIT, v.name) } }
            SectionTitle(stringResource(R.string.wipi_settings_landscape))
            ChoiceRow(arrangements, state.landscape) { v -> scope.launch { settings.set(WipiPrefs.ARRANGEMENT_LANDSCAPE, v.name) } }

            SectionTitle(stringResource(R.string.wipi_settings_design))
            ChoiceRow(
                listOf(
                    WipiPrefs.Design.CLASSIC to stringResource(R.string.wipi_design_classic),
                    WipiPrefs.Design.FLAT to stringResource(R.string.wipi_design_flat),
                ),
                state.design,
            ) { v -> scope.launch { settings.set(WipiPrefs.DESIGN, v.name) } }

            SectionTitle(stringResource(R.string.wipi_settings_keypad_size, (state.keypadScale * 100).roundToInt()))
            Slider(
                value = state.keypadScale,
                onValueChange = { v -> scope.launch { settings.set(WipiPrefs.KEYPAD_SCALE, v) } },
                valueRange = WipiPrefs.KEYPAD_SCALE_MIN..WipiPrefs.KEYPAD_SCALE_MAX,
            )
            SectionTitle(stringResource(R.string.wipi_settings_screen_size, (state.screenScale * 100).roundToInt()))
            Slider(
                value = state.screenScale,
                onValueChange = { v -> scope.launch { settings.set(WipiPrefs.SCREEN_SCALE, v) } },
                valueRange = WipiPrefs.SCREEN_SCALE_MIN..1f,
            )
            SectionTitle(stringResource(R.string.wipi_settings_opacity, (state.overlayOpacity * 100).roundToInt()))
            Slider(
                value = state.overlayOpacity,
                onValueChange = { v -> scope.launch { settings.set(WipiPrefs.OVERLAY_OPACITY, v) } },
                valueRange = 0f..1f,
            )

            SwitchRow(stringResource(R.string.wipi_settings_fold), null, state.foldDpad) { v -> scope.launch { settings.set(WipiPrefs.FOLD_DPAD, v) } }
            SwitchRow(stringResource(R.string.wipi_settings_show_keypad), stringResource(R.string.wipi_settings_show_keypad_desc), state.showKeypad) { v ->
                scope.launch { settings.set(WipiPrefs.SHOW_KEYPAD, v) }
            }
            SwitchRow(stringResource(R.string.wipi_settings_hide_with_pad), stringResource(R.string.wipi_settings_hide_with_pad_desc), state.hideWithPad) { v ->
                scope.launch { settings.set(WipiPrefs.HIDE_WITH_PAD, v) }
            }
            SectionTitle(stringResource(R.string.wipi_settings_fill))
            ChoiceRow(
                listOf(false to stringResource(R.string.wipi_fill_fit), true to stringResource(R.string.wipi_fill_stretch)),
                state.stretch,
            ) { v -> scope.launch { settings.set(WipiPrefs.STRETCH, v) } }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { mappingOpen = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.wipi_settings_mapping), fontSize = 16.sp)
            }
        }
    }

    if (mappingOpen) WipiPadMappingDialog(gameId, padConnected, lastPadMask, onPadKey, onPadMotion, onDismiss = { mappingOpen = false })
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (desc != null) Text(desc, fontSize = 12.sp, color = OneEmuColors.OnSurfaceMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private enum class PadStyle { RETRO, XBOX, PS }

/** Face / shoulder legend of a RetroPad button on the chosen controller family (positions stay the same). */
private fun padLabel(button: Int, style: PadStyle): String = when (button) {
    Buttons.B -> when (style) { PadStyle.RETRO -> "B"; PadStyle.XBOX -> "A"; PadStyle.PS -> "✕" }
    Buttons.A -> when (style) { PadStyle.RETRO -> "A"; PadStyle.XBOX -> "B"; PadStyle.PS -> "○" }
    Buttons.Y -> when (style) { PadStyle.RETRO -> "Y"; PadStyle.XBOX -> "X"; PadStyle.PS -> "□" }
    Buttons.X -> when (style) { PadStyle.RETRO -> "X"; PadStyle.XBOX -> "Y"; PadStyle.PS -> "△" }
    Buttons.L -> when (style) { PadStyle.RETRO -> "L"; PadStyle.XBOX -> "LB"; PadStyle.PS -> "L1" }
    Buttons.R -> when (style) { PadStyle.RETRO -> "R"; PadStyle.XBOX -> "RB"; PadStyle.PS -> "R1" }
    Buttons.L2 -> when (style) { PadStyle.RETRO -> "ZL"; PadStyle.XBOX -> "LT"; PadStyle.PS -> "L2" }
    Buttons.R2 -> when (style) { PadStyle.RETRO -> "ZR"; PadStyle.XBOX -> "RT"; PadStyle.PS -> "R2" }
    Buttons.SELECT -> when (style) { PadStyle.RETRO -> "Select"; PadStyle.XBOX -> "View"; PadStyle.PS -> "Share" }
    Buttons.START -> when (style) { PadStyle.RETRO -> "Start"; PadStyle.XBOX -> "Menu"; PadStyle.PS -> "Options" }
    Buttons.L3 -> if (style == PadStyle.XBOX) "LS" else "L3"
    Buttons.R3 -> if (style == PadStyle.XBOX) "RS" else "R3"
    Buttons.UP -> "↑"
    Buttons.DOWN -> "↓"
    Buttons.LEFT -> "←"
    Buttons.RIGHT -> "→"
    else -> "?"
}

/** Where each button's chip sits on the 360 x 240 controller drawing (chip centre, dp). */
private val CHIP_AT: Map<Int, Pair<Float, Float>> = mapOf(
    Buttons.L2 to (48f to 22f), Buttons.L to (110f to 22f), Buttons.R to (250f to 22f), Buttons.R2 to (312f to 22f),
    Buttons.UP to (80f to 90f), Buttons.LEFT to (36f to 132f), Buttons.RIGHT to (124f to 132f), Buttons.DOWN to (80f to 174f),
    Buttons.X to (280f to 90f), Buttons.Y to (236f to 132f), Buttons.A to (324f to 132f), Buttons.B to (280f to 174f),
    Buttons.SELECT to (152f to 112f), Buttons.START to (208f to 112f),
    Buttons.L3 to (142f to 212f), Buttons.R3 to (218f to 212f),
)

/**
 * WIPI-X-style 게임패드 키매핑: a controller drawing with every button's phone key; tap a button to pick its key.
 * Pressing a button on the connected pad lights its chip, so the physical button is easy to find. One table for
 * all games, or this game's own when "이 게임만 따로 설정" is on.
 */
@Composable
fun WipiPadMappingDialog(
    gameId: Long,
    padConnected: String?,
    lastPadMask: () -> Int,
    onPadKey: (android.view.KeyEvent) -> Boolean,
    onPadMotion: (android.view.MotionEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf(WipiPadMapping.DEFAULT) }
    var perGame by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(PadStyle.RETRO) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var lit by remember { mutableStateOf(0) }

    LaunchedEffect(gameId) {
        val own = WipiPadMapping.decode(settings.get(WipiPadMapping.perGame(gameId), ""))
        val global = WipiPadMapping.decode(settings.get(WipiPadMapping.GLOBAL, ""))
        perGame = own != null
        mapping = own ?: global ?: WipiPadMapping.DEFAULT
        loaded = true
    }
    // Poll the pad state the emulator activity publishes, to light the pressed button.
    LaunchedEffect(Unit) {
        while (true) {
            lit = lastPadMask()
            kotlinx.coroutines.delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ForwardPadInput(onPadKey, onPadMotion)
        Column(
            Modifier
                .padding(16.dp)
                .background(OneEmuColors.Surface, RoundedCornerShape(20.dp))
                .padding(18.dp)
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.wipi_map_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (padConnected != null) stringResource(R.string.wipi_map_connected, padConnected) else stringResource(R.string.wipi_map_none),
                fontSize = 13.sp,
                color = OneEmuColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                listOf(
                    PadStyle.RETRO to stringResource(R.string.wipi_map_style_retro),
                    PadStyle.XBOX to stringResource(R.string.wipi_map_style_xbox),
                    PadStyle.PS to stringResource(R.string.wipi_map_style_ps),
                ),
                style,
            ) { style = it }
            Spacer(Modifier.height(14.dp))

            Box(Modifier.size(360.dp, 240.dp).align(Alignment.CenterHorizontally)) {
                Canvas(Modifier.fillMaxSize()) {
                    val u = size.width / 360f
                    val body = Color(0xFF26292E)
                    drawRoundRect(body, Offset(20f * u, 50f * u), Size(320f * u, 170f * u), CornerRadius(70f * u))
                    drawRoundRect(Color(0xFF3A3E45), Offset(20f * u, 50f * u), Size(320f * u, 170f * u), CornerRadius(70f * u), style = Stroke(2f * u))
                }
                if (loaded) {
                    for ((button, pos) in CHIP_AT) {
                        val key = PhoneKey.ofBit(mapping.keyFor(button))
                        val on = lit and button != 0
                        Column(
                            Modifier
                                .offset((pos.first - 27f).dp, (pos.second - 20f).dp)
                                .size(54.dp, 40.dp)
                                .background(if (on) OneEmuColors.Accent.copy(alpha = 0.85f) else Color(0xFF34383F), RoundedCornerShape(8.dp))
                                .border(1.dp, if (on) OneEmuColors.Accent else Color(0xFF555A62), RoundedCornerShape(8.dp))
                                .clickable { picking = button },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(padLabel(button, style), fontSize = 10.sp, color = if (on) Color(0xFF111111) else OneEmuColors.OnSurfaceMuted, maxLines = 1)
                            Text(
                                key?.label ?: stringResource(R.string.wipi_map_unassigned),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (on) Color(0xFF111111) else if (key == null) OneEmuColors.OnSurfaceMuted else OneEmuColors.OnSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val pressed = PRESS_NAMES.filter { (bit, _) -> lit and bit != 0 }.joinToString(" + ") { (bit, _) -> padLabel(bit, style) }
            Text(
                if (pressed.isEmpty()) stringResource(R.string.wipi_map_press_none) else stringResource(R.string.wipi_map_pressed, pressed),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (pressed.isEmpty()) OneEmuColors.OnSurfaceMuted else OneEmuColors.Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.wipi_map_hint), fontSize = 12.sp, color = OneEmuColors.OnSurfaceMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { perGame = !perGame }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.wipi_map_per_game), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = perGame, onCheckedChange = { perGame = it })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { mapping = WipiPadMapping.DEFAULT }) { Text(stringResource(R.string.wipi_map_reset)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    scope.launch {
                        if (perGame) {
                            settings.set(WipiPadMapping.perGame(gameId), mapping.encode())
                        } else {
                            settings.set(WipiPadMapping.perGame(gameId), "")
                            settings.set(WipiPadMapping.GLOBAL, mapping.encode())
                        }
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.wipi_map_apply), fontWeight = FontWeight.Bold) }
            }
        }
    }

    picking?.let { button ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.wipi_map_pick, padLabel(button, style))) },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 360.dp)) {
                    items(listOf<PhoneKey?>(null) + PhoneKey.PICKABLE) { k ->
                        val current = mapping.keyFor(button) == (k?.bit ?: 0)
                        Box(
                            Modifier
                                .padding(4.dp)
                                .height(44.dp)
                                .background(if (current) OneEmuColors.Accent else OneEmuColors.SurfaceHigh, RoundedCornerShape(8.dp))
                                .clickable {
                                    mapping = mapping.with(button, k?.bit ?: 0)
                                    picking = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                k?.label ?: stringResource(R.string.wipi_map_unassigned),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (current) Color(0xFF111111) else OneEmuColors.OnSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Pad buttons in the order the "pressed" line lists them. */
private val PRESS_NAMES: List<Pair<Int, String>> = listOf(
    Buttons.UP to "UP", Buttons.DOWN to "DOWN", Buttons.LEFT to "LEFT", Buttons.RIGHT to "RIGHT",
    Buttons.A to "A", Buttons.B to "B", Buttons.X to "X", Buttons.Y to "Y",
    Buttons.L to "L", Buttons.R to "R", Buttons.L2 to "L2", Buttons.R2 to "R2",
    Buttons.L3 to "L3", Buttons.R3 to "R3", Buttons.START to "START", Buttons.SELECT to "SELECT",
)

/**
 * A Dialog is its own window: the pad's key and stick events go to it, not to the emulator activity. This hands
 * them to the activity's pad handler so the dialog can show what is pressed.
 */
@Composable
private fun ForwardPadInput(onKey: (android.view.KeyEvent) -> Boolean, onMotion: (android.view.MotionEvent) -> Boolean) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : android.view.Window.Callback by original {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = onKey(event) || original.dispatchKeyEvent(event)

                override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
                    onMotion(event) || original.dispatchGenericMotionEvent(event)
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}
