package com.manggome.oneemu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.BoxArtCandidate
import com.manggome.oneemu.library.BoxArtFetcher
import com.manggome.oneemu.library.BoxArtKind

/**
 * Pick a picture for one game from what the libretro thumbnail server has. The search starts from
 * the game's title, but the player can type anything — useful when our title came from an oddly
 * named file — and switch between the box art, the title screen and an in-game shot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxArtPickerScreen(gameId: Long, vm: LibraryViewModel, onBack: () -> Unit) {
    var game by remember { mutableStateOf<GameEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(BoxArtKind.BOXART) }
    var results by remember { mutableStateOf<List<BoxArtCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    /** Regions the game itself names, so its own release sorts above the western default. */
    var regions by remember { mutableStateOf(emptySet<String>()) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * Runs a search, and when the game's own title finds nothing, quietly tries the ROM's file name
     * instead: a game renamed to Korean has a title the server cannot match, but its file usually
     * still carries the English name. The box shows whatever was actually searched.
     */
    fun search(text: String, fallback: String? = null) {
        val g = game ?: return
        searching = true
        failed = false
        vm.searchBoxArt(g.system, text, regions) { found ->
            if (found.isEmpty() && !fallback.isNullOrBlank() && fallback != text) {
                query = fallback
                search(fallback)
                return@searchBoxArt
            }
            results = found
            searching = false
        }
    }

    // The first search runs itself: opening the picker should already show something.
    LaunchedEffect(gameId) {
        val g = vm.game(gameId) ?: return@LaunchedEffect
        game = g
        val fileBase = File(g.path).name.substringBeforeLast('.')
        regions = BoxArtFetcher.regionsOf(g.title) + BoxArtFetcher.regionsOf(File(g.path).name)
        val seed = BoxArtFetcher.searchSeed(g.title, File(g.path).name)
        query = seed
        search(seed, fallback = fileBase)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boxart_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.boxart_search_label)) },
                trailingIcon = {
                    IconButton(onClick = { search(query) }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.boxart_search))
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (k in BoxArtKind.entries) {
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = { Text(stringResource(kindLabel(k))) },
                    )
                }
            }

            if (failed) {
                Text(
                    stringResource(R.string.boxart_apply_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            val found = results
            when {
                searching || applying -> Centered { CircularProgressIndicator() }
                found == null -> Centered { Text(stringResource(R.string.boxart_start_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                found.isEmpty() -> Centered { Text(stringResource(R.string.boxart_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(found, key = { it.systemFolder + "/" + it.name }) { candidate ->
                        CandidateCell(candidate, kind, url = vm.boxArtUrl(candidate, kind)) {
                            val g = game ?: return@CandidateCell
                            applying = true
                            failed = false
                            scope.launch {
                                if (vm.applyBoxArt(g, candidate, kind)) onBack()
                                else { applying = false; failed = true }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCell(candidate: BoxArtCandidate, kind: BoxArtKind, url: String, onPick: () -> Unit) {
    Column(Modifier.clickable(onClick = onPick)) {
        AsyncImage(
            model = url,
            contentDescription = candidate.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                // Box art is portrait, a screen grab is landscape; giving each its own shape stops
                // the grid from jumping around as the user switches between them.
                .aspectRatio(if (kind == BoxArtKind.BOXART) 0.72f else 1.33f)
                .clip(RoundedCornerShape(6.dp)),
        )
        Text(
            candidate.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

private fun kindLabel(kind: BoxArtKind): Int = when (kind) {
    BoxArtKind.BOXART -> R.string.boxart_kind_box
    BoxArtKind.TITLE -> R.string.boxart_kind_title
    BoxArtKind.SNAP -> R.string.boxart_kind_snap
}
