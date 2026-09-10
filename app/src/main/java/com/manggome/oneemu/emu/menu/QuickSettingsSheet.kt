package com.manggome.oneemu.emu.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession
import com.manggome.oneemu.emu.NativeBridge
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch

/**
 * In-game quick settings. Video/audio changes are pushed to the native side immediately and
 * persisted through [Settings]; the pad settings are observed live by the overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(session: EmulatorSession, onEditLayout: () -> Unit, onDismiss: () -> Unit) {
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(Page.MAIN) }

    val linear by settings.observe(Settings.Keys.videoLinearFilter, false).collectAsState(false)
    val aspect by settings.observe(Settings.Keys.videoAspect, 0).collectAsState(0)
    val opacity by settings.observe(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY).collectAsState(Settings.DEFAULT_PAD_OPACITY)
    val padScale by settings.observe(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE).collectAsState(Settings.DEFAULT_PAD_SCALE)
    val vibration by settings.observe(Settings.Keys.padVibration, true).collectAsState(true)
    val showFps by settings.observe(Settings.Keys.showFps, false).collectAsState(false)
    val audio by settings.observe(Settings.Keys.audioEnabled, true).collectAsState(true)

    fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) = scope.launch { settings.set(key, value) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = OneEmuColors.Surface) {
        when (page) {
            Page.MAIN -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.qs_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                SectionLabel(stringResource(R.string.qs_filter))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    SegmentedButton(
                        selected = linear, onClick = { put(Settings.Keys.videoLinearFilter, true); NativeBridge.setVideoConfig(true, aspect) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.qs_filter_smooth)) }
                    SegmentedButton(
                        selected = !linear, onClick = { put(Settings.Keys.videoLinearFilter, false); NativeBridge.setVideoConfig(false, aspect) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.qs_filter_pixel)) }
                }

                val aspectLabels = listOf(
                    stringResource(R.string.qs_aspect_core), stringResource(R.string.qs_aspect_stretch),
                    stringResource(R.string.qs_aspect_integer), stringResource(R.string.qs_aspect_square),
                )
                DropdownRow(stringResource(R.string.qs_aspect), aspectLabels.getOrElse(aspect) { aspectLabels[0] }, aspectLabels) { idx ->
                    put(Settings.Keys.videoAspect, idx); NativeBridge.setVideoConfig(linear, idx)
                }

                SliderRow(stringResource(R.string.qs_pad_opacity), opacity, 0.15f..1f, "${(opacity * 100).toInt()}%") { put(Settings.Keys.padOpacity, it) }
                SliderRow(stringResource(R.string.qs_pad_scale), padScale, 0.7f..1.5f, "${(padScale * 100).toInt()}%") { put(Settings.Keys.padScale, it) }

                SwitchRow(stringResource(R.string.qs_vibration), vibration) { put(Settings.Keys.padVibration, it) }
                SwitchRow(stringResource(R.string.qs_show_fps), showFps) { put(Settings.Keys.showFps, it) }
                SwitchRow(stringResource(R.string.qs_audio), audio) { put(Settings.Keys.audioEnabled, it); NativeBridge.setAudioMuted(!it) }

                NavRow(stringResource(R.string.qs_core_options)) { page = Page.CORE_OPTIONS }
                NavRow(stringResource(R.string.qs_edit_layout)) { onEditLayout() }
            }
            Page.CORE_OPTIONS -> CoreOptionsPage(session, onBack = { page = Page.MAIN })
        }
    }
}

private enum class Page { MAIN, CORE_OPTIONS }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, color = OneEmuColors.Accent)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = OneEmuColors.OnSurfaceMuted)
    }
}

@Composable
private fun DropdownRow(label: String, current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(current, color = OneEmuColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/** Core options grouped by category with a dropdown per option. */
@Composable
private fun CoreOptionsPage(session: EmulatorSession, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<EmulatorSession.CoreOption>>(emptyList()) }
    LaunchedEffect(Unit) { options = session.coreOptions().filter { it.visible } }
    val general = stringResource(R.string.qs_category_general)
    val grouped = remember(options) { options.groupBy { it.category.ifBlank { general } } }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.qs_core_options), style = MaterialTheme.typography.titleMedium)
        }
        if (options.isEmpty()) {
            Text(stringResource(R.string.qs_core_options_empty), color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(20.dp))
            Spacer(Modifier.height(24.dp))
            return
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            grouped.forEach { (category, list) ->
                item(key = "cat-$category") { SectionLabel(category) }
                items(list, key = { it.key }) { opt ->
                    val labels = opt.values.map { it.second }
                    val currentLabel = opt.values.firstOrNull { it.first == opt.current }?.second ?: opt.current
                    DropdownRow(opt.desc.ifBlank { opt.key }, currentLabel, labels) { idx ->
                        val value = opt.values[idx].first
                        scope.launch {
                            session.setCoreOption(opt.key, value)
                            options = session.coreOptions().filter { it.visible }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
