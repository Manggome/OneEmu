package com.manggome.oneemu.ui.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import java.io.File

/**
 * Thumbnail for a game, in priority order: user thumbnail → auto-extracted icon (NDS banner, drawn
 * with nearest-neighbour so pixels stay crisp) → colored placeholder with the first characters of
 * the title. Reused by list, grid, recent row and detail screens.
 *
 * @param titleSize font size of the placeholder initials; scale it with the box size.
 */
@Composable
fun GameThumbnail(
    game: GameEntity,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Crop,
    showFavorite: Boolean = true,
    titleSize: TextUnit = 20.sp,
    badgeSize: Dp = 16.dp,
) {
    val system = SystemId.fromId(game.system)
    val tint = system?.color ?: MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier.clip(shape).background(tint.copy(alpha = 0.35f))) {
        val custom = game.thumbnail?.takeIf { it.isNotBlank() }
        val auto = game.autoIcon?.takeIf { it.isNotBlank() }
        // Failed loads fall back to the next candidate; keyed on the source so a new thumbnail retries.
        var customFailed by remember(custom) { mutableStateOf(false) }
        var autoFailed by remember(auto) { mutableStateOf(false) }

        when {
            custom != null && !customFailed -> AsyncImage(
                model = toModel(custom),
                contentDescription = game.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onError = { customFailed = true },
            )
            auto != null && !autoFailed -> AsyncImage(
                model = File(auto),
                contentDescription = game.title,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                onError = { autoFailed = true },
            )
            else -> PlaceholderContent(game.title, system, titleSize)
        }

        if (showFavorite && game.favorite) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(badgeSize)
                    .background(Color(0xAA000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.lib_favorite_badge),
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(badgeSize * 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PlaceholderContent(title: String, system: SystemId?, titleSize: TextUnit) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text = title.trim().take(2).ifEmpty { "?" },
            modifier = Modifier.align(Alignment.Center).padding(bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = titleSize, fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (system != null) {
            Text(
                text = system.shortName,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                fontSize = (titleSize.value * 0.42f).coerceAtLeast(8f).sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

/** Stored thumbnails are either absolute file paths or content:// URIs. */
private fun toModel(source: String): Any =
    if (source.startsWith("content://") || source.startsWith("file://")) Uri.parse(source) else File(source)
