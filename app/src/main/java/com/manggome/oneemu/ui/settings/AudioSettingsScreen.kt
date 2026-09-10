package com.manggome.oneemu.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings

@Composable
internal fun AudioSettingsScreen(onBack: () -> Unit) {
    val enabled = rememberPref(Settings.Keys.audioEnabled, true)
    SettingsScaffold(title = stringResource(R.string.settings_audio), onBack = onBack) {
        SwitchRow(
            title = stringResource(R.string.audio_enabled),
            subtitle = stringResource(R.string.audio_enabled_desc),
            checked = enabled.value,
            onCheckedChange = { enabled.set(it) },
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
        )
    }
}
