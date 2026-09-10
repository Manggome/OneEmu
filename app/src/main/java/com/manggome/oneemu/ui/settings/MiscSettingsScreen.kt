package com.manggome.oneemu.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FF_SPEEDS = listOf(2, 3, 4, 0) // 0 = unlimited

@Composable
internal fun MiscSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dirs = OneEmuApp.get().dirs
    val updateOnStart = rememberPref(Settings.Keys.updateCheckOnStart, true)
    val autoSave = rememberPref(Settings.Keys.autoSaveState, true)
    val ffSpeed = rememberPref(Settings.Keys.fastForwardSpeed, Settings.DEFAULT_FF_SPEED)
    val unlimited = stringResource(R.string.misc_ff_unlimited)

    SettingsScaffold(title = stringResource(R.string.settings_misc), onBack = onBack) {
        SwitchRow(
            title = stringResource(R.string.misc_update_on_start),
            subtitle = stringResource(R.string.misc_update_on_start_desc),
            checked = updateOnStart.value,
            onCheckedChange = { updateOnStart.set(it) },
            icon = Icons.Outlined.SystemUpdate,
        )
        SettingsDivider()
        SwitchRow(
            title = stringResource(R.string.misc_auto_save),
            subtitle = stringResource(R.string.misc_auto_save_desc),
            checked = autoSave.value,
            onCheckedChange = { autoSave.set(it) },
            icon = Icons.Outlined.Save,
        )
        SettingsDivider()
        ChoiceRow(
            title = stringResource(R.string.misc_ff_speed),
            options = FF_SPEEDS.map { if (it == 0) unlimited else "${it}×" },
            selectedIndex = FF_SPEEDS.indexOf(ffSpeed.value).let { if (it < 0) FF_SPEEDS.indexOf(Settings.DEFAULT_FF_SPEED) else it },
            onSelect = { ffSpeed.set(FF_SPEEDS[it]) },
            icon = Icons.Outlined.FastForward,
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.misc_clear_temp),
            subtitle = stringResource(R.string.misc_clear_temp_desc),
            icon = Icons.Outlined.CleaningServices,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { dirs.clearTemp() }
                    Toast.makeText(context, R.string.misc_clear_temp_done, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
