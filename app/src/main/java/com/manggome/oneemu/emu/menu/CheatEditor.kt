package com.manggome.oneemu.emu.menu

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.data.db.CheatEntity
import com.manggome.oneemu.emu.CheatCodes
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Human-readable code format accepted by [core]'s `retro_cheat_set`, shown in the add/edit dialog. */
fun cheatFormatHint(context: Context, core: CoreInfo?): String = when (core?.id) {
    "mgba" -> context.getString(R.string.cheat_hint_mgba)
    "fceumm" -> context.getString(R.string.cheat_hint_fceumm)
    "melondsds" -> context.getString(R.string.cheat_hint_melondsds)
    "play", "mame2003plus" -> context.getString(R.string.cheat_hint_unsupported)
    else -> context.getString(R.string.cheat_hint_generic)
}

/** PPSSPP ignores the libretro cheat UI in practice: it wants CWCheat .ini files next to its save data. */
fun usesCwCheat(core: CoreInfo?): Boolean = core?.id == "ppsspp"

/**
 * Cheat list for one game with add / edit / delete / enable and RetroArch `.cht` import. Shared by the in-game
 * [CheatsSheet] and the library's game detail screen. Renders as a plain [Column] so it can live inside any
 * scroll container; the caller supplies the surrounding title/scrolling.
 *
 * [onChanged] runs after every DB change (the in-game sheet re-applies cheats to the running core).
 * [onMessage] receives short Korean status texts (import results) for the host to toast/snackbar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CheatEditor(
    gameId: Long,
    core: CoreInfo?,
    onChanged: suspend () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = OneEmuApp.get()
    val context = LocalContext.current
    val dao = remember { app.db.cheats() }
    val cheats by remember(gameId) { dao.observeForGame(gameId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CheatEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CheatEntity?>(null) }

    fun apply(block: suspend () -> Unit) = scope.launch { block(); onChanged() }

    val importCht = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        apply {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { CheatCodes.parseCht(it) }
            }
            when {
                imported == null -> onMessage(context.getString(R.string.cheat_import_failed))
                imported.isEmpty() -> onMessage(context.getString(R.string.cheat_import_none))
                else -> {
                    imported.forEach { dao.upsert(CheatEntity(gameId = gameId, name = it.name, code = it.code, enabled = it.enabled)) }
                    onMessage(context.getString(R.string.cheat_imported, imported.size))
                }
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (usesCwCheat(core)) {
            val system = SystemId.PSP
            val dir = File(app.dirs.saves(system.id), "PSP/Cheats")
            Text(
                stringResource(R.string.cheat_ppsspp_note, dir.absolutePath),
                style = MaterialTheme.typography.bodyMedium,
                color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { importCht.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cheat_import)) }
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.cheat_add)) }
        }

        if (cheats.isEmpty()) {
            Text(
                stringResource(R.string.cheat_empty),
                color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            Text(
                stringResource(R.string.cheat_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            cheats.forEach { cheat ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { editing = cheat },
                            onLongClick = { deleting = cheat },
                        )
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cheat.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val lines = cheat.code.lines().filter { it.isNotBlank() }
                        Text(
                            lines.firstOrNull().orEmpty() + if (lines.size > 1) "  (+${lines.size - 1})" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = OneEmuColors.OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = cheat.enabled, onCheckedChange = { on -> apply { dao.upsert(cheat.copy(enabled = on)) } })
                }
            }
        }
    }

    if (adding || editing != null) {
        val target = editing
        CheatDialog(
            title = stringResource(if (target == null) R.string.cheat_add else R.string.cheat_edit),
            hint = cheatFormatHint(context, core),
            hintLabel = stringResource(R.string.cheat_format_label, core?.displayName ?: ""),
            initialName = target?.name.orEmpty(),
            initialCode = target?.code.orEmpty(),
            confirmText = stringResource(if (target == null) R.string.cheat_add_button else R.string.save),
            onDismiss = { adding = false; editing = null },
            onDelete = if (target != null) ({ deleting = target; editing = null }) else null,
            onConfirm = { name, code ->
                val n = name.ifBlank { code.lines().first { it.isNotBlank() }.trim() }
                val entity = target?.copy(name = n, code = code) ?: CheatEntity(gameId = gameId, name = n, code = code, enabled = true)
                apply { dao.upsert(entity) }
                adding = false; editing = null
            },
        )
    }

    deleting?.let { cheat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.cheat_delete_confirm, cheat.name)) },
            confirmButton = {
                TextButton(onClick = { apply { dao.delete(cheat) }; deleting = null }) { Text(stringResource(R.string.delete), color = OneEmuColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CheatDialog(
    title: String,
    hint: String,
    hintLabel: String,
    initialName: String,
    initialCode: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (name: String, code: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cheat_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.cheat_code)) },
                    placeholder = { Text(stringResource(R.string.cheat_code_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(hintLabel, style = MaterialTheme.typography.labelMedium, color = OneEmuColors.Accent)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = { onConfirm(name.trim(), code.trim()) }) { Text(confirmText) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = OneEmuColors.Danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
