package com.manggome.oneemu.ui.settings

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.input.GamepadDevices
import com.manggome.oneemu.emu.input.StickDpad
import com.manggome.oneemu.emu.input.GamepadMapping
import com.manggome.oneemu.emu.input.PadDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The attached controllers, which player each one is, and the way into its button mapping. The list
 * updates itself as pads connect and disconnect, so a user can pair a pad with this screen open and
 * watch it appear.
 */
@Composable
internal fun GamepadSettingsScreen(onBack: () -> Unit, onMapping: (deviceKey: String) -> Unit, onMacros: () -> Unit = {}) {
    val devices = rememberPadDevices()
    val scope = rememberCoroutineScope()
    val settings = OneEmuApp.get().settings
    val auto = stringResource(R.string.gamepad_player_auto)
    val playerLabels = listOf(auto) + (1..GamepadDevices.MAX_PLAYERS).map { stringResource(R.string.gamepad_player_n, it) }

    SettingsScaffold(title = stringResource(R.string.gamepad_title), onBack = onBack) {
        // Before the device list: it applies with or without a pad connected right now.
        val stickDpad = rememberPref(StickDpad.everywhere, false)
        SwitchRow(
            title = stringResource(R.string.gamepad_stick_dpad),
            subtitle = stringResource(R.string.gamepad_stick_dpad_desc),
            checked = stickDpad.value,
            onCheckedChange = stickDpad.set,
        )
        val layout by remember { GamepadMapping.observeLayout(settings) }.collectAsState(GamepadMapping.LAYOUT_AUTO)
        val layoutNames = listOf(
            stringResource(R.string.gamepad_layout_auto),
            stringResource(R.string.gamepad_layout_position),
            stringResource(R.string.gamepad_layout_letter),
        )
        val layoutDescs = listOf(
            stringResource(R.string.gamepad_layout_auto_desc),
            stringResource(R.string.gamepad_layout_position_desc),
            stringResource(R.string.gamepad_layout_letter_desc),
        )
        val layoutIndex = GamepadMapping.LAYOUTS.indexOf(layout).coerceAtLeast(0)
        ChoiceRow(
            title = stringResource(R.string.gamepad_layout),
            subtitle = layoutNames[layoutIndex] + " — " + layoutDescs[layoutIndex],
            options = layoutNames,
            selectedIndex = layoutIndex,
            onSelect = { i -> scope.launch { settings.set(GamepadMapping.FACE_LAYOUT, GamepadMapping.LAYOUTS[i]) } },
            icon = Icons.Outlined.SportsEsports,
        )
        SettingsRow(
            title = stringResource(R.string.macros_title),
            subtitle = stringResource(R.string.macros_row_desc),
            icon = Icons.Outlined.Keyboard,
            onClick = onMacros,
        )
        SettingsDivider()
        if (devices.isEmpty()) {
            NoteText(stringResource(R.string.gamepad_none))
            return@SettingsScaffold
        }
        devices.forEachIndexed { index, device ->
            if (index > 0) SettingsDivider()
            SectionHeader(device.name)
            SettingsRow(
                title = stringResource(R.string.gamepad_current_player, device.port + 1),
                subtitle = stringResource(R.string.gamepad_current_player_desc),
                icon = Icons.Outlined.SportsEsports,
            )
            PlayerChoiceRow(device, playerLabels) { choice ->
                scope.launch {
                    if (choice == GamepadDevices.AUTO) settings.remove(Settings.Keys.gamepadPort(device.key))
                    else settings.set(Settings.Keys.gamepadPort(device.key), choice)
                }
            }
            SettingsRow(
                title = stringResource(R.string.gamepad_buttons),
                subtitle = stringResource(R.string.gamepad_buttons_desc),
                onClick = { onMapping(device.key) },
            )
        }
        SettingsDivider()
        NoteText(stringResource(R.string.gamepad_note))
    }
}

/** The pinned player for one pad, read straight from the preference it is stored under. */
@Composable
private fun PlayerChoiceRow(device: PadDevice, labels: List<String>, onSelect: (Int) -> Unit) {
    val pinned = rememberPref(Settings.Keys.gamepadPort(device.key), GamepadDevices.AUTO)
    ChoiceRow(
        title = stringResource(R.string.gamepad_player),
        options = labels,
        selectedIndex = pinned.value.coerceIn(0, GamepadDevices.MAX_PLAYERS),
        onSelect = onSelect,
    )
}

/** Attached pads with their player, refreshed whenever a device comes or goes. */
@Composable
internal fun rememberPadDevices(): List<PadDevice> {
    val context = LocalContext.current
    val settings = OneEmuApp.get().settings
    var devices by remember { mutableStateOf(emptyList<PadDevice>()) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        fun refresh() {
            scope.launch {
                devices = GamepadDevices.assign(GamepadDevices.connected(), settings.gamepadPorts.first())
            }
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        refresh()
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    // A pinned player changes the assignment without any device event, so follow the preference too.
    val ports by settings.gamepadPorts.collectAsState(initial = emptyMap())
    LaunchedEffect(ports) { devices = GamepadDevices.assign(GamepadDevices.connected(), ports) }
    return devices
}

/** The mapping saved for [deviceKey], or the defaults when the user has not changed anything. */
internal suspend fun loadMapping(deviceKey: String): GamepadMapping =
    GamepadMapping.parse(OneEmuApp.get().settings.get(Settings.Keys.gamepadMapping(deviceKey), ""))
