package com.manggome.oneemu.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.emu.input.StickDpad
import com.manggome.oneemu.emu.pad.PadProfile
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlin.math.roundToInt

/*
 * Building blocks for the two pad editors' tool panel. The panel does one thing at a time: with nothing
 * selected it is a single row of tools; with something selected it shows that thing's own settings and a
 * way back. Settings that apply to the whole editor (opacity, grid, axis lock, where the panel sits) live
 * behind 설정 rather than taking a row each on screen.
 */

/** One entry in [EditorToolRow]. */
data class EditorTool(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** The idle panel: tools spread evenly, icon over a short label, each a comfortable thumb target. */
@Composable
fun EditorToolRow(tools: List<EditorTool>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        tools.forEach { t ->
            val tint = if (t.selected) OneEmuColors.Accent else OneEmuColors.OnSurface
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = t.onClick)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Top of the panel while something is selected: what it is, room for its quick actions, and 완료 to go
 * back to the tools. Without a way back the only exit was tapping empty canvas, which nobody finds.
 */
@Composable
fun SelectionHeader(title: String, onDone: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = OneEmuColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
        TextButton(onClick = onDone) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.le_done))
        }
    }
}

/** A labelled 0.x-1.x slider with its value in percent. */
@Composable
fun PercentSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    onFinished: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        Slider(value = value, onValueChange = onValue, onValueChangeFinished = onFinished, valueRange = range, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

/** A setting row: title, optional explanation, switch. The whole row toggles. */
@Composable
fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 설정: what applies to the whole editor. [extra] lets an editor add its own lines (the skin editor's
 * variant and overlay-only buttons, say) without growing the panel itself.
 */
@Composable
fun EditorSettingsDialog(
    opacity: Float,
    onOpacity: (Float) -> Unit,
    snap: Boolean?,
    onSnap: (Boolean) -> Unit,
    chrome: EditorChromeState,
    onDismiss: () -> Unit,
    /** The pad being edited, for the settings that belong to it rather than to the layout. */
    profile: PadProfile? = null,
    extra: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.le_settings)) },
        text = {
            Column {
                PercentSlider(stringResource(R.string.le_opacity), opacity, onOpacity, 0.15f..1f)
                if (snap != null) SwitchLine(stringResource(R.string.le_snap), snap, onSnap, stringResource(R.string.le_snap_desc))
                SwitchLine(
                    stringResource(R.string.le_axis_lock),
                    chrome.axisLock,
                    { chrome.axisLock = it },
                    stringResource(R.string.le_axis_lock_desc),
                )
                if (profile != null) StickDpadLine(profile)
                LocalPerGameLayout.current?.let { toggle ->
                    SwitchLine(
                        stringResource(R.string.le_per_game),
                        toggle.on,
                        toggle.set,
                        stringResource(R.string.le_per_game_desc),
                    )
                }
                extra()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** One line of a menu-like dialog: title, optional explanation, chevron. */
@Composable
fun MenuLine(title: String, description: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = OneEmuColors.OnSurfaceMuted)
    }
}

/**
 * [StickDpad] for [profile], saved as soon as it is flipped (it is a setting of the pad, not part of the
 * layout, so 저장/취소 do not apply to it).
 */
@Composable
fun StickDpadLine(profile: PadProfile) {
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val on by settings.observe(StickDpad.key(profile), false).collectAsState(false)
    val everywhere by settings.observe(StickDpad.everywhere, false).collectAsState(false)
    SwitchLine(
        stringResource(R.string.le_stick_dpad),
        on || everywhere,
        { v -> scope.launch { settings.set(StickDpad.key(profile), v) } },
        stringResource(if (everywhere) R.string.le_stick_dpad_everywhere else R.string.le_stick_dpad_desc),
    )
}

/** 이 게임만 따로 배치, offered by the editor only when it is opened from a running game. */
data class PerGameLayoutToggle(val on: Boolean, val set: (Boolean) -> Unit)

val LocalPerGameLayout = androidx.compose.runtime.compositionLocalOf<PerGameLayoutToggle?> { null }
