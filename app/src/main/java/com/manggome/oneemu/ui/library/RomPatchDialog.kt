package com.manggome.oneemu.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.RomPatcher
import com.manggome.oneemu.library.RomPatcher.Problem
import com.manggome.oneemu.ui.common.formatFileSize
import java.io.File

/**
 * 패치 적용: progress while [LibraryViewModel.startPatch] runs, then where the patched copy went (with a
 * button to play it) or, in plain words, why the patch could not be applied.
 */
@Composable
fun RomPatchDialog(state: PatchState, onDismiss: () -> Unit, onPlay: (GameEntity) -> Unit) {
    val working = state is PatchState.Working
    AlertDialog(
        // Not dismissible while writing: the result would arrive with nothing left to show it.
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.patch_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Small(stringResource(R.string.patch_rom, File(state.original.path).name))
                Small(stringResource(R.string.patch_file, state.patchName))
                Spacer(Modifier.size(12.dp))
                when (state) {
                    is PatchState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.patch_working))
                    }
                    is PatchState.Done -> {
                        val game = state.game
                        if (game != null) Text(stringResource(R.string.patch_done, game.title))
                        else Text(stringResource(R.string.patch_done_unlisted))
                        Spacer(Modifier.size(8.dp))
                        PathText(state.file.absolutePath)
                        if (state.inFallback) Note(stringResource(R.string.patch_done_fallback))
                        Note(stringResource(R.string.patch_done_saves))
                        if (state.format == RomPatcher.Format.IPS) Note(stringResource(R.string.patch_done_ips))
                    }
                    is PatchState.Failed -> Text(problemText(state), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                working -> Unit
                state is PatchState.Done && state.game != null ->
                    TextButton(onClick = { onDismiss(); onPlay(state.game) }) { Text(stringResource(R.string.patch_play)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (state is PatchState.Done && state.game != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
private fun Small(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun problemText(s: PatchState.Failed): String = when (s.problem) {
    Problem.UNKNOWN_FORMAT -> stringResource(R.string.patch_err_unknown)
    Problem.XDELTA -> stringResource(R.string.patch_err_xdelta)
    Problem.PPF -> stringResource(R.string.patch_err_ppf)
    Problem.CORRUPT -> stringResource(R.string.patch_err_corrupt)
    Problem.WRONG_SOURCE -> stringResource(R.string.patch_err_wrong_source, s.expected.orEmpty(), s.actual.orEmpty())
    Problem.ALREADY_PATCHED -> stringResource(R.string.patch_err_already)
    Problem.BAD_RESULT -> stringResource(R.string.patch_err_bad_result)
    Problem.SYSTEM_UNSUPPORTED -> stringResource(R.string.patch_err_system)
    Problem.CONTAINER_UNSUPPORTED -> stringResource(R.string.patch_err_container, s.expected.orEmpty())
    Problem.ZIP_NO_ROM -> stringResource(R.string.patch_err_zip_no_rom)
    Problem.ZIP_MANY_ROMS -> stringResource(R.string.patch_err_zip_many, s.expected.orEmpty())
    Problem.TOO_LARGE -> stringResource(R.string.patch_err_too_large)
    Problem.NO_SPACE -> stringResource(R.string.patch_err_no_space, formatFileSize(s.expected?.toLongOrNull() ?: 0L))
    Problem.MISSING_ROM -> stringResource(R.string.patch_err_missing)
    Problem.IO -> stringResource(R.string.patch_err_io)
}
