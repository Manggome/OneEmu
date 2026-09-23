package com.manggome.oneemu.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manggome.oneemu.BuildConfig
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.update.UpdateDialog
import com.manggome.oneemu.update.UpdateViewModel

private const val GITHUB_URL = "https://github.com/" + BuildConfig.GITHUB_REPO

@Composable
internal fun AboutSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cores = OneEmuApp.get().cores.cores
    val updateVm: UpdateViewModel = viewModel(key = "update-about")
    val updateState by updateVm.state.collectAsState()
    val checking = updateState is UpdateViewModel.UiState.Checking
    val updateOnStart = rememberPref(Settings.Keys.updateCheckOnStart, true)

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack) {
        SettingsRow(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            icon = Icons.Outlined.Info,
        )
        SettingsRow(
            title = stringResource(R.string.about_check_update),
            subtitle = if (checking) stringResource(R.string.about_checking) else null,
            icon = Icons.Outlined.SystemUpdate,
            enabled = !checking,
            onClick = { updateVm.checkNow() },
            trailing = if (checking) ({ CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }) else null,
        )
        SwitchRow(
            title = stringResource(R.string.misc_update_on_start),
            subtitle = stringResource(R.string.misc_update_on_start_desc),
            checked = updateOnStart.value,
            onCheckedChange = { updateOnStart.set(it) },
            icon = Icons.Outlined.Autorenew,
        )
        SettingsRow(
            title = stringResource(R.string.about_github),
            subtitle = GITHUB_URL,
            icon = Icons.Outlined.Code,
            onClick = { open(GITHUB_URL) },
        )
        SettingsRow(
            title = stringResource(R.string.about_copy_log),
            subtitle = stringResource(R.string.about_copy_log_desc),
            icon = Icons.Outlined.BugReport,
            onClick = {
                scope.launch {
                    val text = com.manggome.oneemu.emu.CrashMarker.buildLogReport(context, null, null, null)
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("OneEmu log", text))
                    android.widget.Toast.makeText(context, R.string.log_copied, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
        SettingsRow(
            title = stringResource(R.string.misc_clear_temp),
            subtitle = stringResource(R.string.misc_clear_temp_desc),
            icon = Icons.Outlined.CleaningServices,
            onClick = {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OneEmuApp.get().dirs.clearTemp() }
                    android.widget.Toast.makeText(context, R.string.misc_clear_temp_done, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (BuildConfig.DEBUG) {
            NoteText(stringResource(R.string.about_debug_note))
        }
        SettingsDivider()

        SectionHeader(stringResource(R.string.about_license_title))
        SettingsRow(
            title = stringResource(R.string.about_license_app),
            subtitle = stringResource(R.string.about_license_desc),
            icon = Icons.Outlined.Gavel,
            onClick = { open("$GITHUB_URL/blob/main/LICENSE") },
        )
        NoteText(stringResource(R.string.about_no_rom_note))
        SettingsDivider()

        SectionHeader(stringResource(R.string.about_cores_title))
        cores.forEach { core ->
            val commit = core.sourceCommit.take(7)
            val parts = buildList {
                if (core.license.isNotBlank()) add(core.license)
                if (commit.isNotBlank()) add(stringResource(R.string.about_core_commit, commit))
                if (core.sourceRepo.isNotBlank()) add(core.sourceRepo.removePrefix("https://"))
            }
            SettingsRow(
                title = core.displayName,
                subtitle = parts.joinToString(" · "),
                icon = Icons.Outlined.Memory,
                onClick = if (core.sourceRepo.isNotBlank()) ({ open(core.sourceRepo) }) else null,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    UpdateDialog(updateVm, showTransientResults = true)
}
