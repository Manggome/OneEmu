package com.manggome.oneemu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.input.GamepadCapture
import com.manggome.oneemu.emu.input.GamepadMapping
import kotlinx.coroutines.launch

/**
 * One row per libretro button, showing the key that presses it. Tapping a row waits for a press on
 * the pad and binds whatever came in, so a controller whose A and B are swapped can be fixed without
 * knowing any key codes.
 */
@Composable
internal fun GamepadMappingScreen(deviceKey: String, onBack: () -> Unit, onMacros: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val settings = OneEmuApp.get().settings
    var mapping by remember { mutableStateOf(GamepadMapping.DEFAULT) }
    var capturing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // True when the press should be added to the button's keys instead of replacing them.
    var adding by remember { mutableStateOf(false) }
    // 조합 버튼: first the key is captured, then the buttons it presses are picked.
    var comboCapture by remember { mutableStateOf(false) }
    var comboKey by remember { mutableStateOf<Int?>(null) }
    // 매크로 버튼: capture the key, then pick which macro it plays.
    var macroCapture by remember { mutableStateOf(false) }
    var macroKey by remember { mutableStateOf<Int?>(null) }
    val macros by remember { com.manggome.oneemu.emu.input.Macros.observe(settings) }.collectAsState(emptyList())
    var resetting by remember { mutableStateOf(false) }

    val devices = rememberPadDevices()
    val layout by remember { GamepadMapping.observeLayout(settings) }.collectAsState(GamepadMapping.LAYOUT_AUTO)
    val device = devices.firstOrNull { it.key == deviceKey }

    LaunchedEffect(deviceKey) { mapping = loadMapping(deviceKey) }

    fun save(next: GamepadMapping) {
        mapping = next
        scope.launch { settings.set(Settings.Keys.gamepadMapping(deviceKey), next.overridesOf()) }
    }

    SettingsScaffold(title = device?.name ?: stringResource(R.string.gamepad_buttons), onBack = onBack) {
        if (device == null) NoteText(stringResource(R.string.gamepad_disconnected))
        else NoteText(stringResource(R.string.gamepad_map_hint))
        SettingsDivider()
        for ((name, button) in GamepadMapping.assignable) {
            if (name == "MENU") {
                SettingsDivider()
                SectionHeader(stringResource(R.string.gamepad_actions))
            }
            val keys = mapping.keysFor(button)
            // What the same row presses in a PlayStation game, so the game's own ×○□△ key settings can be
            // matched up without guessing.
            val ps = psGlyph(button, GamepadMapping.positionalFor(layout, playStation = true))
            // In position mode a Nintendo-style game sees the other letter, so say which.
            val other = if (layout == GamepadMapping.LAYOUT_POSITION && ps != null) GamepadMapping.nameOf(GamepadMapping.toPositional(button)) else null
            SettingsRow(
                title = when {
                    ps == null -> buttonLabel(name)
                    other != null -> stringResource(R.string.gamepad_game_hint, buttonLabel(name), other, ps)
                    else -> stringResource(R.string.gamepad_ps_hint, buttonLabel(name), ps)
                },
                subtitle = if (keys.isEmpty()) stringResource(R.string.gamepad_unbound) else keys.joinToString(" · ") { GamepadCapture.keyName(it) },
                enabled = device != null,
                // + adds a second (third...) button for the same job - a back paddle that also presses A.
                trailing = {
                    IconButton(onClick = { adding = true; capturing = name to button }, enabled = device != null) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.gamepad_add_key))
                    }
                },
                onClick = { adding = false; capturing = name to button },
            )
        }
        SettingsDivider()
        SectionHeader(stringResource(R.string.gamepad_combos))
        NoteText(stringResource(R.string.gamepad_combos_desc))
        for ((key, mask) in mapping.combos) {
            val parts = GamepadMapping.nameOf(mask).split('+')
            val labels = ArrayList<String>(parts.size)
            for (part in parts) labels += buttonLabel(part)
            SettingsRow(
                title = GamepadCapture.keyName(key),
                subtitle = labels.joinToString(" + "),
                trailing = {
                    IconButton(onClick = { save(mapping.withoutKey(key)) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.gamepad_combo_delete))
                    }
                },
                onClick = { comboKey = key },
            )
        }
        SettingsRow(
            title = stringResource(R.string.gamepad_combo_add),
            icon = Icons.Outlined.Add,
            enabled = device != null,
            onClick = { comboCapture = true },
        )
        SettingsDivider()
        SectionHeader(stringResource(R.string.gamepad_macro_keys))
        NoteText(stringResource(R.string.gamepad_macro_keys_desc))
        for ((key, id) in mapping.macroKeys) {
            SettingsRow(
                title = GamepadCapture.keyName(key),
                subtitle = macros.firstOrNull { it.id == id }?.name ?: stringResource(R.string.gamepad_macro_missing),
                trailing = {
                    IconButton(onClick = { save(mapping.withoutKey(key)) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.gamepad_combo_delete))
                    }
                },
                onClick = { macroKey = key },
            )
        }
        SettingsRow(
            title = stringResource(R.string.gamepad_macro_add),
            icon = Icons.Outlined.Add,
            enabled = device != null,
            onClick = { if (macros.isEmpty()) onMacros() else macroCapture = true },
        )
        SettingsRow(title = stringResource(R.string.macros_title), subtitle = stringResource(R.string.macros_row_desc), onClick = onMacros)
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.gamepad_reset),
            subtitle = stringResource(R.string.gamepad_reset_desc),
            icon = Icons.Outlined.Refresh,
            onClick = { resetting = true },
        )
    }

    capturing?.let { (name, button) ->
        CaptureDialog(
            label = buttonLabel(name),
            onCaptured = { keyCode ->
                capturing = null
                save(if (adding) mapping.addKey(button, keyCode) else mapping.rebind(button, keyCode))
            },
            onClear = { capturing = null; save(GamepadMapping(mapping.keys.filterValues { it != button })) },
            onDismiss = { capturing = null },
        )
    }

    if (comboCapture) {
        CaptureDialog(
            label = stringResource(R.string.gamepad_combo_key),
            onCaptured = { keyCode -> comboCapture = false; comboKey = keyCode },
            onClear = { comboCapture = false },
            onDismiss = { comboCapture = false },
        )
    }
    comboKey?.let { key ->
        ComboDialog(
            keyName = GamepadCapture.keyName(key),
            initial = mapping.buttonFor(key)?.takeIf { it and GamepadMapping.PAD_BITS.inv() == 0 } ?: 0,
            onSave = { mask ->
                comboKey = null
                save(if (mask == 0) mapping.withoutKey(key) else mapping.addKey(mask, key))
            },
            onDismiss = { comboKey = null },
        )
    }

    if (macroCapture) {
        CaptureDialog(
            label = stringResource(R.string.gamepad_macro_key),
            onCaptured = { keyCode -> macroCapture = false; macroKey = keyCode },
            onClear = { macroCapture = false },
            onDismiss = { macroCapture = false },
        )
    }
    macroKey?.let { key ->
        AlertDialog(
            onDismissRequest = { macroKey = null },
            title = { Text(stringResource(R.string.gamepad_macro_pick, GamepadCapture.keyName(key))) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(macros.size) { i ->
                        val m = macros[i]
                        Column(
                            androidx.compose.ui.Modifier.fillMaxWidth()
                                .clickable { macroKey = null; save(mapping.addKey(GamepadMapping.macroValue(m.id), key)) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge)
                            Text(m.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { macroKey = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (resetting) {
        ConfirmDialog(
            title = stringResource(R.string.gamepad_reset),
            body = stringResource(R.string.gamepad_reset_confirm),
            confirmLabel = stringResource(R.string.reset),
            onDismiss = { resetting = false },
            onConfirm = {
                resetting = false
                mapping = GamepadMapping.DEFAULT
                scope.launch { settings.remove(Settings.Keys.gamepadMapping(deviceKey)) }
            },
        )
    }
}

/** Waits for one press on the pad. Back closes it instead of being captured. */
@Composable
private fun CaptureDialog(label: String, onCaptured: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val captured = remember { mutableStateOf<Int?>(null) }
    DisposableEffect(label) {
        GamepadCapture.onKey = handler@{ event ->
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) return@handler false
            captured.value = event.keyCode
            true
        }
        onDispose { GamepadCapture.onKey = null }
    }
    // Applied outside the key handler so the binding is written on the Compose thread.
    LaunchedEffect(captured.value) { captured.value?.let(onCaptured) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gamepad_press_title, label)) },
        text = { Text(stringResource(R.string.gamepad_press_body), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.gamepad_unbind)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Korean names for the two pseudo-buttons and the directions; the rest are printed as they are. */
@Composable
private fun buttonLabel(name: String): String = when (name) {
    "MENU" -> stringResource(R.string.gamepad_btn_menu)
    "TURBO" -> stringResource(R.string.gamepad_btn_turbo)
    "FAST_FORWARD" -> stringResource(R.string.gamepad_btn_ff)
    "SPEED" -> stringResource(R.string.gamepad_btn_speed)
    "SAVE_STATE" -> stringResource(R.string.gamepad_btn_save)
    "LOAD_STATE" -> stringResource(R.string.gamepad_btn_load)
    "QUICK_SAVE" -> stringResource(R.string.gamepad_btn_quick_save)
    "QUICK_LOAD" -> stringResource(R.string.gamepad_btn_quick_load)
    "REWIND" -> stringResource(R.string.gamepad_btn_rewind)
    "UP" -> stringResource(R.string.gamepad_btn_up)
    "DOWN" -> stringResource(R.string.gamepad_btn_down)
    "LEFT" -> stringResource(R.string.gamepad_btn_left)
    "RIGHT" -> stringResource(R.string.gamepad_btn_right)
    else -> name
}

/** Picks the pad buttons a 조합 key presses together. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ComboDialog(keyName: String, initial: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var mask by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gamepad_combo_title, keyName)) },
        text = {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                for ((name, bit) in GamepadMapping.COMBO_BUTTONS) {
                    FilterChip(
                        selected = mask and bit != 0,
                        onClick = { mask = mask xor bit },
                        label = { Text(buttonLabel(name)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mask) }, enabled = Integer.bitCount(mask) != 1) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The PlayStation button a RetroPad face button ends up as, with or without the position mapping. */
private fun psGlyph(button: Int, positional: Boolean): String? {
    val b = if (positional) GamepadMapping.toPositional(button) else button
    return when (b) {
        com.manggome.oneemu.emu.EmulatorSession.Buttons.B -> "×"
        com.manggome.oneemu.emu.EmulatorSession.Buttons.A -> "○"
        com.manggome.oneemu.emu.EmulatorSession.Buttons.Y -> "□"
        com.manggome.oneemu.emu.EmulatorSession.Buttons.X -> "△"
        else -> null
    }.takeIf { button in FACE }
}

private val FACE = setOf(
    com.manggome.oneemu.emu.EmulatorSession.Buttons.A, com.manggome.oneemu.emu.EmulatorSession.Buttons.B,
    com.manggome.oneemu.emu.EmulatorSession.Buttons.X, com.manggome.oneemu.emu.EmulatorSession.Buttons.Y,
)
