package com.manggome.oneemu.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.GameThumbnail
import com.manggome.oneemu.ui.common.SystemChip
import java.io.File

/** melonDS-style row: 56dp thumbnail, title, optional file name, trailing options button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameListItem(
    game: GameEntity,
    showFileName: Boolean,
    showSystemChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val system = SystemId.fromId(game.system)
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameThumbnail(game, Modifier.size(56.dp), titleSize = MaterialTheme.typography.titleLarge.fontSize)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                game.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showFileName || showSystemChip) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSystemChip && system != null) {
                        SystemChip(system, small = true)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (showFileName) {
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
        }
        IconButton(onClick = onMore) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.lib_game_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
