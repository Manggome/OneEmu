package com.manggome.oneemu.ui.layout

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElement
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadElementVisual
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.PadLayoutStore
import com.manggome.oneemu.emu.pad.drawPadElement
import com.manggome.oneemu.emu.pad.rectOn
import com.manggome.oneemu.emu.skin.SkinSelection
import com.manggome.oneemu.emu.skin.SkinStore
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.skins.SkinEditor
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag-to-arrange editor for the virtual pad of one (system, orientation).
 *
 * Used in two places: over the paused game from the in-game quick settings (transparent
 * background, [showMockGame] = false) and as the Routes.LAYOUT_EDITOR destination (dark background
 * with a faint mock game rectangle; [orientationToggle] lets the user switch which orientation they
 * are editing). Changes are only persisted on 저장.
 *
 * Editing helpers (shared with the skin editor through [EditorGuides.kt] / [EditorChrome.kt]): smart
 * guides with snapping, axis lock (두 손가락 or 축 고정), 1 dp nudge, multi-select alignment, undo/redo,
 * and a tool panel that gets out of the way while dragging.
 */
@Composable
fun LayoutEditor(
    system: SystemId,
    landscape: Boolean,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    orientationToggle: (@Composable () -> Unit)? = null,
) {
    // An image skin selected for this system gets its own editor; the vector pad keeps the original one.
    val context = LocalContext.current
    val selection by produceState<SkinSelection>(SkinSelection.Loading, system) {
        SkinStore.observeSelectedSkin(context, system).collect { value = it }
    }
    when (val sel = selection) {
        SkinSelection.Loading -> Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000)))
        is SkinSelection.Skin -> SkinEditor(system, landscape, sel.info, showMockGame, onClose, modifier, orientationToggle)
        SkinSelection.Vector -> VectorLayoutEditor(system, landscape, showMockGame, onClose, modifier, orientationToggle)
    }
}

@Composable
private fun VectorLayoutEditor(
    system: SystemId,
    landscape: Boolean,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
    orientationToggle: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val haptics = remember { Haptics(context) }

    var layout by remember { mutableStateOf<PadLayout?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<PadElementId>>(emptyList()) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var snap by remember { mutableStateOf(false) }
    var vibrate by remember { mutableStateOf(true) }
    var showElementList by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val history = remember { UndoHistory<PadLayout>(50) }
    val chrome = rememberEditorChromeState()
    val drag = remember { EditorDragState() }

    LaunchedEffect(system, landscape) {
        layout = PadLayoutStore.load(system, landscape)
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
        snap = settings.get(PadLayoutStore.Keys.layoutSnapToGrid, false)
        vibrate = settings.get(Settings.Keys.padVibration, true)
        selection = emptyList()
        history.clear()
        dirty = false
    }

    fun requestClose() { if (dirty) confirmDiscard = true else onClose() }
    BackHandler { requestClose() }

    fun save() {
        val l = layout ?: return
        scope.launch {
            PadLayoutStore.save(system, landscape, l)
            settings.set(Settings.Keys.padOpacity, opacity)
            settings.set(PadLayoutStore.Keys.layoutSnapToGrid, snap)
            Toast.makeText(context, R.string.le_saved, Toast.LENGTH_SHORT).show()
            dirty = false
            onClose()
        }
    }

    /** Applies [transform] to the layout as one undo step ([key] coalesces slider/nudge bursts). */
    fun edit(key: String? = null, transform: (PadLayout) -> PadLayout) {
        val l = layout ?: return
        history.record(l, key)
        layout = transform(l)
        dirty = true
    }
    fun undo() { val l = layout ?: return; history.undo(l)?.let { layout = it; dirty = true } }
    fun redo() { val l = layout ?: return; history.redo(l)?.let { layout = it; dirty = true } }

    val layoutState = rememberUpdatedState(layout)
    val scaleState = rememberUpdatedState(globalScale)
    val snapState = rememberUpdatedState(snap)
    val vibrateState = rememberUpdatedState(vibrate)
    val selectionState = rememberUpdatedState(selection)
    val canvasState = rememberUpdatedState(canvasSize)
    val landscapeState = rememberUpdatedState(landscape)

    fun PadElement.rect(size: Size = canvasState.value) = rectOn(size, density, scaleState.value)

    val host = remember {
        object : EditorDragHost<PadElementId> {
            private var startLayout: PadLayout? = null
            private var startRects: Map<PadElementId, Rect> = emptyMap()

            override fun hitTest(pos: Offset): PadElementId? =
                layoutState.value?.elements?.asReversed()?.firstOrNull { e -> e.visible && inflate(e.rect(), 0.2f).contains(pos) }?.id
            override fun rectOf(id: PadElementId): Rect? = layoutState.value?.get(id)?.rect()
            override fun selection(): List<PadElementId> = selectionState.value
            override fun otherRects(exclude: Set<PadElementId>): List<Rect> =
                layoutState.value?.elements.orEmpty().filter { it.visible && it.id !in exclude }.map { it.rect() }
            override fun fixedRects(): List<Rect> =
                if (showMockGame) listOf(mockGameRect(canvasState.value, landscapeState.value)) else emptyList()
            override fun axisLockOn(): Boolean = chrome.axisLock
            override fun onTap(id: PadElementId?) { selection = if (id == null) emptyList() else listOf(id) }
            override fun onLongPress(id: PadElementId) { selection = if (id in selection) selection - id else selection + id }
            override fun onDragStart(ids: Set<PadElementId>) {
                val l = layoutState.value ?: return
                startLayout = l
                startRects = ids.mapNotNull { id -> l[id]?.let { id to it.rect() } }.toMap()
                history.record(l)
            }
            override fun onDragMove(ids: Set<PadElementId>, delta: Offset) {
                val s = canvasState.value
                if (s == Size.Zero) return
                var l = startLayout ?: return
                for (id in ids) {
                    val c = (startRects[id] ?: continue).center + delta
                    l = l.update(id) { it.copy(x = (c.x / s.width).coerceIn(0f, 1f), y = (c.y / s.height).coerceIn(0f, 1f)) }
                }
                layout = l
            }
            override fun onDragEnd(ids: Set<PadElementId>) { dirty = true }
            override fun adjust(rect: Rect, snappedX: Boolean, snappedY: Boolean): Rect {
                if (!snapState.value) return rect
                val s = canvasState.value
                var cx = rect.center.x
                var cy = rect.center.y
                if (!snappedX) cx = snapTo(cx / s.width) * s.width
                if (!snappedY) cy = snapTo(cy / s.height) * s.height
                return Rect(Offset(cx - rect.width / 2f, cy - rect.height / 2f), rect.size)
            }
            override fun haptic() { if (vibrateState.value) haptics.tick(0) }
        }
    }

    fun nudge(dxDp: Int, dyDp: Int) {
        val s = canvasSize
        if (s == Size.Zero || selection.isEmpty()) return
        val ids = selection.toSet()
        edit("nudge") { l ->
            PadLayout(l.elements.map { e ->
                if (e.id in ids) e.copy(x = (e.x + dxDp * density / s.width).coerceIn(0f, 1f), y = (e.y + dyDp * density / s.height).coerceIn(0f, 1f)) else e
            })
        }
    }

    fun align(op: (List<Rect>) -> List<Offset>) {
        val s = canvasSize
        val l = layout ?: return
        val members = selection.mapNotNull { l[it] }
        if (members.size < 2 || s == Size.Zero) return
        val centers = op(members.map { it.rect(s) })
        edit { cur ->
            var out = cur
            members.forEachIndexed { i, e -> out = out.update(e.id) { it.copy(x = (centers[i].x / s.width).coerceIn(0f, 1f), y = (centers[i].y / s.height).coerceIn(0f, 1f)) } }
            out
        }
    }

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
        if (showMockGame) MockGame(landscape)

        // Elements + drag handling.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) { editorGestures(host, drag, density) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val l = layout ?: return@Canvas
                if (canvasSize == Size.Zero) return@Canvas
                if (snap) drawGrid()
                for (e in l.elements) {
                    val rect = e.rectOn(canvasSize, density, globalScale)
                    val alpha = if (e.visible) opacity else 0.15f
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, canvasSize), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                    drawPadElement(e, rect, system, PadElementVisual(selected = e.id in selection), textMeasurer)
                    drawContext.canvas.restore()
                }
                drawGuides(drag.guides, OneEmuColors.Accent, density)
            }
        }

        val readout = drag.dragRect?.takeIf { canvasSize != Size.Zero }?.let { r ->
            stringResource(
                R.string.le_readout,
                (r.center.x / density).roundToInt(), (r.center.x / canvasSize.width * 100f).roundToInt(),
                (r.center.y / density).roundToInt(), (r.center.y / canvasSize.height * 100f).roundToInt(),
            )
        }

        EditorChrome(
            state = chrome,
            title = stringResource(R.string.le_title),
            subtitle = null,
            orientationToggle = orientationToggle,
            onCancel = { requestClose() },
            onSave = { save() },
            saveEnabled = layout != null,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            onUndo = { undo() },
            onRedo = { redo() },
            dragging = drag.dragging,
            hint = stringResource(R.string.le_hint),
            readout = readout,
        ) {
            val sel = selection.singleOrNull()?.let { id -> layout?.get(id) }
            if (selection.size >= 2) {
                AlignToolbar(
                    count = selection.size,
                    onAlignRow = { align(AlignOps::alignRow) },
                    onAlignColumn = { align(AlignOps::alignColumn) },
                    onDistributeH = { align(AlignOps::distributeHorizontally) },
                    onDistributeV = { align(AlignOps::distributeVertically) },
                    onMirror = { align { AlignOps.mirrorHorizontally(it, canvasSize.width) } },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                }
            } else if (sel != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                    Text(sel.id.displayName, style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Text(stringResource(R.string.le_visible), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = sel.visible,
                        onCheckedChange = { v -> edit { l -> l.update(sel.id) { it.copy(visible = v) } } },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.le_scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Slider(
                        value = sel.scale,
                        onValueChange = { s -> edit("scale") { l -> l.update(sel.id) { it.copy(scale = s) } } },
                        onValueChangeFinished = { history.endCoalesce() },
                        valueRange = 0.6f..1.8f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(sel.scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.le_opacity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                Slider(value = opacity, onValueChange = { opacity = it; dirty = true }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = snap, onClick = { snap = !snap }, label = { Text(stringResource(R.string.le_snap)) })
                TextButton(onClick = { showElementList = true }) { Text(stringResource(R.string.le_elements)) }
                TextButton(onClick = { edit { DefaultLayouts.forSystem(system, landscape) }; selection = emptyList() }) {
                    Text(stringResource(R.string.le_reset), maxLines = 1)
                }
            }
        }
    }

    if (showElementList) {
        val l = layout
        AlertDialog(
            onDismissRequest = { showElementList = false },
            title = { Text(stringResource(R.string.le_elements)) },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(l?.elements.orEmpty(), key = { it.id }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable { edit { it.update(e.id) { el -> el.copy(visible = !el.visible) } } }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = e.visible, onCheckedChange = { v -> edit { it.update(e.id) { el -> el.copy(visible = v) } } })
                            Text(e.id.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showElementList = false }) { Text(stringResource(R.string.ok)) } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(R.string.le_discard_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.le_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun snapTo(v: Float): Float = (v * 50f).roundToInt() / 50f

private fun inflate(r: Rect, fraction: Float): Rect {
    val dx = r.width * fraction / 2f
    val dy = r.height * fraction / 2f
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid() {
    val color = Color.White.copy(alpha = 0.06f)
    var x = 0f
    while (x <= size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height)); x += size.width * 0.02f }
    var y = 0f
    while (y <= size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y)); y += size.height * 0.02f }
}

/** A faint rectangle where the game image would be, so users can place controls around it. */
@Composable
private fun MockGame(landscape: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val label = stringResource(R.string.le_mock_game)
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.6f }) {
        val rect = mockGameRect(size, landscape)
        drawRect(Color(0xFF2B2B2B), rect.topLeft, rect.size)
        drawRect(OneEmuColors.Divider, rect.topLeft, rect.size, style = Stroke(2f))
        val t = textMeasurer.measure(label, TextStyle(color = OneEmuColors.OnSurfaceMuted, fontSize = 14.sp))
        drawText(t, topLeft = Offset(rect.center.x - t.size.width / 2f, rect.center.y - t.size.height / 2f))
    }
}
