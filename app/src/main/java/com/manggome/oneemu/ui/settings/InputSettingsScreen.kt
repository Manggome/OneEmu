package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import kotlin.math.roundToInt

@Composable
internal fun InputSettingsScreen(onBack: () -> Unit) {
    val opacity = rememberPref(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
    val scale = rememberPref(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
    val vibration = rememberPref(Settings.Keys.padVibration, true)
    val vibrationMs = rememberPref(Settings.Keys.padVibrationMs, Settings.DEFAULT_VIBRATION_MS)
    val hideWithGamepad = rememberPref(Settings.Keys.padHideWithGamepad, true)

    SettingsScaffold(title = stringResource(R.string.settings_input), onBack = onBack) {
        SliderRow(
            title = stringResource(R.string.input_pad_opacity),
            value = opacity.value.coerceIn(0.2f, 1.0f),
            range = 0.2f..1.0f,
            steps = 15,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            onValueChange = { opacity.set(it) },
        )
        SettingsDivider()
        SliderRow(
            title = stringResource(R.string.input_pad_scale),
            value = scale.value.coerceIn(0.7f, 1.5f),
            range = 0.7f..1.5f,
            steps = 15,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            onValueChange = { scale.set(it) },
        )
        SettingsDivider()
        SwitchRow(
            title = stringResource(R.string.input_vibration),
            subtitle = stringResource(R.string.input_vibration_desc),
            checked = vibration.value,
            onCheckedChange = { vibration.set(it) },
            icon = Icons.Outlined.Vibration,
        )
        SliderRow(
            title = stringResource(R.string.input_vibration_ms),
            value = vibrationMs.value.toFloat().coerceIn(5f, 40f),
            range = 5f..40f,
            steps = 6,
            valueLabel = { "${it.roundToInt()} ms" },
            onValueChange = { vibrationMs.set(it.roundToInt()) },
            enabled = vibration.value,
        )
        SettingsDivider()
        SwitchRow(
            title = stringResource(R.string.input_hide_with_gamepad),
            subtitle = stringResource(R.string.input_hide_with_gamepad_desc),
            checked = hideWithGamepad.value,
            onCheckedChange = { hideWithGamepad.set(it) },
            icon = Icons.Outlined.SportsEsports,
        )
        SettingsDivider()
        NoteText(stringResource(R.string.input_layout_note))
    }
}
