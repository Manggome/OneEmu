package com.manggome.oneemu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.common.GameThumbnail
import com.manggome.oneemu.ui.common.InfoDialog
import com.manggome.oneemu.ui.common.SystemChip
import com.manggome.oneemu.ui.common.formatDateTime
import com.manggome.oneemu.ui.common.formatFileSize
import java.io.File

/**
 * Bottom sheet with everything you can do to one game. Sub-dialogs (rename, core, info, remove) are
 * owned here; the image picker lives in the screen because it needs an ActivityResult launcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameActionSheet(
    game: GameEntity,
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
    onPlay: (GameEntity) -> Unit,
    onPickThumbnail: (GameEntity) -> Unit,
    onOpenCoreOptions: (coreId: String) -> Unit,
    onOpenDetails: ((GameEntity) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val system = SystemId.fromId(game.system)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GameThumbnail(game, Modifier.size(64.dp), titleSize = 22.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        system?.let { SystemChip(it, small = true); Spacer(Modifier.width(6.dp)) }
                        Text(
                            File(game.path).name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetItem(Icons.Filled.PlayArrow, stringResource(R.string.lib_action_play), tint = MaterialTheme.colorScheme.primary) {
                onDismiss(); onPlay(game)
            }
            SheetItem(
                if (game.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                stringResource(if (game.favorite) R.string.lib_action_favorite_remove else R.string.lib_action_favorite_add),
            ) { vm.toggleFavorite(game) }
            SheetItem(Icons.Filled.Image, stringResource(R.string.lib_action_thumbnail)) { onDismiss(); onPickThumbnail(game) }
            if (game.thumbnail != null) {
                SheetItem(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.lib_action_thumbnail_reset)) { vm.resetThumbnail(game) }
            }
            SheetItem(Icons.Filled.Edit, stringResource(R.string.lib_action_rename)) { dialog = "rename" }
            SheetItem(Icons.Filled.Memory, stringResource(R.string.lib_action_core)) { dialog = "core" }
            SheetItem(Icons.Filled.Info, stringResource(R.string.lib_action_info)) { dialog = "info" }
            if (onOpenDetails != null) {
                SheetItem(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.lib_action_details)) { onDismiss(); onOpenDetails(game) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetItem(Icons.Filled.Delete, stringResource(R.string.lib_action_remove), tint = MaterialTheme.colorScheme.error) { dialog = "remove" }
        }
    }

    when (dialog) {
        "rename" -> RenameDialog(game, onDismiss = { dialog = null }) { vm.rename(game, it) }
        "core" -> CorePickerDialog(game, vm, onDismiss = { dialog = null }, onOpenCoreOptions = { onDismiss(); onOpenCoreOptions(it) })
        "info" -> FileInfoDialog(game, vm, onDismiss = { dialog = null })
        "remove" -> ConfirmDialog(
            title = stringResource(R.string.lib_remove_title),
            text = stringResource(R.string.lib_remove_desc, game.title),
            confirmText = stringResource(R.string.lib_remove_confirm),
            destructive = true,
            onConfirm = { vm.remove(game); onDismiss() },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
fun RenameDialog(game: GameEntity, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable(game.id) { mutableStateOf(game.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.lib_rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onRename(text); onDismiss() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Radio list of cores that can run this system; the first entry is "use the system default". */
@Composable
fun CorePickerDialog(game: GameEntity, vm: LibraryViewModel, onDismiss: () -> Unit, onOpenCoreOptions: (String) -> Unit) {
    val system = SystemId.fromId(game.system)
    val cores = remember(game.system) { system?.let { vm.cores.coresFor(it) } ?: emptyList() }
    val default = remember(game.system) { system?.let { vm.cores.defaultCoreFor(it) } }
    val effective = vm.coreFor(game)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_core_title)) },
        text = {
            Column {
                if (cores.isEmpty()) {
                    Text(stringResource(R.string.lib_core_none))
                } else {
                    CoreRow(
                        label = if (default != null) stringResource(R.string.lib_core_default_of, default.displayName) else stringResource(R.string.lib_core_default),
                        selected = game.coreId == null,
                        enabled = true,
                    ) { vm.setCore(game, null) }
                    for (core in cores) {
                        val available = vm.cores.isAvailable(core)
                        CoreRow(
                            label = core.displayName,
                            sub = if (available) null else stringResource(R.string.lib_core_unavailable),
                            selected = game.coreId == core.id,
                            enabled = available,
                        ) { vm.setCore(game, core.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (effective != null) {
                TextButton(onClick = { onOpenCoreOptions(effective.id) }) { Text(stringResource(R.string.lib_core_options)) }
            }
        },
    )
}

@Composable
private fun CoreRow(label: String, selected: Boolean, enabled: Boolean, sub: String? = null, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FileInfoDialog(game: GameEntity, vm: LibraryViewModel, onDismiss: () -> Unit) {
    val system = SystemId.fromId(game.system)
    val core = vm.coreFor(game)
    val exists = remember(game.path) { File(game.path).exists() }
    InfoDialog(title = stringResource(R.string.lib_info_title), onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            InfoRow(stringResource(R.string.lib_info_path), game.path, mono = true)
            if (!exists) {
                Text(stringResource(R.string.lib_info_missing_file), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            InfoRow(stringResource(R.string.lib_info_size), formatFileSize(game.fileSize))
            InfoRow(stringResource(R.string.lib_info_system), system?.displayName ?: game.system)
            InfoRow(stringResource(R.string.lib_info_core), core?.displayName ?: stringResource(R.string.lib_no_core_short))
            InfoRow(stringResource(R.string.lib_info_added), formatDateTime(game.addedAt))
            InfoRow(stringResource(R.string.lib_info_last_played), if (game.lastPlayedAt > 0) formatDateTime(game.lastPlayedAt) else stringResource(R.string.lib_info_never))
            InfoRow(stringResource(R.string.lib_info_play_time), playTimeText(game.playTimeSec))
        }
    }
}

@Composable
fun playTimeText(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes >= 60) stringResource(R.string.lib_play_time_format, minutes / 60, minutes % 60)
    else stringResource(R.string.lib_play_time_minutes, minutes)
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
        )
    }
}
