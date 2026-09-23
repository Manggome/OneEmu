package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
internal fun GamepadMappingScreen(deviceKey: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = OneEmuApp.get().settings
    var mapping by remember { mutableStateOf(GamepadMapping.DEFAULT) }
    var capturing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // True when the press should be added to the button's keys instead of replacing them.
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    val devices = rememberPadDevices()
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
            SettingsRow(
                title = buttonLabel(name),
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
    "UP" -> stringResource(R.string.gamepad_btn_up)
    "DOWN" -> stringResource(R.string.gamepad_btn_down)
    "LEFT" -> stringResource(R.string.gamepad_btn_left)
    "RIGHT" -> stringResource(R.string.gamepad_btn_right)
    else -> name
}
