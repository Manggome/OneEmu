package com.manggome.oneemu.emu.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.CheatEntity
import com.manggome.oneemu.emu.NativeBridge
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search progress survives closing the sheet (the core keeps the candidates; this keeps what the UI shows). */
private object FinderState {
    var gameId = -1L
    var size = 4
    var left: Long? = null
    var results: List<Pair<Long, Long>> = emptyList()
}

private const val SHOW_RESULTS = 50

/**
 * "치트 찾기": finds a value the game keeps in memory (money, HP, notes...) by searching for what's on screen,
 * changing it in the game and narrowing down, then changes it once or locks it as a saved cheat.
 * Only shown for cores with OneEmu's memory-search extension ([NativeBridge.hasMemSearch], the WIPI core).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheatFinder(gameId: Long, onChanged: suspend () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var supported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { supported = withContext(Dispatchers.IO) { NativeBridge.hasMemSearch() } }
    if (!supported) return

    if (FinderState.gameId != gameId) {
        FinderState.gameId = gameId
        FinderState.left = null
        FinderState.results = emptyList()
    }
    var size by remember { mutableStateOf(FinderState.size) }
    var left by remember { mutableStateOf(FinderState.left) }
    var results by remember { mutableStateOf(FinderState.results) }
    var valueText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val failed = stringResource(R.string.finder_unsupported)

    fun step(op: Int, value: Long) {
        if (busy) return
        busy = true
        scope.launch {
            val (n, list) = withContext(Dispatchers.IO) {
                val n = NativeBridge.memSearch(op, size, value)
                val raw = if (n in 1..SHOW_RESULTS.toLong()) NativeBridge.memSearchResults(SHOW_RESULTS) else LongArray(1)
                n to (1 until raw.size step 2).map { raw[it] to raw[it + 1] }
            }
            busy = false
            if (n < 0) { onMessage(failed); return@launch }
            left = n; results = list
            FinderState.size = size; FinderState.left = n; FinderState.results = list
        }
    }
    val value = valueText.toLongOrNull()

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.finder_title), style = MaterialTheme.typography.titleSmall, color = OneEmuColors.Accent)
        Text(stringResource(R.string.finder_help), style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = valueText,
                onValueChange = { t -> valueText = t.filter { it.isDigit() }.take(10) },
                label = { Text(stringResource(R.string.finder_value)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to R.string.finder_size1, 2 to R.string.finder_size2, 4 to R.string.finder_size4).forEach { (s, label) ->
                FilterChip(selected = size == s, enabled = left == null, onClick = { size = s }, label = { Text(stringResource(label)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = !busy && value != null, onClick = { step(0, value!!) }) { Text(stringResource(R.string.finder_new)) }
            if (left != null) {
                OutlinedButton(enabled = !busy && value != null, onClick = { step(1, value!!) }) { Text(stringResource(R.string.finder_equal)) }
                OutlinedButton(enabled = !busy, onClick = { step(5, 0) }) { Text(stringResource(R.string.finder_decreased)) }
                OutlinedButton(enabled = !busy, onClick = { step(4, 0) }) { Text(stringResource(R.string.finder_increased)) }
                OutlinedButton(enabled = !busy, onClick = { step(3, 0) }) { Text(stringResource(R.string.finder_unchanged)) }
                OutlinedButton(enabled = !busy, onClick = { step(2, 0) }) { Text(stringResource(R.string.finder_changed)) }
            }
        }
        left?.let { n ->
            Text(
                when {
                    busy -> stringResource(R.string.finder_searching)
                    n == 0L -> stringResource(R.string.finder_none)
                    n > SHOW_RESULTS -> stringResource(R.string.finder_many, n)
                    else -> stringResource(R.string.finder_left, n)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        results.forEach { (address, current) ->
            Row(
                Modifier.fillMaxWidth().clickable { picked = address to current }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("%08X".format(address), fontFamily = FontFamily.Monospace, color = OneEmuColors.OnSurfaceMuted)
                Spacer(Modifier.width(16.dp))
                Text(current.toString(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = OneEmuColors.OnSurfaceMuted.copy(alpha = 0.2f))
    }

    picked?.let { (address, current) ->
        FinderValueDialog(
            address = address,
            current = current,
            size = size,
            onDismiss = { picked = null },
            onWrite = { v ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { NativeBridge.memWrite(address, size, v) }
                    if (!ok) onMessage(failed)
                    results = results.map { if (it.first == address) address to v else it }
                    FinderState.results = results
                }
                picked = null
            },
            onLock = { name, v ->
                scope.launch {
                    val code = "%08X:%0${size * 2}X".format(address, v)
                    withContext(Dispatchers.IO) {
                        OneEmuApp.get().db.cheats().upsert(CheatEntity(gameId = gameId, name = name, code = code, enabled = true))
                    }
                    onChanged()
                }
                picked = null
            },
        )
    }
}

@Composable
private fun FinderValueDialog(
    address: Long,
    current: Long,
    size: Int,
    onDismiss: () -> Unit,
    onWrite: (Long) -> Unit,
    onLock: (name: String, value: Long) -> Unit,
) {
    val max = if (size >= 4) 0xFFFFFFFFL else (1L shl (size * 8)) - 1
    var text by remember { mutableStateOf(current.toString()) }
    var name by remember { mutableStateOf("") }
    val v = text.toLongOrNull()?.takeIf { it in 0..max }
    val defaultName = stringResource(R.string.finder_default_name, "%08X".format(address))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("%08X".format(address), fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() }.take(10) },
                    label = { Text(stringResource(R.string.finder_new_value, max)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cheat_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.finder_lock_help), style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
            }
        },
        confirmButton = {
            TextButton(enabled = v != null, onClick = { onLock(name.ifBlank { defaultName }, v!!) }) { Text(stringResource(R.string.finder_lock)) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = v != null, onClick = { onWrite(v!!) }) { Text(stringResource(R.string.finder_write)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
