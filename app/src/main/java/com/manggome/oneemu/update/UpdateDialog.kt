package com.manggome.oneemu.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manggome.oneemu.R
import kotlin.math.roundToInt

/**
 * Drop-in for MainActivity: checks for updates once per launch (respecting the 6 hour interval and
 * the "시작할 때 업데이트 확인" preference) and shows [UpdateDialog] when a non-skipped update exists.
 * Network failures are silent.
 */
@Composable
fun UpdatePrompt() {
    val vm: UpdateViewModel = viewModel(key = "update-prompt")
    LaunchedEffect(Unit) { vm.checkAutomatically() }
    UpdateDialog(vm, showTransientResults = false)
}

/**
 * Renders the update dialog for whatever [vm] is doing. With [showTransientResults] the manual-check
 * outcomes (최신 버전입니다 / 확인 실패) are shown as small dialogs too; the startup prompt keeps quiet.
 */
@Composable
fun UpdateDialog(vm: UpdateViewModel, showTransientResults: Boolean = true) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val needsPermission by vm.needsInstallPermission.collectAsState()

    when (val s = state) {
        is UpdateViewModel.UiState.Available -> UpdateBody(s.info, progress = null, file = false, vm = vm)
        is UpdateViewModel.UiState.Downloading -> UpdateBody(s.info, progress = s.progress, file = false, vm = vm)
        is UpdateViewModel.UiState.Downloaded -> UpdateBody(s.info, progress = 1f, file = true, vm = vm)
        is UpdateViewModel.UiState.Error -> {
            if (s.info != null) {
                AlertDialog(
                    onDismissRequest = vm::later,
                    title = { Text(stringResource(R.string.update_title, s.info.version)) },
                    text = { Text(stringResource(R.string.update_failed, s.message)) },
                    confirmButton = { TextButton(onClick = vm::download) { Text(stringResource(R.string.retry)) } },
                    dismissButton = { TextButton(onClick = vm::later) { Text(stringResource(R.string.update_action_later)) } },
                )
            } else if (showTransientResults) {
                AlertDialog(
                    onDismissRequest = vm::acknowledge,
                    title = { Text(stringResource(R.string.about_check_update)) },
                    text = { Text(stringResource(R.string.about_check_failed, s.message)) },
                    confirmButton = { TextButton(onClick = vm::acknowledge) { Text(stringResource(R.string.ok)) } },
                )
            }
        }
        UpdateViewModel.UiState.UpToDate -> if (showTransientResults) {
            AlertDialog(
                onDismissRequest = vm::acknowledge,
                title = { Text(stringResource(R.string.about_check_update)) },
                text = { Text(stringResource(R.string.about_up_to_date)) },
                confirmButton = { TextButton(onClick = vm::acknowledge) { Text(stringResource(R.string.ok)) } },
            )
        }
        UpdateViewModel.UiState.Idle, UpdateViewModel.UiState.Checking -> Unit
    }

    if (needsPermission) {
        AlertDialog(
            onDismissRequest = vm::dismissPermissionPrompt,
            title = { Text(stringResource(R.string.update_perm_title)) },
            text = { Text(stringResource(R.string.update_perm_body)) },
            confirmButton = { TextButton(onClick = { vm.openInstallPermission(context) }) { Text(stringResource(R.string.update_perm_open)) } },
            dismissButton = { TextButton(onClick = vm::dismissPermissionPrompt) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun UpdateBody(info: UpdateInfo, progress: Float?, file: Boolean, vm: UpdateViewModel) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (progress == null) vm.later() },
        title = { Text(stringResource(R.string.update_title, info.version)) },
        text = {
            Column {
                if (info.apkSize > 0) {
                    Text(stringResource(R.string.update_size, formatSize(info.apkSize)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(info.notes.ifBlank { stringResource(R.string.update_notes_empty) }, style = MaterialTheme.typography.bodyMedium)
                }
                if (progress != null && !file) {
                    Spacer(Modifier.height(16.dp))
                    if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.update_downloading, (progress.coerceAtLeast(0f) * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
                }
                if (file) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.update_downloaded), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (info.htmlUrl.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.update_release_page)) }
                }
            }
        },
        confirmButton = {
            when {
                file -> TextButton(onClick = { vm.install(context) }) { Text(stringResource(R.string.update_action_install)) }
                progress == null -> TextButton(onClick = vm::download) { Text(stringResource(R.string.update_action_update)) }
                else -> Unit
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = vm::later) { Text(stringResource(R.string.update_action_later)) }
                if (progress == null) {
                    TextButton(onClick = vm::skip) { Text(stringResource(R.string.update_action_skip), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
    )
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
