package com.manggome.oneemu.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.FolderEntity
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.common.formatDateTime
import com.manggome.oneemu.ui.theme.OneEmuColors
import java.io.File

/** Manage the folders the scanner watches: add, rescan, toggle recursion, remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(nav: NavHostController, vm: LibraryViewModel = viewModel()) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var toRemove by remember { mutableStateOf<FolderEntity?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addFolder(it) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> snackbar.showSnackbar(context.getString(msg.resId, *msg.args.toTypedArray())) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lib_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.rescanAll() }, enabled = !progress.running && folders.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lib_folders_rescan_all))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pickFolder.launch(null) },
                containerColor = OneEmuColors.Accent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lib_folders_add)) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanProgressBar(progress)
            if (folders.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.lib_folders_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.lib_folders_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        FolderCard(
                            folder = folder,
                            scanning = progress.running && progress.folder == folder.path,
                            onScan = { vm.scanFolder(folder) },
                            onRecursive = { vm.setFolderRecursive(folder, it) },
                            onRemove = { toRemove = folder },
                        )
                    }
                }
            }
        }
    }

    toRemove?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.lib_folders_remove),
            text = stringResource(R.string.lib_folders_remove_desc, folder.path),
            confirmText = stringResource(R.string.lib_remove_confirm),
            destructive = true,
            onConfirm = { vm.removeFolder(folder) },
            onDismiss = { toRemove = null },
        )
    }
}

@Composable
private fun FolderCard(
    folder: FolderEntity,
    scanning: Boolean,
    onScan: () -> Unit,
    onRecursive: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val exists = remember(folder.path) { File(folder.path).isDirectory }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(File(folder.path).name.ifEmpty { folder.path }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        folder.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !exists -> stringResource(R.string.lib_folders_missing)
                            folder.lastScannedAt > 0 -> stringResource(R.string.lib_folders_last_scanned, formatDateTime(folder.lastScannedAt))
                            else -> stringResource(R.string.lib_folders_never_scanned)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_folders_recursive), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = folder.recursive, onCheckedChange = onRecursive)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onScan, enabled = !scanning && exists) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lib_folders_scan))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.lib_folders_remove), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Thin progress bar + "스캔 중… 12/340" shown while the scanner runs; collapses to nothing otherwise. */
@Composable
fun ScanProgressBar(progress: com.manggome.oneemu.library.RomScanner.Progress, modifier: Modifier = Modifier) {
    if (!progress.running) return
    Column(modifier.fillMaxWidth()) {
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.found.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            if (progress.total > 0) stringResource(R.string.lib_scanning, progress.found, progress.total)
            else stringResource(R.string.lib_scanning_folder, File(progress.folder).name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
