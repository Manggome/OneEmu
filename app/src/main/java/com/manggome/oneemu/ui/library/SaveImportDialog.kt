package com.manggome.oneemu.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.SaveImporter
import com.manggome.oneemu.library.SaveImporter.Plan
import com.manggome.oneemu.library.SaveImporter.Problem
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 세이브 가져오기: reads the picked file, says what will happen (which card or file it goes to, a region
 * mismatch, why a format cannot be used), and puts it in place once confirmed.
 */
@Composable
fun SaveImportDialog(game: GameEntity, uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = OneEmuApp.get()
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var failed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<SaveImporter.Applied?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > SaveImporter.MAX_BYTES) break
                    }
                    buf.toByteArray()
                } ?: error("unreadable")
                val system = SystemId.fromId(game.system)
                val coreId = game.coreId ?: system?.let { app.cores.defaultCoreFor(it)?.id }
                val target = SaveImporter.Target(system, game.path, coreId, app.dirs.saves(game.system))
                plan = SaveImporter.analyze(target, fileName, bytes)
            }.onFailure { failed = true }
        }
    }

    fun apply(relabel: Boolean) {
        val p = plan ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SaveImporter.apply(p, File(app.dirs.saveRoot("saves"), "import-backup"), relabel) }.getOrNull()
            }
            busy = false
            if (result == null) failed = true else done = result
        }
    }

    val p = plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_import_title)) },
        text = {
            Column {
                Text(fileName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                val d = done
                when {
                    d != null -> {
                        Text(stringResource(R.string.save_import_ok, d.written.absolutePath))
                        if (d.backup != null) {
                            Text(
                                stringResource(R.string.save_import_backup, d.backup.absolutePath),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    failed -> Text(stringResource(R.string.save_import_io_failed))
                    p == null || busy -> CircularProgressIndicator()
                    p is Plan.Unsupported -> Text(problemText(p))
                    else -> {
                        Text(planText(p, game.title))
                        if (p is Plan.GameCube && p.regionOnlyMismatch) {
                            Text(
                                stringResource(R.string.save_import_region, p.saveCode, p.gameCode.orEmpty()),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.save_import_close_game),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val ready = p != null && p !is Plan.Unsupported && done == null && !failed && !busy
            when {
                ready && p is Plan.GameCube && p.regionOnlyMismatch ->
                    TextButton(onClick = { apply(relabel = true) }) { Text(stringResource(R.string.save_import_relabel)) }
                ready -> TextButton(onClick = { apply(relabel = false) }) { Text(stringResource(R.string.save_import_apply)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            val ready = p != null && p !is Plan.Unsupported && done == null && !failed && !busy
            when {
                ready && p is Plan.GameCube && p.regionOnlyMismatch ->
                    TextButton(onClick = { apply(relabel = false) }) { Text(stringResource(R.string.save_import_as_is)) }
                ready -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                else -> {}
            }
        },
    )
}

@Composable
private fun planText(p: Plan, title: String): String = when (p) {
    is Plan.Battery -> stringResource(R.string.save_import_plan_battery, title, p.target.name)
    is Plan.Ps1Card -> stringResource(R.string.save_import_plan_ps1, title)
    is Plan.GameCube -> stringResource(R.string.save_import_plan_gc, p.saveName, p.saveCode, regionName(p.regionDir))
    is Plan.Folder -> if (p.ps2) stringResource(R.string.save_import_plan_ps2, p.folderName, p.files.size)
    else stringResource(R.string.save_import_plan_psp, p.folderName, p.files.size)
    is Plan.Unsupported -> ""
}

@Composable
private fun regionName(dir: String): String = when (dir) {
    "USA" -> stringResource(R.string.save_import_region_usa)
    "JAP" -> stringResource(R.string.save_import_region_jap)
    else -> stringResource(R.string.save_import_region_eur)
}

@Composable
private fun problemText(p: Plan.Unsupported): String = when (p.problem) {
    Problem.UNKNOWN_FORMAT -> stringResource(R.string.save_import_err_format)
    Problem.SYSTEM_UNSUPPORTED -> stringResource(R.string.save_import_err_system)
    Problem.PS2_CBS -> stringResource(R.string.save_import_err_cbs)
    Problem.PS2_OTHER_CORE -> stringResource(R.string.save_import_err_ps2_core, p.detail)
    Problem.PS1_SINGLE_SAVE -> stringResource(R.string.save_import_err_ps1_single)
    Problem.OTHER_GAME -> stringResource(R.string.save_import_err_other_game, p.detail)
    Problem.NO_SAVE_IN_ZIP -> stringResource(R.string.save_import_err_zip)
    Problem.TOO_LARGE -> stringResource(R.string.save_import_err_large)
}
