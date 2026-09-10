package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.Routes

/** Top-level settings menu: one plain row per category, like My Boy. */
@Composable
internal fun SettingsHomeScreen(nav: NavHostController) {
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = { nav.popBackStack() }) {
        SettingsRow(stringResource(R.string.settings_video), stringResource(R.string.settings_video_desc), Icons.Outlined.Videocam) { nav.navigate(Routes.SETTINGS_VIDEO) }
        SettingsRow(stringResource(R.string.settings_audio), stringResource(R.string.settings_audio_desc), Icons.AutoMirrored.Outlined.VolumeUp) { nav.navigate(Routes.SETTINGS_AUDIO) }
        SettingsRow(stringResource(R.string.settings_input), stringResource(R.string.settings_input_desc), Icons.Outlined.Gamepad) { nav.navigate(Routes.SETTINGS_INPUT) }
        SettingsRow(stringResource(R.string.settings_layouts), stringResource(R.string.settings_layouts_desc), Icons.Outlined.GridView) { nav.navigate(Routes.SETTINGS_LAYOUTS) }
        SettingsRow(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_desc), Icons.Outlined.Palette) { nav.navigate(Routes.SETTINGS_THEME) }
        SettingsRow(stringResource(R.string.settings_cores), stringResource(R.string.settings_cores_desc), Icons.Outlined.Memory) { nav.navigate(Routes.SETTINGS_CORES) }
        SettingsRow(stringResource(R.string.settings_misc), stringResource(R.string.settings_misc_desc), Icons.Outlined.Tune) { nav.navigate(Routes.SETTINGS_MISC) }
        SettingsRow(stringResource(R.string.settings_about), stringResource(R.string.settings_about_desc), Icons.Outlined.Info) { nav.navigate(Routes.SETTINGS_ABOUT) }
    }
}
