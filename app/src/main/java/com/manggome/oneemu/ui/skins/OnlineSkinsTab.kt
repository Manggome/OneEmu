package com.manggome.oneemu.ui.skins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.skin.CatalogSkin
import com.manggome.oneemu.emu.skin.SkinCatalogManager
import com.manggome.oneemu.emu.skin.SkinCatalogState
import com.manggome.oneemu.emu.skin.SkinDownloadState
import com.manggome.oneemu.emu.skin.SkinInfo
import com.manggome.oneemu.emu.skin.SkinStore
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.formatFileSize
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch

/** Family filter chips of the online tab. [matches] decides membership from the catalog entry. */
private enum class SkinFilter(val label: Int, val matches: (CatalogSkin, SystemId) -> Boolean) {
    ALL(R.string.skins_online_filter_all, { _, _ -> true }),
    FOR_SYSTEM(R.string.skins_online_filter_system, { s, sys -> s.suits(sys.id) }),
    UNIVERSAL(R.string.skins_online_filter_universal, { s, _ -> s.universal }),
    FLAT(R.string.skins_online_filter_flat, { s, _ -> s.family == "flat" || s.family == "named" }),
    NEO(R.string.skins_online_filter_neo, { s, _ -> s.family.startsWith("neo") || s.family == "piixel" || s.family == "rgpad" }),
    LITE(R.string.skins_online_filter_lite, { s, _ -> s.family == "lite" }),
    CONSOLE(R.string.skins_online_filter_console, { s, _ -> s.family !in setOf("flat", "named", "lite", "old") && !s.family.startsWith("neo") && !s.universal && s.family != "piixel" && s.family != "rgpad" }),
    ANALOG(R.string.skins_online_filter_analog, { s, _ -> s.hasAnalog }),
    CLASSIC(R.string.skins_online_filter_classic, { s, _ -> s.family == "old" }),
}

/**
 * 온라인 tab of the skin picker: browses the `skins` release catalog, downloads a skin into `skins/<id>/`
 * (becoming a `user:` imported skin) and selects it for [system]. Installed entries can be deleted here too.
 */
@Composable
fun OnlineSkinsTab(
    system: SystemId,
    selectedId: String?,
    imported: List<SkinInfo>,
    onDelete: (SkinInfo) -> Unit,
    onInstalled: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SkinCatalogManager.state.collectAsState()
    val downloads by SkinCatalogManager.downloads.collectAsState()
    LaunchedEffect(Unit) { SkinCatalogManager.refresh(context) }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SkinFilter.ALL) }
    var landscapePreview by rememberSaveable { mutableStateOf(true) }

    // Downloads that finished while this tab is visible: tell the parent once (snackbar) and drop the flag.
    LaunchedEffect(downloads) {
        downloads.filterValues { it is SkinDownloadState.Installed }.keys.forEach { id ->
            onInstalled(id)
            SkinCatalogManager.clearDownloadState(id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.skins_online_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = null) }
                    IconButton(onClick = { landscapePreview = !landscapePreview }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = stringResource(if (landscapePreview) R.string.skins_preview_landscape else R.string.skins_preview_portrait), tint = OneEmuColors.Accent)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SkinFilter.entries, key = { it.name }) { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(if (f == SkinFilter.FOR_SYSTEM) stringResource(f.label, system.shortName) else stringResource(f.label)) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        when (val s = state) {
            SkinCatalogState.Idle, SkinCatalogState.Loading -> CenterMessage {
                CircularProgressIndicator(color = OneEmuColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.skins_online_loading), color = OneEmuColors.OnSurfaceMuted)
            }
            is SkinCatalogState.Unavailable -> CenterMessage {
                Text(s.message, color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { SkinCatalogManager.refresh(context, force = true) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skins_online_retry))
                }
            }
            is SkinCatalogState.Loaded -> {
                val q = query.trim().lowercase()
                val installedIds = remember(imported) { imported.map { it.id }.toSet() }
                val list = remember(s, q, filter, system) {
                    s.catalog.skins.filter { sk ->
                        filter.matches(sk, system) && (q.isEmpty() || listOf(sk.name, sk.family, sk.id, sk.author).any { it.lowercase().contains(q) })
                    }.sortedWith(compareBy<CatalogSkin>({ !it.suits(system.id) }, { !it.universal }, { it.family }, { it.name }))
                }
                if (list.isEmpty()) {
                    CenterMessage { Text(stringResource(R.string.skins_online_empty), color = OneEmuColors.OnSurfaceMuted) }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.id }) { skin ->
                            val installedInfo = if (skin.installedId in installedIds) imported.firstOrNull { it.id == skin.installedId } else null
                            OnlineSkinCard(
                                skin = skin,
                                landscape = landscapePreview,
                                download = downloads[skin.id],
                                installed = installedInfo,
                                selected = selectedId == skin.installedId,
                                onDownload = { SkinCatalogManager.download(context, skin) },
                                onSelect = { scope.launch { SkinStore.select(system.id, skin.installedId) } },
                                onDelete = { installedInfo?.let(onDelete) },
                            )
                        }
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.skins_online_footer, s.catalog.skins.size),
                                style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun OnlineSkinCard(
    skin: CatalogSkin,
    landscape: Boolean,
    download: SkinDownloadState?,
    installed: SkinInfo?,
    selected: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    val busy = download?.isBusy == true
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, OneEmuColors.Accent, shape) else Modifier)
            .clickable(enabled = installed != null && !busy, onClick = onSelect),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
    ) {
        Column {
            val url = if (landscape) skin.previewLandscape else skin.previewPortrait
            Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 19.5f / 9f else 9f / 12f).background(Color(0xFF151515))) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                    contentDescription = skin.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                )
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.skins_selected), tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(OneEmuColors.Accent, RoundedCornerShape(10.dp)).padding(3.dp))
                }
                if (!skin.hasPortrait || !skin.hasLandscape) {
                    Text(
                        stringResource(if (skin.hasLandscape) R.string.skins_online_landscape_only else R.string.skins_online_portrait_only),
                        style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(skin.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${skin.family} · ${skin.author.ifBlank { stringResource(R.string.skins_credit_unknown) }} · ${skin.license}",
                    style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                ButtonBadges(skin)
                Spacer(Modifier.height(8.dp))
                when {
                    busy -> {
                        val p = (download as? SkinDownloadState.Downloading)?.progress ?: -1f
                        if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = OneEmuColors.Accent)
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OneEmuColors.Accent)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (download is SkinDownloadState.Installing) stringResource(R.string.skins_online_installing)
                            else if (p >= 0f) stringResource(R.string.skins_online_downloading_pct, (p * 100).toInt()) else stringResource(R.string.skins_online_downloading),
                            style = MaterialTheme.typography.labelSmall, color = OneEmuColors.OnSurfaceMuted,
                        )
                    }
                    installed != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            Text(stringResource(R.string.skins_selected), style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f))
                        } else {
                            FilledTonalButton(onClick = onSelect, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.skins_online_use))
                            }
                        }
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.skins_delete), tint = OneEmuColors.OnSurfaceMuted) }
                    }
                    else -> {
                        val failed = download as? SkinDownloadState.Failed
                        if (failed != null) {
                            Text(failed.message, style = MaterialTheme.typography.labelSmall, color = OneEmuColors.Danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                        }
                        FilledTonalButton(onClick = onDownload, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (failed != null) stringResource(R.string.skins_online_retry)
                                else if (skin.size > 0) stringResource(R.string.skins_online_download_size, formatFileSize(skin.size))
                                else stringResource(R.string.skins_online_download),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A/B/X/Y/L/R/L2/R2/스틱 coverage as tiny chips; the d-pad and start/select are implied by every skin. */
@Composable
private fun ButtonBadges(skin: CatalogSkin) {
    val labels = buildList {
        for (b in listOf("a", "b", "x", "y", "l", "r", "l2", "r2")) if (b in skin.buttons) add(b.uppercase())
        if (skin.hasAnalog) add(stringResource(R.string.skins_online_badge_stick))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (l in labels) {
            Text(
                l, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = OneEmuColors.OnSurfaceMuted,
                modifier = Modifier.background(OneEmuColors.SurfaceHigh, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
