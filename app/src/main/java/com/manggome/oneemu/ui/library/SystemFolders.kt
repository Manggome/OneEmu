package com.manggome.oneemu.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.manggome.oneemu.model.SystemId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.ui.common.GameThumbnail

private val FavoriteColor = Color(0xFFFFD54F)

/** How the folder tiles are ordered; 즐겨찾기 stays first in every one. */
enum class FolderSort { NAME, RECENT, CUSTOM }

/** 크게: the 2 × 2 preview with a name row under it. 작게: a plain square with the name, more to a row. */
enum class FolderSize { LARGE, SMALL }

internal object FolderOrder {
    /** [sections] (즐겨찾기 among them or not) in [sort] order, 즐겨찾기 kept at the front. */
    fun sort(sections: List<LibrarySection>, sort: FolderSort, custom: List<String>, collator: Comparator<Any>): List<LibrarySection> {
        val (favorites, systems) = sections.partition { it.favorites }
        val ordered = when (sort) {
            FolderSort.NAME -> systems.sortedWith { a, b -> collator.compare(name(a), name(b)) }
            // Most recently played first; folders never played keep the default order after them.
            FolderSort.RECENT -> systems.sortedByDescending { s -> s.games.maxOfOrNull { it.lastPlayedAt } ?: 0L }
            FolderSort.CUSTOM -> custom(systems, custom)
        }
        return favorites + ordered
    }

    /** [systems] in the saved order; any folder not in it yet (a system added since) goes at the end. */
    fun custom(systems: List<LibrarySection>, custom: List<String>): List<LibrarySection> {
        val rank = custom.withIndex().associate { (i, key) -> key to i }
        return systems.filter { !it.favorites }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    private fun name(s: LibrarySection): String = s.system?.shortName ?: "\uFFFF"
}

/**
 * 기종별 폴더: the home screen as one tile per system (즐겨찾기 first), each showing its first four
 * games and how many there are. A tap opens that system on its own, so a big library is two taps
 * deep instead of one long scroll.
 */
@Composable
internal fun SystemFolderGrid(
    state: LibraryUiState,
    onOpen: (LibrarySection) -> Unit,
    onPlay: (GameEntity) -> Unit,
    onLongClick: (GameEntity) -> Unit,
    /** Long press on a tile: the 사용자 지정 order editor. */
    onEditOrder: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (state.folderSize == FolderSize.SMALL) 76.dp else 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.recent.isNotEmpty()) {
            item(key = "recent", span = { GridItemSpan(maxLineSpan) }, contentType = "recent") {
                RecentRow(state.recent, onPlay, onLongClick)
            }
        }
        items(state.sections, key = { "f_${it.key}" }, contentType = { "folder" }) { section ->
            if (state.folderSize == FolderSize.SMALL) SmallFolderTile(section, onClick = { onOpen(section) }, onLongClick = onEditOrder)
            else FolderTile(section, onClick = { onOpen(section) }, onLongClick = onEditOrder)
        }
    }
}

@Composable
private fun tintOf(section: LibrarySection): Color = when {
    section.favorites -> FavoriteColor
    else -> section.system?.color ?: MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun folderName(section: LibrarySection): String = when {
    section.favorites -> stringResource(R.string.lib_favorites_title)
    else -> section.system?.shortName ?: stringResource(R.string.lib_section_unknown)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(section: LibrarySection, onClick: () -> Unit, onLongClick: () -> Unit) {
    val tint = tintOf(section)
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        // The folder's face: its first four games, 2 × 2, on the system's colour.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(tint.copy(alpha = 0.16f))
                .padding(6.dp),
        ) {
            val preview = section.games.take(4)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 0 until 2) {
                            val game = preview.getOrNull(row * 2 + col)
                            val cell = Modifier.weight(1f).aspectRatio(1f)
                            if (game != null) {
                                GameThumbnail(game, cell, shape = RoundedCornerShape(8.dp), showFavorite = false, titleSize = 12.sp, badgeSize = 10.dp)
                            } else {
                                Box(cell.background(tint.copy(alpha = 0.10f), RoundedCornerShape(8.dp)))
                            }
                        }
                    }
                }
            }
            if (section.favorites) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = FavoriteColor,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 3.dp, height = 26.dp).background(tint, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    folderName(section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    section.system?.displayName ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${section.games.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 작게: one square per folder - its colour, short name and count - so five or so fit a row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallFolderTile(section: LibrarySection, onClick: () -> Unit, onLongClick: () -> Unit) {
    val tint = tintOf(section)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.28f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (section.favorites) Icon(Icons.Filled.Star, contentDescription = null, tint = FavoriteColor, modifier = Modifier.size(18.dp))
            Text(
                folderName(section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                "${section.games.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 순서 바꾸기: the system folders in their 사용자 지정 order, each moved with ↑ ↓. Saving switches the
 * tiles to that order. 즐겨찾기 is not listed; it is always first.
 */
@Composable
internal fun FolderOrderDialog(keys: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var order by remember(keys) { mutableStateOf(keys) }
    fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in order.indices) return
        order = order.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_folder_order_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, k -> k }) { i, key ->
                    val system = SystemId.fromId(key)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 4.dp, height = 24.dp).background(system?.color ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(system?.shortName ?: stringResource(R.string.lib_section_unknown), style = MaterialTheme.typography.bodyLarge)
                            if (system != null) {
                                Text(system.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.lib_folder_move_up))
                        }
                        IconButton(onClick = { move(i, 1) }, enabled = i < order.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.lib_folder_move_down))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
