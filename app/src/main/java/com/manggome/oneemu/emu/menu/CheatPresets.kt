package com.manggome.oneemu.emu.menu

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.CheatEntity
import com.manggome.oneemu.emu.NativeBridge
import com.manggome.oneemu.library.WipiCompat
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-game cheat presets for feature-phone (WIPI) games ("게임 치트"): the player picks a value such as 별 or 골드
 * and types any number. The catalog ships in the APK (cores/wipi/assets/cheats.json -> coreassets/wipi/) keyed
 * by the game's PID. Its codes use locators that survive a restart (cores/wipi/wie_libretro/src/cheat.rs):
 * `P:BASE>OFF:{v}` pointer chains from the game binary, `J:Class.field.field:{v}` Java field paths; `{v}` is
 * replaced by the number.
 */
object WipiCheatCatalog {
    private const val TAG = "WipiCheats"
    const val BUNDLED_ASSET = "coreassets/wipi/cheats.json"
    const val VALUE = "{v}"

    @Serializable
    data class FileRef(val sha256: String, val size: Long = 0)

    @Serializable
    data class Preset(
        val name: String,
        /** Code with [VALUE] where the number goes. */
        val code: String,
        /** Value range of the location: u8, u16, i16, u32, i32 or i64. */
        val type: String = "i32",
        val min: Long? = null,
        val max: Long? = null,
        /** Suggested number for the input field. */
        val default: Long? = null,
        val note: String = "",
    ) {
        val lowest: Long get() = min ?: 0L
        val highest: Long
            get() = max ?: when (type) {
                "u8" -> 0xFF
                "i16" -> Short.MAX_VALUE.toLong()
                "u16" -> 0xFFFF
                "u32" -> 0xFFFFFFFFL
                "i64" -> Long.MAX_VALUE
                else -> Int.MAX_VALUE.toLong()
            }

        fun fill(value: Long): String = code.replace(VALUE, value.toString())

        /** The number in [code] when it is this preset filled in, else null. */
        fun valueIn(code: String): Long? {
            val at = this.code.indexOf(VALUE)
            if (at < 0) return null
            val prefix = this.code.substring(0, at)
            val suffix = this.code.substring(at + VALUE.length)
            val code = code.trim()
            if (!code.startsWith(prefix) || !code.endsWith(suffix) || code.length < prefix.length + suffix.length) return null
            return code.substring(prefix.length, code.length - suffix.length).toLongOrNull()
        }
    }

    @Serializable
    data class Game(
        val title: String,
        val carrier: String = "",
        val pid: String,
        /** Files the presets were verified on; another file of the same PID may be a different build. */
        val files: List<FileRef> = emptyList(),
        val cheats: List<Preset> = emptyList(),
    )

    @Serializable
    data class Catalog(val schema: Int = 1, val updated: String = "", val games: List<Game> = emptyList())

    /** Presets for one game file; [verified] is false when the file is another build of that PID. */
    data class Match(val game: Game, val verified: Boolean)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var catalog: Catalog? = null

    suspend fun catalog(context: Context): Catalog = catalog ?: mutex.withLock {
        catalog ?: withContext(Dispatchers.IO) {
            runCatching { context.assets.open(BUNDLED_ASSET).use { json.decodeFromString<Catalog>(it.readBytes().decodeToString()) } }
                .getOrElse { Log.w(TAG, "cheat catalog unreadable", it); Catalog() }
                .also { catalog = it }
        }
    }

    suspend fun forGame(context: Context, path: String): Match? {
        val games = catalog(context).games
        if (games.isEmpty()) return null
        val id = WipiCompat.get(context).resolve(path).identity ?: return null
        val game = games.firstOrNull { it.pid.isNotEmpty() && it.pid.equals(id.pid, ignoreCase = true) } ?: return null
        if (game.cheats.isEmpty()) return null
        val verified = game.files.isEmpty() || game.files.any { it.sha256.equals(id.sha256, ignoreCase = true) }
        return Match(game, verified)
    }
}

/** `retro_cheat_set` slots for one-shot writes, above the ones [com.manggome.oneemu.emu.EmulatorSession.applyCheats] uses. */
private var nextOnceSlot = 10_000

/**
 * "게임 치트": the running game's presets from [WipiCheatCatalog]. Tapping one asks for a number, then either keeps it
 * there ([고정]: saved as a cheat in the list below, written every frame while on) or writes it a single time
 * ([한 번 적용]: the game can change it afterwards; nothing is saved). Renders nothing for games without presets.
 */
@Composable
fun CheatPresets(gameId: Long, gamePath: String, onChanged: suspend () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var match by remember(gamePath) { mutableStateOf<WipiCheatCatalog.Match?>(null) }
    LaunchedEffect(gamePath) { match = runCatching { WipiCheatCatalog.forGame(context, gamePath) }.getOrNull() }
    val found = match ?: return
    val dao = remember { OneEmuApp.get().db.cheats() }
    val cheats by remember(gameId) { dao.observeForGame(gameId) }.collectAsState(initial = emptyList())
    var picked by remember { mutableStateOf<WipiCheatCatalog.Preset?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.preset_title), style = MaterialTheme.typography.titleSmall, color = OneEmuColors.Accent)
        Text(
            stringResource(if (found.verified) R.string.preset_help else R.string.preset_other_edition),
            style = MaterialTheme.typography.bodySmall,
            color = if (found.verified) OneEmuColors.OnSurfaceMuted else OneEmuColors.Danger,
        )
        Spacer(Modifier.height(4.dp))
        found.game.cheats.forEach { preset ->
            val locked = cheats.firstNotNullOfOrNull { c -> preset.valueIn(c.code)?.takeIf { c.enabled } }
            Row(Modifier.fillMaxWidth().clickable { picked = preset }.padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                    if (preset.note.isNotBlank()) {
                        Text(preset.note, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
                    }
                }
                if (locked != null) {
                    Text(stringResource(R.string.preset_locked_at, locked), style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.Accent)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = OneEmuColors.OnSurfaceMuted.copy(alpha = 0.2f))
    }

    picked?.let { preset ->
        val existing = cheats.firstOrNull { preset.valueIn(it.code) != null }
        PresetValueDialog(
            preset = preset,
            initial = existing?.let { preset.valueIn(it.code) } ?: preset.default,
            onDismiss = { picked = null },
            onLock = { value ->
                picked = null
                scope.launch {
                    val name = context.getString(R.string.preset_lock_name, preset.name, value)
                    withContext(Dispatchers.IO) {
                        dao.upsert(
                            existing?.copy(name = name, code = preset.fill(value), enabled = true)
                                ?: CheatEntity(gameId = gameId, name = name, code = preset.fill(value), enabled = true),
                        )
                    }
                    onChanged()
                    onMessage(context.getString(R.string.preset_locked, preset.name, value))
                }
            },
            onOnce = { value ->
                picked = null
                scope.launch {
                    // A lock on the same value would overwrite the one-time number every frame.
                    if (existing != null && existing.enabled) {
                        withContext(Dispatchers.IO) { dao.upsert(existing.copy(enabled = false)) }
                        onChanged()
                    }
                    withContext(Dispatchers.IO) { NativeBridge.setCheat(nextOnceSlot++, true, "once:" + preset.fill(value)) }
                    onMessage(context.getString(R.string.preset_applied, preset.name, value))
                }
            },
        )
    }
}

@Composable
private fun PresetValueDialog(
    preset: WipiCheatCatalog.Preset,
    initial: Long?,
    onDismiss: () -> Unit,
    onLock: (Long) -> Unit,
    onOnce: (Long) -> Unit,
) {
    val lowest = preset.lowest
    val highest = preset.highest
    var text by remember { mutableStateOf(initial?.toString().orEmpty()) }
    val value = text.toLongOrNull()?.takeIf { it in lowest..highest }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0 && lowest < 0) }.take(19) },
                    label = { Text(stringResource(R.string.preset_value, lowest, highest)) },
                    singleLine = true,
                    isError = text.isNotEmpty() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.preset_dialog_help), style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { onLock(value!!) }) { Text(stringResource(R.string.preset_lock)) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = value != null, onClick = { onOnce(value!!) }) { Text(stringResource(R.string.preset_once)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
