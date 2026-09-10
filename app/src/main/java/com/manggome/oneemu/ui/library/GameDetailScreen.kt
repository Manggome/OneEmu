package com.manggome.oneemu.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.menu.CheatEditor
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes
import com.manggome.oneemu.ui.common.GameThumbnail
import com.manggome.oneemu.ui.common.SystemChip
import com.manggome.oneemu.ui.common.formatDateTime
import com.manggome.oneemu.ui.common.formatFileSize

/** Routes.GAME — large thumbnail, metadata and the same actions as the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(nav: NavHostController, gameId: Long, vm: LibraryViewModel = viewModel()) {
    val game by remember(gameId) { vm.observeGame(gameId) }.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var launchBlock by remember { mutableStateOf<LaunchCheck?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setThumbnail(gameId, it) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> snackbar.showSnackbar(context.getString(msg.resId, *msg.args.toTypedArray())) }
    }
    var cheatMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cheatMessage) { cheatMessage?.let { snackbar.showSnackbar(it); cheatMessage = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lib_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    game?.let { g ->
                        IconButton(onClick = { vm.toggleFavorite(g) }) {
                            Icon(
                                if (g.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.lib_favorite_badge),
                                tint = if (g.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lib_game_options))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val g = game
        if (g == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.lib_detail_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val system = SystemId.fromId(g.system)
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GameThumbnail(g, Modifier.size(180.dp, 240.dp), shape = RoundedCornerShape(14.dp), titleSize = 48.sp, badgeSize = 28.dp)
            Spacer(Modifier.height(16.dp))
            Text(g.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                system?.let { SystemChip(it); Spacer(Modifier.width(8.dp)) }
                Text(
                    system?.displayName ?: g.system,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    when (val check = vm.checkLaunch(g)) {
                        LaunchCheck.Ok -> launchGame(context, g)
                        else -> launchBlock = check
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_action_play), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.lib_action_thumbnail))
                }
                if (system != null) {
                    OutlinedButton(onClick = { nav.navigate(Routes.layoutEditor(system.id)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.lib_action_layout))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow(stringResource(R.string.lib_info_path), g.path, mono = true)
                    InfoRow(stringResource(R.string.lib_info_size), formatFileSize(g.fileSize))
                    InfoRow(stringResource(R.string.lib_info_core), vm.coreFor(g)?.displayName ?: stringResource(R.string.lib_no_core_short))
                    InfoRow(stringResource(R.string.lib_info_added), formatDateTime(g.addedAt))
                    InfoRow(stringResource(R.string.lib_info_last_played), if (g.lastPlayedAt > 0) formatDateTime(g.lastPlayedAt) else stringResource(R.string.lib_info_never))
                    InfoRow(stringResource(R.string.lib_info_play_time), playTimeText(g.playTimeSec))
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.lib_detail_cheats),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Text(
                        stringResource(R.string.lib_detail_cheats_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                    CheatEditor(gameId = g.id, core = vm.coreFor(g), onChanged = {}, onMessage = { cheatMessage = it })
                }
            }
        }

        if (showSheet) {
            GameActionSheet(
                game = g,
                vm = vm,
                onDismiss = { showSheet = false },
                onPlay = { target ->
                    when (val check = vm.checkLaunch(target)) {
                        LaunchCheck.Ok -> launchGame(context, target)
                        else -> launchBlock = check
                    }
                },
                onPickThumbnail = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onOpenCoreOptions = { nav.navigate(Routes.coreOptions(it)) },
            )
        }
    }

    launchBlock?.let { LaunchCheckDialog(it) { launchBlock = null } }
}
