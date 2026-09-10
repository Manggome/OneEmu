package com.manggome.oneemu.emu.menu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.CheatEntity
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch

/** Cheat list for one game: toggle switches, add dialog, long-press to delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CheatsSheet(gameId: Long, onChanged: suspend () -> Unit, onDismiss: () -> Unit) {
    val dao = remember { OneEmuApp.get().db.cheats() }
    val cheats by remember(gameId) { dao.observeForGame(gameId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CheatEntity?>(null) }

    fun apply(block: suspend () -> Unit) = scope.launch { block(); onChanged() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = OneEmuColors.Surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cheat_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.cheat_add)) }
        }
        if (cheats.isEmpty()) {
            Text(
                stringResource(R.string.cheat_empty),
                color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            Text(
                stringResource(R.string.cheat_long_press_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(cheats, key = { it.id }) { cheat ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { apply { dao.upsert(cheat.copy(enabled = !cheat.enabled)) } },
                                onLongClick = { deleting = cheat },
                            )
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cheat.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                cheat.code.lines().firstOrNull().orEmpty() + if (cheat.code.lines().size > 1) " …" else "",
                                style = MaterialTheme.typography.bodyMedium,
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
        Spacer(Modifier.height(24.dp))
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.cheat_add)) },
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
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.isNotBlank(),
                    onClick = {
                        val n = name.ifBlank { code.lines().first().trim() }
                        apply { dao.upsert(CheatEntity(gameId = gameId, name = n, code = code.trim(), enabled = true)) }
                        showAdd = false
                    },
                ) { Text(stringResource(R.string.cheat_add_button)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } },
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
