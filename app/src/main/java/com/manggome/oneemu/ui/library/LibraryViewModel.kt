package com.manggome.oneemu.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.BiosEntry
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.data.SortMode
import com.manggome.oneemu.data.ViewMode
import com.manggome.oneemu.data.db.FolderEntity
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.RomScanner
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/** One collapsible block of the library; [system] is null for the flat (ungrouped) list or unknown systems. */
data class LibrarySection(
    val system: SystemId?,
    val games: List<GameEntity>,
    val collapsed: Boolean,
) {
    val key: String get() = system?.id ?: "unknown"
}

data class LibraryUiState(
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val sections: List<LibrarySection> = emptyList(),
    val recent: List<GameEntity> = emptyList(),
    val viewMode: ViewMode = ViewMode.LIST,
    val sortMode: SortMode = SortMode.TITLE,
    val gridColumns: Int = 3,
    val groupBySystem: Boolean = true,
    val showFileName: Boolean = true,
    val query: String = "",
    val searching: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && totalCount == 0
    val filteredCount: Int get() = sections.sumOf { it.games.size }
}

/** One-off messages for the snackbar; the UI resolves [resId] with [args]. */
data class LibraryMessage(val resId: Int, val args: List<Any> = emptyList())

/** Result of the pre-launch check (core present, required BIOS present, file present). */
sealed class LaunchCheck {
    data object Ok : LaunchCheck()
    data class NoCore(val system: SystemId?) : LaunchCheck()
    data class MissingBios(val core: CoreInfo, val files: List<BiosEntry>, val dir: File) : LaunchCheck()
    data class MissingFile(val path: String) : LaunchCheck()
}

class LibraryViewModel : ViewModel() {
    private val app = OneEmuApp.get()
    private val settings = app.settings
    private val db = app.db
    private val scanner = app.scanner
    val cores get() = app.cores
    val dirs get() = app.dirs

    private val query = MutableStateFlow("")
    private val searching = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<LibraryMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<LibraryMessage> get() = _messages

    val scanProgress: StateFlow<RomScanner.Progress> get() = scanner.progress

    val folders: StateFlow<List<FolderEntity>> = db.folders().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Prefs(
        val viewMode: ViewMode,
        val sortMode: SortMode,
        val gridColumns: Int,
        val groupBySystem: Boolean,
        val showFileName: Boolean,
        val collapsed: Set<String>,
    )

    private val prefs: Flow<Prefs> = combine(
        settings.viewMode,
        settings.sortMode,
        settings.observe(Settings.Keys.gridColumns, DEFAULT_GRID_COLUMNS),
        settings.observe(Settings.Keys.groupBySystem, true),
        settings.observe(Settings.Keys.showFileName, true),
    ) { view, sort, cols, group, fileName ->
        Prefs(view, sort, cols.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS), group, fileName, emptySet())
    }.combine(settings.observe(KEY_COLLAPSED_SECTIONS, "")) { p, collapsed ->
        p.copy(collapsed = collapsed.split(',').filter { it.isNotBlank() }.toSet())
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        db.games().observeAll(), prefs, query, searching,
    ) { games, p, q, s -> buildState(games, p, q, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val collator: Collator = Collator.getInstance(Locale.KOREAN)

    private fun buildState(all: List<GameEntity>, p: Prefs, q: String, s: Boolean): LibraryUiState {
        val needle = q.trim()
        val filtered = if (needle.isEmpty()) all else all.filter {
            it.title.contains(needle, ignoreCase = true) || File(it.path).name.contains(needle, ignoreCase = true)
        }
        val sorted = sort(filtered, p.sortMode)
        val sections = if (p.groupBySystem) {
            val bySystem = sorted.groupBy { SystemId.fromId(it.system) }
            val ordered = SystemId.ordered.mapNotNull { sys ->
                bySystem[sys]?.let { LibrarySection(sys, it, sys.id in p.collapsed) }
            }
            val unknown = bySystem[null]?.let { LibrarySection(null, it, "unknown" in p.collapsed) }
            if (unknown != null) ordered + unknown else ordered
        } else {
            if (sorted.isEmpty()) emptyList() else listOf(LibrarySection(null, sorted, false))
        }
        val recent = all.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(RECENT_LIMIT)
        return LibraryUiState(
            loading = false,
            totalCount = all.size,
            sections = sections,
            recent = recent,
            viewMode = p.viewMode,
            sortMode = p.sortMode,
            gridColumns = p.gridColumns,
            groupBySystem = p.groupBySystem,
            showFileName = p.showFileName,
            query = q,
            searching = s,
        )
    }

    /** Favorites first, then the chosen order. Title order uses a Korean collator so 가나다 sorts naturally. */
    private fun sort(games: List<GameEntity>, mode: SortMode): List<GameEntity> {
        val byTitle = Comparator<GameEntity> { a, b -> collator.compare(a.title, b.title) }
        val secondary: Comparator<GameEntity> = when (mode) {
            SortMode.TITLE -> byTitle
            SortMode.RECENT -> compareByDescending<GameEntity> { it.lastPlayedAt }.then(byTitle)
            SortMode.ADDED -> compareByDescending<GameEntity> { it.addedAt }.then(byTitle)
        }
        return games.sortedWith(compareByDescending<GameEntity> { it.favorite }.then(secondary))
    }

    // ---- top bar -------------------------------------------------------------------------------

    fun setQuery(q: String) { query.value = q }

    fun setSearching(active: Boolean) {
        searching.value = active
        if (!active) query.value = ""
    }

    fun setViewMode(mode: ViewMode) = launchIo { settings.set(Settings.Keys.viewMode, mode.name) }
    fun setSortMode(mode: SortMode) = launchIo { settings.set(Settings.Keys.sortMode, mode.name) }
    fun setGridColumns(cols: Int) = launchIo { settings.set(Settings.Keys.gridColumns, cols.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)) }
    fun setGroupBySystem(on: Boolean) = launchIo { settings.set(Settings.Keys.groupBySystem, on) }
    fun setShowFileName(on: Boolean) = launchIo { settings.set(Settings.Keys.showFileName, on) }

    fun toggleSection(section: LibrarySection) = launchIo {
        val current = settings.get(KEY_COLLAPSED_SECTIONS, "").split(',').filter { it.isNotBlank() }.toMutableSet()
        if (!current.add(section.key)) current.remove(section.key)
        settings.set(KEY_COLLAPSED_SECTIONS, current.joinToString(","))
    }

    // ---- launching -----------------------------------------------------------------------------

    /** Effective core for a game: explicit override if it exists and is available, else the system default. */
    fun coreFor(game: GameEntity): CoreInfo? {
        val system = SystemId.fromId(game.system)
        val override = game.coreId?.let { cores.core(it) }
        if (override != null && cores.isAvailable(override)) return override
        return system?.let { cores.defaultCoreFor(it) } ?: override
    }

    fun checkLaunch(game: GameEntity): LaunchCheck {
        if (!File(game.path).exists()) return LaunchCheck.MissingFile(game.path)
        val system = SystemId.fromId(game.system)
        val core = coreFor(game)
        if (core == null || !cores.isAvailable(core)) return LaunchCheck.NoCore(system)
        val systemDir = dirs.system
        val missing = core.bios.filter { b ->
            b.required && (b.system.isEmpty() || system == null || b.system == system.id) && !File(systemDir, b.file).exists()
        }
        return if (missing.isEmpty()) LaunchCheck.Ok else LaunchCheck.MissingBios(core, missing, systemDir)
    }

    // ---- per-game actions ----------------------------------------------------------------------

    fun observeGame(id: Long): Flow<GameEntity?> = db.games().observe(id)

    fun toggleFavorite(game: GameEntity) = launchIo { db.games().setFavorite(game.id, !game.favorite) }

    fun rename(game: GameEntity, title: String) = launchIo {
        val t = title.trim()
        if (t.isNotEmpty()) db.games().setTitle(game.id, t)
    }

    fun setCore(game: GameEntity, coreId: String?) = launchIo { db.games().setCore(game.id, coreId) }

    /** Manually added games are deleted outright; scanned ones are hidden so a rescan doesn't resurrect them. */
    fun remove(game: GameEntity) = launchIo {
        if (game.folderId == null) db.games().delete(game) else db.games().setHidden(game.id, true)
        deleteOwnedThumbnail(game)
        post(LibraryMessage(R.string.lib_msg_removed))
    }

    /** Copies the picked image into the thumbnails folder (max 512px, JPEG) and stores its path. */
    fun setThumbnail(gameId: Long, uri: Uri) = launchIo {
        val game = db.games().get(gameId) ?: return@launchIo
        val bmp = decodeScaled(uri, THUMB_MAX_PX)
        if (bmp == null) {
            post(LibraryMessage(R.string.lib_msg_thumbnail_failed))
            return@launchIo
        }
        val out = File(dirs.thumbnails, "${game.id}_${System.currentTimeMillis()}.jpg")
        val ok = runCatching { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.isSuccess
        bmp.recycle()
        if (!ok) {
            out.delete()
            post(LibraryMessage(R.string.lib_msg_thumbnail_failed))
            return@launchIo
        }
        deleteOwnedThumbnail(game)
        db.games().setThumbnail(game.id, out.absolutePath)
        post(LibraryMessage(R.string.lib_msg_thumbnail_set))
    }

    fun resetThumbnail(game: GameEntity) = launchIo {
        deleteOwnedThumbnail(game)
        db.games().setThumbnail(game.id, null)
    }

    /** Only delete thumbnail files we created ourselves (inside dirs.thumbnails, named "<id>_..."). */
    private fun deleteOwnedThumbnail(game: GameEntity) {
        val path = game.thumbnail ?: return
        val f = File(path)
        if (f.parentFile?.absolutePath == dirs.thumbnails.absolutePath && f.name.startsWith("${game.id}_")) f.delete()
    }

    private fun decodeScaled(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = minOf(maxPx.toFloat() / decoded.width, maxPx.toFloat() / decoded.height, 1f)
        if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    // ---- adding content ------------------------------------------------------------------------

    fun addFiles(uris: List<Uri>) = launchIo {
        var added = 0
        var unsupported = 0
        var notLocal = 0
        for (uri in uris) {
            val file = StorageAccess.treeUriToPath(app, uri)
            if (file == null || !file.isFile) { notLocal++; continue }
            if (scanner.addFile(file) != null) added++ else unsupported++
        }
        if (added > 0) post(LibraryMessage(R.string.lib_msg_files_added, listOf(added)))
        if (unsupported > 0) post(LibraryMessage(R.string.lib_msg_files_unsupported, listOf(unsupported)))
        if (notLocal > 0) post(LibraryMessage(R.string.lib_msg_files_not_local))
    }

    fun addFolder(uri: Uri) = launchIo {
        val dir = StorageAccess.treeUriToPath(app, uri)
        if (dir == null || !dir.isDirectory) {
            post(LibraryMessage(R.string.lib_msg_folder_not_local))
            return@launchIo
        }
        val id = db.folders().insert(FolderEntity(path = dir.absolutePath))
        if (id <= 0) {
            post(LibraryMessage(R.string.lib_msg_folder_exists))
            return@launchIo
        }
        scanner.scanFolder(FolderEntity(id = id, path = dir.absolutePath))
        post(LibraryMessage(R.string.lib_msg_folder_added))
    }

    fun rescanAll() = launchIo {
        if (db.folders().allOnce().isEmpty()) {
            post(LibraryMessage(R.string.lib_msg_no_folders))
            return@launchIo
        }
        scanner.scanAll()
        post(LibraryMessage(R.string.lib_msg_rescan_done))
    }

    // ---- folders screen ------------------------------------------------------------------------

    fun scanFolder(folder: FolderEntity) = launchIo { scanner.scanFolder(folder) }

    fun setFolderRecursive(folder: FolderEntity, recursive: Boolean) = launchIo {
        val updated = folder.copy(recursive = recursive)
        db.folders().update(updated)
        scanner.scanFolder(updated)
    }

    fun removeFolder(folder: FolderEntity) = launchIo {
        db.games().deleteByFolder(folder.id)
        db.folders().delete(folder)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { block() } }
    }

    private suspend fun post(msg: LibraryMessage) { _messages.emit(msg) }

    companion object {
        const val DEFAULT_GRID_COLUMNS = 3
        const val MIN_GRID_COLUMNS = 2
        const val MAX_GRID_COLUMNS = 4
        const val RECENT_LIMIT = 10
        private const val THUMB_MAX_PX = 512

        /** Comma-separated SystemId.ids (or "unknown") whose library section is collapsed. Library-local key. */
        val KEY_COLLAPSED_SECTIONS = stringPreferencesKey("lib_collapsed_sections")
    }
}
