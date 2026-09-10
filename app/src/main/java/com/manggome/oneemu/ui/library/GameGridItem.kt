package com.manggome.oneemu.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.GameThumbnail
import com.manggome.oneemu.ui.common.SystemChip

/** Grid card: 3:4 portrait thumbnail (box art crops nicely, icons get padded) with a 2-line title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameGridItem(
    game: GameEntity,
    showSystemChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    val system = SystemId.fromId(game.system)
    val initialsSize = when {
        columns <= 2 -> 34.sp
        columns == 3 -> 26.sp
        else -> 20.sp
    }
    Card(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            GameThumbnail(
                game,
                Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                titleSize = initialsSize,
                badgeSize = 20.dp,
            )
            if (showSystemChip && system != null) {
                SystemChip(system, Modifier.align(Alignment.BottomStart).padding(6.dp), small = true)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).height(if (columns >= 4) 34.dp else 38.dp)) {
            Text(
                game.title,
                style = if (columns >= 4) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (columns >= 4) 15.sp else 17.sp,
            )
        }
    }
}
