package com.manggome.oneemu.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.BiosEntry
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.core.DownloadState
import com.manggome.oneemu.core.ManifestState
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.library.ArcadeCoreRouter
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.common.DownloadProgress
import com.manggome.oneemu.ui.common.formatFileSize
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 코어 / BIOS: per system, pick the default core (stored under Settings.Keys.coreForSystem(systemId)
 * as the core id; empty/absent means CoreRegistry.defaultCoreFor), open core options, and show
 * whether each BIOS file the cores mention is present in the libretro system directory.
 */
@Composable
internal fun CoresSettingsScreen(onBack: () -> Unit, onCoreOptions: (coreId: String) -> Unit) {
    val app = OneEmuApp.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemDir = remember { app.dirs.system }
    // Bumped after importing files so BIOS existence checks re-run.
    var refresh by remember { mutableIntStateOf(0) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val subPaths = biosSubPathsByName(app.cores.cores)
            val result = withContext(Dispatchers.IO) { runCatching { copyIntoSystemDir(context, uris, systemDir, subPaths) } }
            result.onSuccess { n -> Toast.makeText(context, context.getString(R.string.cores_import_done, n), Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(context, context.getString(R.string.cores_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show() }
            refresh++
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_cores), onBack = onBack) {
        BiosFolderCard(
            path = systemDir.absolutePath,
            onCopy = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("OneEmu BIOS", systemDir.absolutePath))
                Toast.makeText(context, R.string.cores_path_copied, Toast.LENGTH_SHORT).show()
            },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
        )

        SystemId.ordered.forEach { system ->
            // Arcade cores in routing preference order (2003-Plus → 2010 → current MAME) rather than by id.
            val cores = remember(system) {
                val list = app.cores.coresFor(system)
                if (system == SystemId.ARCADE) list.sortedBy { ArcadeCoreRouter.CORE_IDS.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } } else list
            }
            SystemSection(system = system, cores = cores, systemDir = systemDir, refreshKey = refresh, onCoreOptions = onCoreOptions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BiosFolderCard(path: String, onCopy: () -> Unit, onImport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.cores_bios_folder), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cores_bios_folder_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                path,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.cores_copy_path))
                }
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.cores_import_bios))
                }
            }
        }
    }
}

@Composable
private fun SystemSection(
    system: SystemId,
    cores: List<CoreInfo>,
    systemDir: File,
    refreshKey: Int,
    onCoreOptions: (String) -> Unit,
) {
    val app = OneEmuApp.get()
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(system.color, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(system.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }

    if (cores.isEmpty()) {
        SettingsRow(title = stringResource(R.string.cores_no_core), subtitle = stringResource(R.string.cores_no_core_desc), enabled = false)
        SettingsDivider()
        return
    }

    val chosen = rememberPref(Settings.Keys.coreForSystem(system.id), "")
    val effective = chosen.value.takeIf { id -> cores.any { it.id == id } } ?: app.cores.defaultCoreFor(system)?.id

    Text(
        stringResource(R.string.cores_default_core),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    // Downloadable cores appear/disappear on disk while this screen is open: re-check availability on every state change.
    val dlStates by app.coreDownloads.state.collectAsStateWithLifecycle()
    cores.forEach { core ->
        val available = remember(core.id, dlStates) { app.cores.isAvailable(core) }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = core.id == effective, onClick = { chosen.set(core.id) }, enabled = available)
            Column(Modifier.weight(1f)) {
                Text(core.displayName + if (available) "" else " " + stringResource(if (core.isDownloadable) R.string.cores_core_not_installed else R.string.cores_core_unavailable))
                if (core.notes.isNotBlank()) {
                    Text(
                        core.notes.substringBefore('.').take(60),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onCoreOptions(core.id) }, enabled = available) { Text(stringResource(R.string.cores_core_options)) }
        }
        if (core.isDownloadable) DownloadableCoreCard(core, dlStates[core.id] ?: DownloadState.Idle)
        if (core.hwRender != "none") GraphicsApiRow(core)
    }

    // BIOS entries for this system (a core may serve several systems, so filter by entry.system when set).
    val bios = cores.flatMap { c -> c.bios.filter { it.system.isEmpty() || it.system == system.id } }.distinctBy { it.file }
    Text(
        stringResource(R.string.cores_bios_files),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    if (bios.isEmpty()) {
        Text(
            stringResource(R.string.cores_bios_none),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        bios.forEach { entry -> BiosRow(entry, systemDir, refreshKey) }
    }
    SettingsDivider()
}

/** Vulkan / OpenGL ES choice for a HW-rendering core; empty = the core.json default (core.hwRender). */
@Composable
private fun GraphicsApiRow(core: CoreInfo) {
    val pref = rememberPref(Settings.Keys.graphicsApi(core.id), "")
    val default = if (core.hwRender == "vulkan") "Vulkan" else "OpenGL ES"
    Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 6.dp)) {
        Text(
            stringResource(R.string.cores_graphics_api),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = pref.value.isEmpty(), onClick = { pref.set("") }, label = { Text(stringResource(R.string.cores_gfx_default, default)) })
            FilterChip(selected = pref.value == "vulkan", onClick = { pref.set("vulkan") }, label = { Text("Vulkan") })
            FilterChip(selected = pref.value == "gles3", onClick = { pref.set("gles3") }, label = { Text("OpenGL ES") })
        }
        Text(
            stringResource(R.string.cores_gfx_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Card for a `distribution: download` core: size from the `cores` release manifest ("약 68 MB"), installed
 * version or "설치되지 않음", 내려받기 / 업데이트 / 삭제 and the download progress. When the manifest cannot be
 * read (no network, or no `cores` release published yet) the reason is shown instead of the size.
 */
@Composable
private fun DownloadableCoreCard(core: CoreInfo, dl: DownloadState) {
    val app = OneEmuApp.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = app.coreDownloads
    val manifest by manager.manifest.collectAsStateWithLifecycle()
    LaunchedEffect(core.id) { manager.refreshManifest() }
    val installedVersion = remember(dl) { app.cores.installedVersion(core) }
    val installed = remember(dl) { app.cores.isAvailable(core) }
    val entry = (manifest as? ManifestState.Loaded)?.manifest?.entry(core.id)
    val updateAvailable = installed && entry != null && installedVersion != null && entry.version.isNotEmpty() && entry.version != installedVersion
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("${core.displayName} · ${stringResource(R.string.core_dl_title)}", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.core_dl_explain, core.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    installedVersion != null -> stringResource(R.string.core_dl_installed, installedVersion)
                    installed -> stringResource(R.string.core_dl_installed, "?")
                    else -> stringResource(R.string.core_dl_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (installed) Color(0xFF5CC489) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val m = manifest) {
                is ManifestState.Loaded -> Text(
                    if (entry != null) stringResource(R.string.core_dl_latest, entry.version, stringResource(R.string.core_dl_size, formatFileSize(entry.size)))
                    else stringResource(R.string.core_dl_err_not_in_manifest, core.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ManifestState.Unavailable -> Text(m.message, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.Danger)
                else -> Text(stringResource(R.string.core_dl_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (updateAvailable) Text(stringResource(R.string.core_dl_update_available), style = MaterialTheme.typography.bodySmall, color = OneEmuColors.Accent)
            if (dl.isBusy) {
                Spacer(Modifier.height(8.dp))
                DownloadProgress(dl)
            } else if (dl is DownloadState.Failed) {
                Spacer(Modifier.height(4.dp))
                Text(dl.message, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.Danger)
            }
            if (!dl.isBusy) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!installed && entry != null) {
                        OutlinedButton(onClick = { manager.download(core.id) }) {
                            Text(stringResource(if (dl is DownloadState.Failed) R.string.core_dl_action_retry else R.string.core_dl_action_download))
                        }
                    }
                    if (updateAvailable) OutlinedButton(onClick = { manager.download(core.id) }) { Text(stringResource(R.string.core_dl_action_update)) }
                    if (installed) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.core_dl_action_delete), color = OneEmuColors.Danger) }
                    if (manifest is ManifestState.Unavailable) {
                        TextButton(onClick = { scope.launch { manager.refreshManifest(force = true) } }) { Text(stringResource(R.string.core_dl_action_refresh)) }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.core_dl_action_delete),
            text = stringResource(R.string.core_dl_delete_confirm, core.displayName),
            confirmText = stringResource(R.string.core_dl_action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                manager.delete(core.id)
                Toast.makeText(context, context.getString(R.string.core_dl_deleted, core.displayName), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun BiosRow(entry: BiosEntry, systemDir: File, refreshKey: Int) {
    val present = remember(entry.file, refreshKey) { biosPresent(systemDir, entry) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (present) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = stringResource(if (present) R.string.cores_bios_found else R.string.cores_bios_missing),
            tint = if (present) Color(0xFF5CC489) else OneEmuColors.Danger,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.file, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(if (entry.required) R.string.cores_bios_required else R.string.cores_bios_optional),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (entry.required) OneEmuColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.description.isNotBlank()) {
                Text(entry.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Entries ending with "/" describe a folder the core needs; everything else is a file. */
private fun biosPresent(systemDir: File, entry: BiosEntry): Boolean {
    val f = File(systemDir, entry.file.trimEnd('/'))
    return if (entry.file.endsWith("/")) f.isDirectory else f.exists()
}

/**
 * Lower-cased basename -> relative path, for every bios[].file that lives in a subfolder of the system dir
 * (e.g. "aes_keys.txt" -> "Azahar/sysdata/aes_keys.txt", "cheat.dat" -> "mame2003-plus/cheat.dat").
 * Folder entries (ending with "/") are skipped. On a basename clash the first core wins.
 */
private fun biosSubPathsByName(cores: List<CoreInfo>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (core in cores) for (entry in core.bios) {
        val rel = entry.file.trim('/')
        if (entry.file.endsWith("/") || '/' !in rel) continue
        map.putIfAbsent(rel.substringAfterLast('/').lowercase(), rel)
    }
    return map
}

/**
 * Copies each picked document into [systemDir]. A file whose display name matches (case-insensitively) the
 * basename of a core's sub-folder BIOS entry is placed at that relative path (folders created); every other
 * file lands in the root of [systemDir] under its display name. Returns the number copied.
 */
private fun copyIntoSystemDir(context: Context, uris: List<Uri>, systemDir: File, subPathsByName: Map<String, String>): Int {
    var count = 0
    for (uri in uris) {
        val name = displayName(context, uri) ?: continue
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isBlank() || safe == "." || safe == "..") continue
        val target = File(systemDir, subPathsByName[safe.lowercase()] ?: safe)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
            count++
        }
    }
    return count
}

private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return c.getString(idx)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}
