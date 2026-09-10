package com.manggome.oneemu.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.manggome.oneemu.R
import com.manggome.oneemu.data.SortMode
import com.manggome.oneemu.data.ViewMode
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.ui.Routes

/** Routes.LIBRARY — the home screen: grouped game list/grid, search, sort, add menu. */
@Composable
fun LibraryScreen(nav: NavHostController, vm: LibraryViewModel = viewModel()) {
    PermissionGate { LibraryContent(nav, vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(nav: NavHostController, vm: LibraryViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var selected by remember { mutableStateOf<GameEntity?>(null) }
    var launchBlock by remember { mutableStateOf<LaunchCheck?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var thumbnailTarget by rememberSaveable { mutableStateOf(-1L) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = thumbnailTarget
        thumbnailTarget = -1L
        if (uri != null && target > 0) vm.setThumbnail(target, uri)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addFolder(it) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> snackbar.showSnackbar(context.getString(msg.resId, *msg.args.toTypedArray())) }
    }

    fun tryLaunch(game: GameEntity) {
        when (val check = vm.checkLaunch(game)) {
            LaunchCheck.Ok -> launchGame(context, game)
            else -> launchBlock = check
        }
    }

    BackHandler(enabled = state.searching) { vm.setSearching(false) }

    Scaffold(
        topBar = {
            Column {
                LibraryTopBar(state, vm, nav)
                ScanProgressBar(progress)
            }
        },
        floatingActionButton = {
            AddMenu(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onAddGame = { pickFiles.launch(arrayOf("*/*")) },
                onAddFolder = { pickFolder.launch(null) },
                onHelp = { showHelp = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Unit
                state.isEmpty -> EmptyState(
                    onAddGame = { pickFiles.launch(arrayOf("*/*")) },
                    onAddFolder = { pickFolder.launch(null) },
                )
                state.sections.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.lib_search_empty, state.query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                state.viewMode == ViewMode.LIST -> GameList(
                    state = state,
                    onToggleSection = vm::toggleSection,
                    onClick = ::tryLaunch,
                    onLongClick = { selected = it },
                )
                else -> GameGrid(
                    state = state,
                    onToggleSection = vm::toggleSection,
                    onClick = ::tryLaunch,
                    onLongClick = { selected = it },
                )
            }
        }
    }

    // Resolve the live row so favorite/thumbnail changes made from the sheet show immediately.
    val liveSelected = selected?.let { sel -> state.sections.asSequence().flatMap { it.games.asSequence() }.firstOrNull { it.id == sel.id } ?: sel }
    liveSelected?.let { game ->
        GameActionSheet(
            game = game,
            vm = vm,
            onDismiss = { selected = null },
            onPlay = ::tryLaunch,
            onPickThumbnail = {
                thumbnailTarget = it.id
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onOpenCoreOptions = { nav.navigate(Routes.coreOptions(it)) },
            onOpenDetails = { nav.navigate(Routes.game(it.id)) },
        )
    }
    launchBlock?.let { LaunchCheckDialog(it) { launchBlock = null } }
    if (showHelp) HelpDialog(vm.dirs.system, vm.cores) { showHelp = false }
}

// ---- top bar ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(state: LibraryUiState, vm: LibraryViewModel, nav: NavHostController) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    if (state.searching) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { focus.requestFocus() }
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { vm.setSearching(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.lib_search_close))
                }
            },
            title = {
                TextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            },
            actions = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.lib_search_clear))
                    }
                }
            },
        )
        return
    }

    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.lib_title))
                if (state.totalCount > 0) {
                    Text(
                        stringResource(R.string.lib_game_count, state.totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { vm.setSearching(true) }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.lib_sort))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortItem(R.string.lib_sort_title, state.sortMode == SortMode.TITLE) { vm.setSortMode(SortMode.TITLE); sortMenu = false }
                    SortItem(R.string.lib_sort_recent, state.sortMode == SortMode.RECENT) { vm.setSortMode(SortMode.RECENT); sortMenu = false }
                    SortItem(R.string.lib_sort_added, state.sortMode == SortMode.ADDED) { vm.setSortMode(SortMode.ADDED); sortMenu = false }
                }
            }
            if (state.viewMode == ViewMode.LIST) {
                IconButton(onClick = { vm.setViewMode(ViewMode.GRID) }) {
                    Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.lib_view_grid))
                }
            } else {
                IconButton(onClick = { vm.setViewMode(ViewMode.LIST) }) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = stringResource(R.string.lib_view_list))
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_menu_folders)) },
                        onClick = { moreMenu = false; nav.navigate(Routes.FOLDERS) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_menu_rescan)) },
                        onClick = { moreMenu = false; vm.rescanAll() },
                    )
                    HorizontalDivider()
                    CheckItem(R.string.lib_menu_group_by_system, state.groupBySystem) { vm.setGroupBySystem(!state.groupBySystem) }
                    CheckItem(R.string.lib_menu_show_file_name, state.showFileName) { vm.setShowFileName(!state.showFileName) }
                    if (state.viewMode == ViewMode.GRID) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lib_menu_grid_columns, state.gridColumns)) },
                            onClick = {
                                val next = if (state.gridColumns >= LibraryViewModel.MAX_GRID_COLUMNS) LibraryViewModel.MIN_GRID_COLUMNS else state.gridColumns + 1
                                vm.setGridColumns(next)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_menu_settings)) },
                        onClick = { moreMenu = false; nav.navigate(Routes.SETTINGS) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SortItem(labelRes: Int, checked: Boolean, onClick: () -> Unit) = CheckItem(labelRes, checked, onClick)

@Composable
private fun CheckItem(labelRes: Int, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.width(24.dp))
        },
    )
}

// ---- body --------------------------------------------------------------------------------------------

private val BottomPadding = 96.dp // room for the FAB

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameList(
    state: LibraryUiState,
    onToggleSection: (LibrarySection) -> Unit,
    onClick: (GameEntity) -> Unit,
    onLongClick: (GameEntity) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomPadding)) {
        if (state.recent.isNotEmpty() && !state.searching) {
            item(key = "recent", contentType = "recent") { RecentRow(state.recent, onClick, onLongClick) }
        }
        for (section in state.sections) {
            if (state.groupBySystem) {
                stickyHeader(key = "h_${section.key}", contentType = "header") { _ ->
                    SectionHeader(section, onToggle = { onToggleSection(section) })
                }
            }
            if (!section.collapsed) {
                items(section.games, key = { "g_${it.id}" }, contentType = { "game" }) { game ->
                    GameListItem(
                        game = game,
                        showFileName = state.showFileName,
                        showSystemChip = !state.groupBySystem,
                        onClick = { onClick(game) },
                        onLongClick = { onLongClick(game) },
                        onMore = { onLongClick(game) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GameGrid(
    state: LibraryUiState,
    onToggleSection: (LibrarySection) -> Unit,
    onClick: (GameEntity) -> Unit,
    onLongClick: (GameEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.gridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = BottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.recent.isNotEmpty() && !state.searching) {
            item(key = "recent", span = { GridItemSpan(maxLineSpan) }, contentType = "recent") {
                RecentRow(state.recent, onClick, onLongClick)
            }
        }
        for (section in state.sections) {
            if (state.groupBySystem) {
                item(key = "h_${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader(section, onToggle = { onToggleSection(section) })
                }
            }
            if (!section.collapsed) {
                items(section.games, key = { "g_${it.id}" }, contentType = { "game" }) { game ->
                    GameGridItem(
                        game = game,
                        showSystemChip = !state.groupBySystem,
                        onClick = { onClick(game) },
                        onLongClick = { onLongClick(game) },
                        columns = state.gridColumns,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddGame: () -> Unit, onAddFolder: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.VideogameAsset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp).height(64.dp).width(64.dp),
            )
            Text(stringResource(R.string.lib_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.lib_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAddFolder, modifier = Modifier.fillMaxWidth(0.8f)) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_add_folder))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAddGame, modifier = Modifier.fillMaxWidth(0.8f)) {
                Icon(Icons.Filled.VideogameAsset, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_add_game))
            }
        }
    }
}
