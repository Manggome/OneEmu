package com.manggome.oneemu.ui.settings

import com.manggome.oneemu.data.Settings

import androidx.compose.material.icons.outlined.Save

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.common.formatFileSize
import com.manggome.oneemu.util.SaveBackup
import kotlinx.coroutines.launch

/**
 * Where save data lives and how to get a copy of it out. Everything a player would hate to lose
 * sits under one folder, and the backup is a plain zip they can keep wherever they like.
 */
@Composable
internal fun SaveDataSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = OneEmuApp.get()
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<SaveBackup.Stats?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) { stats = SaveBackup.stats(app.dirs, app.db) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SaveBackup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { SaveBackup.export(app.dirs, app.db, it) }
                    ?: error("no stream")
            }
            busy = false
            result.onSuccess { Toast.makeText(context, context.getString(R.string.save_export_done, it.files), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.save_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { SaveBackup.import(app.dirs, app.db, it) }
                    ?: error("no stream")
            }
            busy = false
            refresh++
            result.onSuccess { Toast.makeText(context, context.getString(R.string.save_import_done, it.files, it.cheats), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.save_import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    val autoSave = rememberPref(Settings.Keys.autoSaveState, true)
    SettingsScaffold(title = stringResource(R.string.settings_savedata), onBack = onBack) {
        SwitchRow(
            title = stringResource(R.string.misc_auto_save),
            subtitle = stringResource(R.string.misc_auto_save_desc),
            checked = autoSave.value,
            onCheckedChange = { autoSave.set(it) },
            icon = Icons.Outlined.Save,
        )
        SettingsDivider()
        val s = stats
        SettingsRow(
            title = stringResource(R.string.save_folder),
            subtitle = app.dirs.dataPath,
            icon = Icons.Outlined.Folder,
        )
        SettingsRow(
            title = stringResource(R.string.save_copy_path),
            icon = Icons.Outlined.ContentCopy,
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("OneEmu", app.dirs.dataPath))
                Toast.makeText(context, R.string.cores_path_copied, Toast.LENGTH_SHORT).show()
            },
        )
        NoteText(
            if (s == null) stringResource(R.string.save_counting)
            else stringResource(R.string.save_summary, s.files, formatFileSize(s.bytes), s.cheats),
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.save_export),
            subtitle = stringResource(R.string.save_export_desc),
            icon = Icons.Outlined.FileUpload,
            enabled = !busy,
            onClick = { exportLauncher.launch(SaveBackup.suggestedName()) },
        )
        SettingsRow(
            title = stringResource(R.string.save_import),
            subtitle = stringResource(R.string.save_import_desc),
            icon = Icons.Outlined.FileDownload,
            enabled = !busy,
            onClick = { importLauncher.launch(arrayOf(SaveBackup.MIME, "application/octet-stream", "*/*")) },
        )
        SettingsDivider()
        NoteText(stringResource(R.string.save_note))
    }
}
