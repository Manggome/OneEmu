package com.manggome.oneemu.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Chrome shared by both pad editors: top bar, an auto-hiding tool panel that can sit at the bottom or
 * the top edge and be collapsed to a handle, a dismissible one-line hint chip, and the position readout
 * shown while dragging.
 */

/** Preferences owned by the layout editors. */
object EditorPrefs {
    /** The one-line usage hint was dismissed. */
    val hintDismissed = booleanPreferencesKey("layout_editor_hint_dismissed")
    /** Tool panel docked at the top edge instead of the bottom. */
    val panelAtTop = booleanPreferencesKey("layout_editor_panel_top")
}

@Stable
class EditorChromeState internal constructor(private val settings: Settings, private val scope: CoroutineScope) {
    var collapsed by mutableStateOf(false)
    /** Top bar folded into a small corner pill, so the top of the pad can be edited too. */
    var barFolded by mutableStateOf(false)
    var atTop by mutableStateOf(false)
        private set
    var hintDismissed by mutableStateOf(true)
        private set
    /** 축 고정 toggle (the PPT Shift equivalent that stays on). */
    var axisLock by mutableStateOf(false)

    internal suspend fun load() {
        atTop = settings.get(EditorPrefs.panelAtTop, false)
        hintDismissed = settings.get(EditorPrefs.hintDismissed, false)
    }

    fun toggleEdge() {
        atTop = !atTop
        scope.launch { settings.set(EditorPrefs.panelAtTop, atTop) }
    }

    fun dismissHint() {
        hintDismissed = true
        scope.launch { settings.set(EditorPrefs.hintDismissed, true) }
    }
}

@Composable
fun rememberEditorChromeState(): EditorChromeState {
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val state = remember { EditorChromeState(settings, scope) }
    LaunchedEffect(state) { state.load() }
    return state
}

/**
 * Overlays the editor chrome on the canvas. [dragging] hides the panel and hint (fade to 0, no touch
 * interception) and brings them back 300 ms after release. [panel] is the editor-specific content shown
 * under the panel's handle row when expanded.
 */
@Composable
fun BoxScope.EditorChrome(
    state: EditorChromeState,
    title: String,
    subtitle: String?,
    orientationToggle: (@Composable () -> Unit)?,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    dragging: Boolean,
    hint: String,
    readout: String?,
    panel: @Composable ColumnScope.() -> Unit,
) {
    var panelShown by remember { mutableStateOf(true) }
    LaunchedEffect(dragging) {
        if (dragging) panelShown = false else { delay(300); panelShown = true }
    }
    val panelAlpha by animateFloatAsState(if (panelShown) 1f else 0f, tween(if (panelShown) 200 else 120), label = "panelAlpha")

    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
        if (state.barFolded) {
            // Folded: only what cannot wait - unfold, and 저장 - in a pill at the top-right corner.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.End) {
                Surface(color = OneEmuColors.Surface.copy(alpha = 0.92f), shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallIconButton(Icons.Filled.ExpandMore, stringResource(R.string.le_bar_unfold), { state.barFolded = false }, size = 40.dp)
                        TextButton(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.le_save)) }
                    }
                }
            }
        } else {
            // One slim bar: leave, what is being edited, undo/redo (they apply to everything, so they live
            // here rather than in the panel), save, and fold it away. The screen-shape selector gets its own
            // line under it, so neither has to be squeezed until the text is cut off.
            Surface(color = OneEmuColors.Surface.copy(alpha = 0.92f)) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallIconButton(Icons.Filled.Close, stringResource(R.string.le_cancel), onCancel, size = 44.dp)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = OneEmuColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = OneEmuColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        SmallIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.le_undo), onUndo, enabled = canUndo, size = 44.dp)
                        SmallIconButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.le_redo), onRedo, enabled = canRedo, size = 44.dp)
                        Button(onClick = onSave, enabled = saveEnabled, contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Text(stringResource(R.string.le_save))
                        }
                        SmallIconButton(Icons.Filled.ExpandLess, stringResource(R.string.le_bar_fold), { state.barFolded = true }, size = 44.dp)
                    }
                    if (orientationToggle != null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) { orientationToggle() }
                    }
                }
            }
        }
        if (state.atTop) {
            ToolPanel(state, panelAlpha, panel)
        }
        if (!state.hintDismissed) HintChip(hint, panelAlpha, onDismiss = { state.dismissHint() })
        if (readout != null && state.atTop) Readout(readout)
    }

    if (readout != null && !state.atTop) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { Readout(readout) }
    }
    if (!state.atTop) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ToolPanel(state, panelAlpha, panel)
        }
    }
}

/** Skips placement (and therefore hit-testing) while faded out, so touches reach the canvas below. */
private fun Modifier.fadeAndPassThrough(alpha: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) {
        if (alpha > 0.02f) p.placeWithLayer(0, 0) { this.alpha = alpha }
    }
}

@Composable
private fun ToolPanel(
    state: EditorChromeState,
    alpha: Float,
    panel: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (state.atTop) RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Surface(
        Modifier.fillMaxWidth().fadeAndPassThrough(alpha),
        color = OneEmuColors.Surface.copy(alpha = 0.95f),
        shape = shape,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.then(if (state.atTop) Modifier else Modifier.navigationBarsPadding()).padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            // Handle row: fold the panel down to this row, or send it to the other edge when it is over
            // the buttons being edited. One tap each, right where the thumb already is.
            Box(Modifier.fillMaxWidth().height(36.dp)) {
                SmallIconButton(
                    if (state.collapsed == state.atTop) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    stringResource(if (state.collapsed) R.string.le_panel_expand else R.string.le_panel_collapse),
                    { state.collapsed = !state.collapsed },
                    size = 36.dp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Box(
                    Modifier.align(Alignment.Center)
                        .size(width = 36.dp, height = 4.dp)
                        .background(OneEmuColors.OnSurfaceMuted.copy(alpha = 0.4f), RoundedCornerShape(50)),
                )
                SmallIconButton(
                    if (state.atTop) Icons.Filled.VerticalAlignBottom else Icons.Filled.VerticalAlignTop,
                    stringResource(if (state.atTop) R.string.le_panel_to_bottom else R.string.le_panel_to_top),
                    { state.toggleEdge() },
                    size = 36.dp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            if (!state.collapsed) panel()
        }
    }
}

@Composable
private fun HintChip(text: String, alpha: Float, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().fadeAndPassThrough(alpha).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.background(OneEmuColors.Surface.copy(alpha = 0.9f), RoundedCornerShape(50)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.le_hint_dismiss), onDismiss, size = 28.dp)
        }
    }
}

@Composable
private fun Readout(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = OneEmuColors.OnSurface,
            modifier = Modifier.background(OneEmuColors.Surface.copy(alpha = 0.9f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SmallIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    tint: androidx.compose.ui.graphics.Color = OneEmuColors.OnSurface,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size)) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.6f))
    }
}

/** Icon button that fires [onTick] on press and repeats while held (400 ms delay, then every 60 ms). */
@Composable
private fun RepeatIconButton(icon: ImageVector, description: String, enabled: Boolean, onTick: () -> Unit, onRelease: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            try {
                onTick()
                delay(400)
                while (true) { onTick(); delay(60) }
            } finally {
                onRelease()
            }
        }
    }
    IconButton(onClick = {}, enabled = enabled, interactionSource = interaction, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, tint = OneEmuColors.OnSurface.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

/** ◀ ▲ ▼ ▶ 1 dp nudge buttons for the selected element(s). */
@Composable
fun NudgeButtons(enabled: Boolean, onNudge: (dxDp: Int, dyDp: Int) -> Unit, onRelease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.le_nudge_left), enabled, { onNudge(-1, 0) }, onRelease)
        RepeatIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.le_nudge_up), enabled, { onNudge(0, -1) }, onRelease)
        RepeatIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.le_nudge_down), enabled, { onNudge(0, 1) }, onRelease)
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.le_nudge_right), enabled, { onNudge(1, 0) }, onRelease)
    }
}

/** 정렬/배치 actions for a multi-selection. */
@Composable
fun AlignToolbar(
    count: Int,
    onAlignRow: () -> Unit,
    onAlignColumn: () -> Unit,
    onDistributeH: () -> Unit,
    onDistributeV: () -> Unit,
    onMirror: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAlignRow) { Text(stringResource(R.string.le_align_row)) }
        TextButton(onClick = onAlignColumn) { Text(stringResource(R.string.le_align_column)) }
        TextButton(onClick = onDistributeH, enabled = count >= 3) { Text(stringResource(R.string.le_distribute_h)) }
        TextButton(onClick = onDistributeV, enabled = count >= 3) { Text(stringResource(R.string.le_distribute_v)) }
        TextButton(onClick = onMirror) { Text(stringResource(R.string.le_mirror)) }
    }
}
