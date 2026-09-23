package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.ui.Routes

/** Top-level settings menu: one plain row per category, like My Boy. */
@Composable
internal fun SettingsHomeScreen(nav: NavHostController) {
    // The pad someone edited last, so the usual trip - 설정, 패드 레이아웃, pick the same system again -
    // is one tap instead of three. The list stays where it is for editing any other pad.
    val lastProfile by OneEmuApp.get().settings
        .observe(Settings.Keys.lastPadProfile, "")
        .collectAsState("")
    val recent = PadProfile.fromKey(lastProfile)

    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = { nav.popBackStack() }) {
        // Grouped by what someone comes here to do: the game itself, how it is controlled, the app.
        SectionHeader(stringResource(R.string.settings_group_game))
        SettingsRow(stringResource(R.string.settings_video), stringResource(R.string.settings_video_desc), Icons.Outlined.Videocam) { nav.navigate(Routes.SETTINGS_VIDEO) }
        SettingsRow(stringResource(R.string.settings_cores), stringResource(R.string.settings_cores_desc), Icons.Outlined.Memory) { nav.navigate(Routes.SETTINGS_CORES) }
        SettingsRow(stringResource(R.string.settings_savedata), stringResource(R.string.settings_savedata_desc), Icons.Outlined.Save) { nav.navigate(Routes.SETTINGS_SAVEDATA) }
        SettingsDivider()

        SectionHeader(stringResource(R.string.settings_group_controls))
        SettingsRow(stringResource(R.string.settings_layouts), stringResource(R.string.settings_layouts_desc), Icons.Outlined.GridView) { nav.navigate(Routes.SETTINGS_LAYOUTS) }
        recent?.let { profile ->
            SettingsRow(
                stringResource(R.string.settings_layout_recent, profile.displayName),
                stringResource(R.string.settings_layout_recent_desc),
                Icons.Outlined.Edit,
            ) { nav.navigate(Routes.layoutEditor(profile.key)) }
        }
        SettingsRow(stringResource(R.string.settings_input), stringResource(R.string.settings_input_desc), Icons.Outlined.TouchApp) { nav.navigate(Routes.SETTINGS_INPUT) }
        SettingsRow(stringResource(R.string.settings_gamepad), stringResource(R.string.settings_gamepad_desc), Icons.Outlined.SportsEsports) { nav.navigate(Routes.SETTINGS_GAMEPAD) }
        SettingsRow(stringResource(R.string.settings_misc), stringResource(R.string.settings_misc_desc), Icons.Outlined.Bolt) { nav.navigate(Routes.SETTINGS_MISC) }
        SettingsDivider()

        SectionHeader(stringResource(R.string.settings_group_app))
        SettingsRow(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_desc), Icons.Outlined.Palette) { nav.navigate(Routes.SETTINGS_THEME) }
        SettingsRow(stringResource(R.string.settings_about), stringResource(R.string.settings_about_desc), Icons.Outlined.Info) { nav.navigate(Routes.SETTINGS_ABOUT) }
    }
}
