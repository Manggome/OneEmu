package com.manggome.oneemu.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.core.DownloadState
import com.manggome.oneemu.core.ManifestState
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "이 게임은 <코어> 코어가 필요합니다 (약 N MB). 지금 내려받을까요?" — asks to install a `distribution: download`
 * core, shows the download progress in place and calls [onInstalled] once the library is on disk (callers
 * then launch the game). The download itself runs in the app scope, so dismissing the dialog does not
 * cancel it. A missing `cores` release or network is explained instead of failing silently.
 */
@Composable
fun CoreDownloadDialog(core: CoreInfo, onDismiss: () -> Unit, onInstalled: () -> Unit) {
    val app = OneEmuApp.get()
    val manager = remember { app.coreDownloads }
    val scope = rememberCoroutineScope()
    val manifest by manager.manifest.collectAsStateWithLifecycle()
    val states by manager.state.collectAsStateWithLifecycle()
    val dl = states[core.id] ?: DownloadState.Idle
    val entry = (manifest as? ManifestState.Loaded)?.manifest?.entry(core.id)

    LaunchedEffect(core.id) {
        if (app.cores.isAvailable(core)) { onInstalled(); return@LaunchedEffect }
        if (manifest !is ManifestState.Loaded) manager.refreshManifest()
    }
    LaunchedEffect(dl) {
        if (dl is DownloadState.Installed) { delay(500); onInstalled() }
    }

    val sizeText = entry?.takeIf { it.size > 0 }?.let { stringResource(R.string.core_dl_size, formatFileSize(it.size)) }
    val canStart = entry != null && !dl.isBusy && dl !is DownloadState.Installed

    AlertDialog(
        onDismissRequest = { if (!dl.isBusy) onDismiss() },
        title = { Text(stringResource(R.string.core_dl_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (dl) {
                    is DownloadState.Installed -> Text(stringResource(R.string.core_dl_dialog_installed, core.displayName))
                    is DownloadState.Downloading, DownloadState.Extracting -> {
                        Text(if (sizeText != null) stringResource(R.string.core_dl_dialog_need, core.displayName, sizeText) else stringResource(R.string.core_dl_dialog_need_nosize, core.displayName))
                        Spacer(Modifier.height(12.dp))
                        DownloadProgress(dl)
                    }
                    is DownloadState.Failed -> {
                        Text(if (sizeText != null) stringResource(R.string.core_dl_dialog_need, core.displayName, sizeText) else stringResource(R.string.core_dl_dialog_need_nosize, core.displayName))
                        Spacer(Modifier.height(8.dp))
                        Text(dl.message, color = OneEmuColors.Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                    DownloadState.Idle -> when (val m = manifest) {
                        is ManifestState.Loaded -> {
                            if (entry != null) {
                                Text(if (sizeText != null) stringResource(R.string.core_dl_dialog_need, core.displayName, sizeText) else stringResource(R.string.core_dl_dialog_need_nosize, core.displayName))
                            } else {
                                Text(stringResource(R.string.core_dl_err_not_in_manifest, core.displayName), color = OneEmuColors.Danger)
                            }
                        }
                        is ManifestState.Unavailable -> {
                            Text(stringResource(R.string.core_dl_dialog_need_nosize, core.displayName))
                            Spacer(Modifier.height(8.dp))
                            Text(m.message, color = OneEmuColors.Danger, style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            Text(stringResource(R.string.core_dl_dialog_need_nosize, core.displayName))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.core_dl_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                dl is DownloadState.Installed -> Unit
                dl.isBusy -> Unit
                manifest is ManifestState.Unavailable -> TextButton(onClick = { scope.launch { manager.refreshManifest(force = true) } }) { Text(stringResource(R.string.retry)) }
                canStart -> TextButton(onClick = { manager.download(core.id) }) {
                    Text(stringResource(if (dl is DownloadState.Failed) R.string.core_dl_action_retry else R.string.core_dl_action_download))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(if (dl.isBusy) R.string.close else R.string.cancel)) }
        },
    )
}

/** Progress bar + "내려받는 중… 45% (30.1 MB / 68.0 MB)" / "설치 중…" for a running core download. */
@Composable
fun DownloadProgress(dl: DownloadState) {
    when (dl) {
        is DownloadState.Downloading -> {
            if (dl.progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    dl.bytes == 0L -> stringResource(R.string.core_dl_connecting)
                    dl.total > 0 -> stringResource(R.string.core_dl_progress, (dl.progress * 100).toInt(), formatFileSize(dl.bytes), formatFileSize(dl.total))
                    else -> stringResource(R.string.core_dl_progress_unknown, formatFileSize(dl.bytes))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        DownloadState.Extracting -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.core_dl_extracting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Unit
    }
}
