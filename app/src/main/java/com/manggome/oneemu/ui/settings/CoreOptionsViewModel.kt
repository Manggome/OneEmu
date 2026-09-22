package com.manggome.oneemu.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.core.CoreInfo
import com.manggome.oneemu.emu.GameQuirks
import com.manggome.oneemu.emu.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One libretro core option as reported by the native frontend. */
data class CoreOption(
    val key: String,
    val desc: String,
    val info: String,
    val category: String,
    val current: String,
    val default: String,
    val visible: Boolean,
    val values: List<Pair<String, String>>, // value -> label
) {
    fun labelFor(value: String): String = values.firstOrNull { it.first == value }?.second ?: value
}

/**
 * Loads a core without a game just long enough to read its option table, then unloads it.
 * Overrides are persisted through Settings and take effect on the next game start.
 *
 * With a [gameId] the screen edits that one game instead of the core: the core-wide settings become
 * the baseline every row falls back to, and only what the user changes here is stored against the
 * game. A game with nothing stored plays exactly as it did before.
 */
class CoreOptionsViewModel(private val coreId: String, private val gameId: Long = 0L) : ViewModel() {
    private val perGame: Boolean get() = gameId > 0
    sealed interface State {
        data object Loading : State
        data object Running : State
        data class Error(val message: String) : State
        data class Ready(
            val core: CoreInfo,
            val options: List<CoreOption>,
            /** What this screen has stored: the core's own overrides, or the game's. */
            val overrides: Map<String, String>,
            /** What a row falls back to when its override is removed. */
            val baseline: Map<String, String>,
            /** True while editing one game rather than the core. */
            val perGame: Boolean,
            /** Explains the options this one game gets by default; null when it has none. */
            val quirkNote: String? = null,
        ) : State
    }

    private val app = OneEmuApp.get()
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            val core = app.cores.core(coreId)
            if (core == null) { _state.value = State.Error("unknown core: $coreId"); return@launch }
            _state.value = withContext(Dispatchers.IO) { readOptions(core) }
        }
    }

    private suspend fun readOptions(core: CoreInfo): State = probeMutex.withLock {
        if (NativeBridge.isRunning()) return State.Running
        val libPath = app.cores.libraryPath(core)
        if (!libPath.exists()) return State.Error("core library missing: ${libPath.name}")
        val coreOverrides = app.settings.coreOptionOverrides(core.id)
        // Options a specific game needs (GameQuirks) are part of what its rows fall back to, so the screen
        // shows them as the value in force and the user can still override any of them.
        val quirk = if (perGame) app.db.games().get(gameId)?.let { GameQuirks.forGame(core.id, it) } else null
        val baseline = core.defaultOptions + coreOverrides + quirk?.options.orEmpty()
        val overrides = if (perGame) app.settings.gameOptionOverrides(gameId) else coreOverrides
        val merged = baseline.toMutableMap().apply { putAll(overrides) }
        val blob = merged.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val systemId = core.systems.firstOrNull() ?: "misc"
        runCatching { app.cores.installAssets(core, app.dirs.system) }.onFailure { Log.w(TAG, "installAssets failed", it) }
        try {
            if (!NativeBridge.loadCore(libPath.absolutePath, app.dirs.system.absolutePath, app.dirs.saves(systemId).absolutePath, blob)) {
                return State.Error(NativeBridge.lastError())
            }
            val options = parseOptions(NativeBridge.getOptions())
            return State.Ready(core, options, overrides, baseline, perGame, quirk?.note)
        } catch (t: Throwable) {
            Log.e(TAG, "option probe failed", t)
            return State.Error(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { NativeBridge.unload() }.onFailure { Log.w(TAG, "unload failed", it) }
        }
    }

    fun setValue(key: String, value: String) {
        val s = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            store(s.core.id, key, value)
            _state.update { st ->
                if (st !is State.Ready) st
                else st.copy(
                    options = st.options.map { if (it.key == key) it.copy(current = value) else it },
                    overrides = st.overrides + (key to value),
                )
            }
        }
    }

    /**
     * Removes the override; the value falls back to the baseline — the core-wide setting when
     * editing one game, core.json's default and then the core's own otherwise.
     */
    fun resetOption(key: String) {
        val s = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            store(s.core.id, key, null)
            _state.update { st ->
                if (st !is State.Ready) st
                else st.copy(
                    options = st.options.map { if (it.key == key) it.copy(current = st.baseline[key] ?: it.default) else it },
                    overrides = st.overrides - key,
                )
            }
        }
    }

    fun resetAll() {
        val s = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            val keys = com.manggome.oneemu.data.Settings.Keys
            app.settings.set(if (perGame) keys.gameOptions(gameId) else keys.coreOptions(s.core.id), "")
            _state.update { st ->
                if (st !is State.Ready) st
                else st.copy(
                    options = st.options.map { it.copy(current = st.baseline[it.key] ?: it.default) },
                    overrides = emptyMap(),
                )
            }
        }
    }

    private suspend fun store(coreId: String, key: String, value: String?) {
        if (perGame) app.settings.setGameOptionOverride(gameId, key, value)
        else app.settings.setCoreOptionOverride(coreId, key, value)
    }

    companion object {
        private const val TAG = "CoreOptions"
        /** Only one probe at a time: the native frontend is a singleton. */
        private val probeMutex = Mutex()

        /** Format: key\tdesc\tinfo\tcategory\tcurrent\tdefault\tvisible\tval=label|val=label */
        fun parseOptions(raw: String): List<CoreOption> = raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 8) return@mapNotNull null
            val values = p[7].split('|').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', it) }
            CoreOption(p[0], p[1], p[2], p[3], p[4], p[5], p[6] == "1", values)
        }.toList()
    }
}
