package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import kotlin.math.roundToInt

@Composable
internal fun VideoSettingsScreen(onBack: () -> Unit) {
    val linear = rememberPref(Settings.Keys.videoLinearFilter, false)
    val aspect = rememberPref(Settings.Keys.videoAspect, 0)
    val fps = rememberPref(Settings.Keys.showFps, false)
    val screenFilter = rememberPref(Settings.Keys.videoFilter, Settings.FILTER_NONE)
    val strength = rememberPref(Settings.Keys.videoFilterStrength, Settings.DEFAULT_FILTER_STRENGTH)

    SettingsScaffold(title = stringResource(R.string.settings_video), onBack = onBack) {
        ChoiceRow(
            title = stringResource(R.string.video_filter),
            options = listOf(stringResource(R.string.video_filter_nearest), stringResource(R.string.video_filter_linear)),
            selectedIndex = if (linear.value) 1 else 0,
            onSelect = { linear.set(it == 1) },
            icon = Icons.Outlined.BlurOn,
        )
        SettingsDivider()
        ChoiceRow(
            title = stringResource(R.string.video_aspect),
            options = listOf(
                stringResource(R.string.video_aspect_core),
                stringResource(R.string.video_aspect_stretch),
                stringResource(R.string.video_aspect_integer),
                stringResource(R.string.video_aspect_square),
            ),
            selectedIndex = aspect.value.coerceIn(0, 3),
            onSelect = { aspect.set(it) },
            icon = Icons.Outlined.AspectRatio,
        )
        SettingsDivider()
        ChoiceRow(
            title = stringResource(R.string.video_screen_filter),
            subtitle = stringResource(R.string.video_screen_filter_desc),
            options = listOf(
                stringResource(R.string.qs_screen_filter_none),
                stringResource(R.string.qs_screen_filter_scanline),
                stringResource(R.string.qs_screen_filter_crt),
                stringResource(R.string.qs_screen_filter_lcd),
            ),
            selectedIndex = screenFilter.value.coerceIn(0, 3),
            onSelect = { screenFilter.set(it) },
            icon = Icons.Outlined.Tv,
        )
        if (screenFilter.value != Settings.FILTER_NONE) {
            SliderRow(
                title = stringResource(R.string.qs_screen_filter_strength),
                value = strength.value.coerceIn(0.1f, 1f),
                range = 0.1f..1f,
                steps = 8,
                valueLabel = { "${(it * 100).roundToInt()}%" },
                onValueChange = { strength.set(it) },
            )
        }
        SettingsDivider()
        SwitchRow(
            title = stringResource(R.string.video_show_fps),
            subtitle = stringResource(R.string.video_show_fps_desc),
            checked = fps.value,
            onCheckedChange = { fps.set(it) },
            icon = Icons.Outlined.Speed,
        )
    }
}
