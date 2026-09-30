package com.manggome.oneemu.ui.library

import androidx.compose.foundation.background
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
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
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
            FolderTile(section, onClick = { onOpen(section) })
        }
    }
}

@Composable
private fun FolderTile(section: LibrarySection, onClick: () -> Unit) {
    val tint = when {
        section.favorites -> FavoriteColor
        else -> section.system?.color ?: MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                    when {
                        section.favorites -> stringResource(R.string.lib_favorites_title)
                        else -> section.system?.shortName ?: stringResource(R.string.lib_section_unknown)
                    },
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
