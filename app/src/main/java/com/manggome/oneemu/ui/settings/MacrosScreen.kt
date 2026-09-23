package com.manggome.oneemu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.input.GamepadMapping
import com.manggome.oneemu.emu.input.Macro
import com.manggome.oneemu.emu.input.MacroStep
import com.manggome.oneemu.emu.input.Macros
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 매크로: named button sequences a pad key can play (게임패드 → 버튼 배치 → 매크로 버튼). Each is a list of
 * steps - buttons held for so many frames, a pause, or 빠른 저장/불러오기 - built from a preset or empty.
 */
@Composable
internal fun MacrosScreen(onBack: () -> Unit) {
    val settings = OneEmuApp.get().settings
    val scope = rememberCoroutineScope()
    val macros by remember { Macros.observe(settings) }.collectAsState(emptyList())
    var editing by remember { mutableStateOf<Macro?>(null) }
    var choosingPreset by remember { mutableStateOf(false) }

    fun save(list: List<Macro>) = scope.launch { Macros.save(settings, list) }

    SettingsScaffold(title = stringResource(R.string.macros_title), onBack = onBack) {
        NoteText(stringResource(R.string.macros_desc))
        for (m in macros) {
            SettingsRow(
                title = m.name,
                subtitle = m.summary(),
                icon = Icons.Outlined.Keyboard,
                trailing = {
                    IconButton(onClick = { save(macros.filter { it.id != m.id }) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.macros_delete))
                    }
                },
                onClick = { editing = m },
            )
        }
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.macros_add), icon = Icons.Outlined.Add, onClick = { choosingPreset = true })
    }

    if (choosingPreset) {
        AlertDialog(
            onDismissRequest = { choosingPreset = false },
            title = { Text(stringResource(R.string.macros_add)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.macros_empty),
                        Modifier.fillMaxWidth().clickable {
                            choosingPreset = false
                            editing = Macro(Macros.nextId(macros), "매크로 ${macros.size + 1}", emptyList())
                        }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    for ((name, steps) in Macros.PRESETS) {
                        Text(
                            name,
                            Modifier.fillMaxWidth().clickable {
                                choosingPreset = false
                                editing = Macro(Macros.nextId(macros), name, steps)
                            }.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingPreset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { m ->
        MacroEditDialog(
            initial = m,
            onSave = { edited ->
                editing = null
                save(if (macros.any { it.id == edited.id }) macros.map { if (it.id == edited.id) edited else it } else macros + edited)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MacroEditDialog(initial: Macro, onSave: (Macro) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps) }
    var stepEdit by remember { mutableStateOf<Pair<Int, MacroStep>?>(null) } // index (-1 = new), step

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.macros_edit)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.macros_name)) }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.macros_total, steps.filter { it.action == null }.sumOf { it.frames } / 60f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(steps) { i, step ->
                        Row(
                            Modifier.fillMaxWidth().clickable { stepEdit = i to step }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}.", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(stepText(step), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.macros_step_delete))
                            }
                        }
                    }
                }
                TextButton(onClick = { stepEdit = -1 to MacroStep() }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.macros_step_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initial.copy(name = name.ifBlank { initial.name }, steps = steps)) }, enabled = steps.isNotEmpty()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    stepEdit?.let { (index, step) ->
        StepDialog(
            initial = step,
            onSave = { s ->
                stepEdit = null
                steps = if (index < 0) steps + s else steps.mapIndexed { j, old -> if (j == index) s else old }
            },
            onDismiss = { stepEdit = null },
        )
    }
}

@Composable
private fun stepText(step: MacroStep): String = when (step.action) {
    MacroStep.ACTION_QUICK_SAVE -> stringResource(R.string.macros_action_quick_save)
    MacroStep.ACTION_QUICK_LOAD -> stringResource(R.string.macros_action_quick_load)
    else -> stringResource(
        R.string.macros_step_hold,
        if (step.buttons == 0) stringResource(R.string.macros_pause) else Macros.label(step.buttons),
        step.frames,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepDialog(initial: MacroStep, onSave: (MacroStep) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(initial.action ?: "") }
    var mask by remember { mutableStateOf(initial.buttons) }
    var frames by remember { mutableStateOf(initial.frames.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.macros_step)) },
        text = {
            Column {
                listOf("" to R.string.macros_kind_buttons, MacroStep.ACTION_QUICK_SAVE to R.string.macros_action_quick_save, MacroStep.ACTION_QUICK_LOAD to R.string.macros_action_quick_load)
                    .forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { kind = value }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = kind == value, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 8.dp))
                        }
                    }
                if (kind.isEmpty()) {
                    Text(
                        stringResource(R.string.macros_buttons_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((name, bit) in GamepadMapping.COMBO_BUTTONS) {
                            FilterChip(selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text(buttonName(name)) })
                        }
                    }
                    Text(stringResource(R.string.macros_frames, frames.roundToInt(), frames / 60f), Modifier.padding(top = 8.dp))
                    Slider(value = frames, onValueChange = { frames = it }, valueRange = 1f..60f, steps = 58)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (kind.isEmpty()) MacroStep(mask, frames.roundToInt()) else MacroStep(action = kind))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun buttonName(name: String): String = when (name) {
    "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"
    else -> name
}
