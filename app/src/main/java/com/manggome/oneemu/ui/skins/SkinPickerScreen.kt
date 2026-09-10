package com.manggome.oneemu.ui.skins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElementVisual
import com.manggome.oneemu.emu.pad.drawPadElement
import com.manggome.oneemu.emu.pad.rectOn
import com.manggome.oneemu.emu.skin.LoadedSkin
import com.manggome.oneemu.emu.skin.SkinInfo
import com.manggome.oneemu.emu.skin.SkinLayout
import com.manggome.oneemu.emu.skin.SkinLoader
import com.manggome.oneemu.emu.skin.SkinStore
import com.manggome.oneemu.emu.skin.SkinVisual
import com.manggome.oneemu.emu.skin.drawOverlay
import com.manggome.oneemu.emu.skin.placeOverlay
import com.manggome.oneemu.emu.skin.resolved
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch

/**
 * Routes.SKINS destination: choose the on-screen pad skin for one system, import RetroArch overlays
 * (zip or folder), delete imports, and jump to the layout editor. Attribution is shown on every card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinPickerScreen(system: SystemId, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val bundled by produceState<List<SkinInfo>>(emptyList()) { value = SkinStore.bundled(context) }
    val imported by SkinStore.imported().collectAsState()
    LaunchedEffect(Unit) { SkinStore.refreshImported(context) }
    val selectedId by SkinStore.observeSelected(system.id).collectAsState(initial = null)

    var importDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkinInfo?>(null) }

    val importedMsg = stringResource(R.string.skins_import_done)
    val failedMsg = stringResource(R.string.skins_import_failed)
    fun runImport(block: suspend () -> SkinInfo) {
        importing = true
        scope.launch {
            val result = runCatching { block() }
            importing = false
            val info = result.getOrNull()
            if (info != null) {
                SkinStore.select(system.id, info.id)
                snackbar.showSnackbar(importedMsg.replace("%1\$s", info.name))
            } else {
                snackbar.showSnackbar(failedMsg)
            }
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport { SkinStore.importZip(context, uri) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runImport { SkinStore.importTree(context, uri) }
    }

    val recommended = bundled.filter { it.suits(system) }
    val universal = bundled.filter { it.neutral }
    val other = bundled.filter { !it.suits(system) && !it.neutral }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skins_title_for, system.shortName)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(if (importing) R.string.skins_importing else R.string.skins_import)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "vector") {
                VectorCard(system, selected = selectedId == SkinStore.VECTOR, onSelect = { scope.launch { SkinStore.select(system.id, SkinStore.VECTOR) } }, onEdit = onEdit)
            }
            if (recommended.isNotEmpty()) {
                item(key = "h-rec") { SectionHeader(stringResource(R.string.skins_section_recommended)) }
                items(recommended, key = { it.id }) { SkinCardRow(it, system, selectedId, scope, onEdit, null) }
            }
            if (universal.isNotEmpty()) {
                item(key = "h-uni") { SectionHeader(stringResource(R.string.skins_section_universal)) }
                items(universal, key = { it.id }) { SkinCardRow(it, system, selectedId, scope, onEdit, null) }
            }
            item(key = "h-imp") { SectionHeader(stringResource(R.string.skins_section_imported)) }
            val imp = imported.orEmpty()
            if (imp.isEmpty()) {
                item(key = "no-imp") {
                    Text(stringResource(R.string.skins_no_imported), style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(horizontal = 4.dp))
                }
            } else {
                items(imp, key = { it.id }) { SkinCardRow(it, system, selectedId, scope, onEdit, onDelete = { deleteTarget = it }) }
            }
            if (other.isNotEmpty()) {
                item(key = "h-other") { SectionHeader(stringResource(R.string.skins_section_other)) }
                items(other, key = { it.id }) { SkinCardRow(it, system, selectedId, scope, onEdit, null) }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.skins_credits_footer),
                    style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }

    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text(stringResource(R.string.skins_import)) },
            text = { Text(stringResource(R.string.skins_import_hint)) },
            confirmButton = {
                TextButton(onClick = { importDialog = false; zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                    Text(stringResource(R.string.skins_import_zip))
                }
            },
            dismissButton = {
                TextButton(onClick = { importDialog = false; folderPicker.launch(null) }) { Text(stringResource(R.string.skins_import_folder)) }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.skins_delete),
            text = stringResource(R.string.skins_delete_confirm, target.name),
            onConfirm = {
                scope.launch {
                    if (selectedId == target.id) SkinStore.select(system.id, SkinStore.defaultSkinId(system))
                    SkinStore.delete(context, target)
                }
            },
            onDismiss = { deleteTarget = null },
            confirmText = stringResource(R.string.skins_delete),
            destructive = true,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun SkinCardRow(
    info: SkinInfo,
    system: SystemId,
    selectedId: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onEdit: () -> Unit,
    onDelete: ((SkinInfo) -> Unit)?,
) {
    val context = LocalContext.current
    val loaded by produceState<LoadedSkin?>(null, info.id) { value = runCatching { SkinLoader.load(context, info) }.getOrNull() }
    val selected = selectedId == info.id
    SkinCard(
        selected = selected,
        onSelect = { scope.launch { SkinStore.select(system.id, info.id) } },
        previews = {
            SkinPreview(loaded, landscape = false, system, Modifier.size(width = 44.dp, height = 92.dp))
            Spacer(Modifier.width(8.dp))
            SkinPreview(loaded, landscape = true, system, Modifier.size(width = 92.dp, height = 44.dp))
        },
        title = info.name,
        subtitle = if (info.author.isBlank() && info.license.isBlank()) stringResource(R.string.skins_credit_unknown)
        else stringResource(R.string.skins_credit, info.author.ifBlank { "?" }, info.license.ifBlank { "?" }),
        onEdit = onEdit,
        trailing = if (onDelete != null) {
            { IconButton(onClick = { onDelete(info) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.skins_delete), tint = OneEmuColors.OnSurfaceMuted) } }
        } else null,
    )
}

@Composable
private fun VectorCard(system: SystemId, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    SkinCard(
        selected = selected,
        onSelect = onSelect,
        previews = {
            VectorPreview(system, landscape = false, Modifier.size(width = 44.dp, height = 92.dp))
            Spacer(Modifier.width(8.dp))
            VectorPreview(system, landscape = true, Modifier.size(width = 92.dp, height = 44.dp))
        },
        title = stringResource(R.string.skins_vector_name),
        subtitle = stringResource(R.string.skins_vector_desc),
        onEdit = onEdit,
        trailing = null,
    )
}

@Composable
private fun SkinCard(
    selected: Boolean,
    onSelect: () -> Unit,
    previews: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, OneEmuColors.Accent, shape) else Modifier)
            .clickable(onClick = onSelect),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { previews() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.skins_selected), tint = OneEmuColors.Accent, modifier = Modifier.size(16.dp))
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
                if (selected) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.skins_edit)) }
                }
            }
            trailing?.invoke()
        }
    }
}

/** Phone-shaped thumbnail of one orientation of a skin (faint game rectangle + overlay). */
@Composable
fun SkinPreview(skin: LoadedSkin?, landscape: Boolean, system: SystemId, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(Color(0xFF151515)).border(1.dp, OneEmuColors.Divider, shape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawMockGame(landscape)
            val s = skin ?: return@Canvas
            val cfg = s.cfg(landscape)
            val base = cfg.pick(landscape, size.width / size.height, preferAnalog = system.hasAnalog) ?: return@Canvas
            val overlay = cfg.resolved(base, landscape)
            val (frame, placed) = placeOverlay(overlay, size, landscape, SkinLayout.EMPTY, 1f)
            drawOverlay(s, overlay, frame, placed, SkinVisual(), Color.Transparent)
        }
    }
}

/** Thumbnail of the default vector layout, drawn with the same code as the live pad at preview density. */
@Composable
private fun VectorPreview(system: SystemId, landscape: Boolean, modifier: Modifier) {
    val config = LocalConfiguration.current
    val shortDp = minOf(config.screenWidthDp, config.screenHeightDp).coerceAtLeast(320)
    val textMeasurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(6.dp)
    val layout = remember(system, landscape) { DefaultLayouts.forSystem(system, landscape) }
    Box(modifier.clip(shape).background(Color(0xFF151515)).border(1.dp, OneEmuColors.Divider, shape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawMockGame(landscape)
            val previewDensity = minOf(size.width, size.height) / shortDp
            for (e in layout.elements) {
                if (!e.visible) continue
                drawPadElement(e, e.rectOn(size, previewDensity, 1f), system, PadElementVisual(), textMeasurer)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMockGame(landscape: Boolean) {
    val aspect = 4f / 3f
    val rect = if (landscape) {
        val h = size.height * 0.9f
        val w = minOf(h * aspect, size.width * 0.6f)
        Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, w / aspect))
    } else {
        val w = size.width
        Rect(Offset(0f, size.height * 0.08f), Size(w, w / aspect))
    }
    drawRect(Color(0xFF2B2B2B), rect.topLeft, rect.size)
}
