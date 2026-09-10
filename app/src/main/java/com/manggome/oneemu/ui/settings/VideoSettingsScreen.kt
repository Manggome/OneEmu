package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings

@Composable
internal fun VideoSettingsScreen(onBack: () -> Unit) {
    val linear = rememberPref(Settings.Keys.videoLinearFilter, false)
    val aspect = rememberPref(Settings.Keys.videoAspect, 0)
    val fps = rememberPref(Settings.Keys.showFps, false)

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
        SwitchRow(
            title = stringResource(R.string.video_show_fps),
            subtitle = stringResource(R.string.video_show_fps_desc),
            checked = fps.value,
            onCheckedChange = { fps.set(it) },
            icon = Icons.Outlined.Speed,
        )
    }
}
