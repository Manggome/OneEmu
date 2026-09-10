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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.BiosEntry
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.model.SystemId
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
            val result = withContext(Dispatchers.IO) { runCatching { copyIntoSystemDir(context, uris, systemDir) } }
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
            val cores = remember(system) { app.cores.coresFor(system) }
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
    cores.forEach { core ->
        val available = remember(core.id) { app.cores.isAvailable(core) }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = core.id == effective, onClick = { chosen.set(core.id) }, enabled = available)
            Column(Modifier.weight(1f)) {
                Text(core.displayName + if (available) "" else " " + stringResource(R.string.cores_core_unavailable))
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

/** Copies each picked document into [systemDir], keeping its display name. Returns the number copied. */
private fun copyIntoSystemDir(context: Context, uris: List<Uri>, systemDir: File): Int {
    var count = 0
    for (uri in uris) {
        val name = displayName(context, uri) ?: continue
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isBlank() || safe == "." || safe == "..") continue
        val target = File(systemDir, safe)
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
