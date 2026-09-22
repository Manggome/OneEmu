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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.emu.ViewportRect
import com.manggome.oneemu.emu.ViewportStore
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElement
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadElementVisual
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.PadLayoutStore
import com.manggome.oneemu.emu.pad.drawPadElement
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.emu.pad.rectOn
import com.manggome.oneemu.emu.pad.rememberPadInsets
import com.manggome.oneemu.emu.skin.SkinSelection
import com.manggome.oneemu.emu.skin.SkinStore
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.skins.SkinEditor
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag-to-arrange editor for the virtual pad *and* the game viewport of one (system, screen configuration).
 *
 * Used in two places: over the paused game from the in-game quick settings (transparent background,
 * [showMockGame] = false, [onViewportPreview] pushes the dashed 화면 rectangle to the native renderer live)
 * and as the Routes.LAYOUT_EDITOR destination (dark background, the viewport doubles as the mock game
 * rectangle; [configSelector] lets the user switch which configuration they are editing). Changes are only
 * persisted on 저장.
 *
 * Editing helpers (shared with the skin editor through [EditorGuides.kt] / [EditorChrome.kt] /
 * [ViewportEditing.kt]): smart guides with snapping, axis lock (두 손가락 or 축 고정), 1 dp nudge, multi-select
 * alignment, undo/redo, viewport corner handles, and a tool panel that gets out of the way while dragging.
 */
@Composable
fun LayoutEditor(
    system: SystemId,
    config: ScreenConfig,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    configSelector: (@Composable () -> Unit)? = null,
    onViewportPreview: ((ViewportRect) -> Unit)? = null,
    /** Which of [system]'s pads is being edited; only Dolphin has more than one (GameCube / Wii). */
    profile: PadProfile = PadProfile(system),
) {
    // An image skin selected for this system gets its own editor; the vector pad keeps the original one.
    val context = LocalContext.current
    val selection by produceState<SkinSelection>(SkinSelection.Loading, profile) {
        SkinStore.observeSelectedSkin(context, profile).collect { value = it }
    }
    when (val sel = selection) {
        SkinSelection.Loading -> Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000)))
        is SkinSelection.Skin -> SkinEditor(profile, config, sel.info, showMockGame, onClose, modifier, configSelector, onViewportPreview)
        SkinSelection.Vector -> VectorLayoutEditor(system, profile, config, showMockGame, onClose, modifier, configSelector, onViewportPreview)
    }
}

/** One undo step of the vector editor: pad layout + viewport together. */
private data class VectorEditState(val layout: PadLayout, val viewport: ViewportRect)

@Composable
private fun VectorLayoutEditor(
    system: SystemId,
    profile: PadProfile,
    config: ScreenConfig,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
    configSelector: (@Composable () -> Unit)?,
    onViewportPreview: ((ViewportRect) -> Unit)?,
) {
    val context = LocalContext.current
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val haptics = remember { Haptics(context) }

    var layout by remember { mutableStateOf<PadLayout?>(null) }
    /** null = never customised → [ViewportStore.default] for the current canvas. */
    var savedViewport by remember { mutableStateOf<ViewportRect?>(null) }
    var viewport by remember { mutableStateOf<ViewportRect?>(null) }
    var viewportSelected by remember { mutableStateOf(false) }
    var keepAspect by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<PadElementId>>(emptyList()) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var snap by remember { mutableStateOf(false) }
    var vibrate by remember { mutableStateOf(true) }
    var showElementList by remember { mutableStateOf(false) }
    var showCopyFrom by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val history = remember { UndoHistory<VectorEditState>(50) }
    val chrome = rememberEditorChromeState()
    val drag = remember { EditorDragState() }

    LaunchedEffect(profile, config) {
        layout = PadLayoutStore.load(profile, config)
        savedViewport = ViewportStore.loadSaved(profile, config)
        viewport = savedViewport
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
        snap = settings.get(PadLayoutStore.Keys.layoutSnapToGrid, false)
        keepAspect = settings.get(ViewportPrefs.keepAspect, true)
        vibrate = settings.get(Settings.Keys.padVibration, true)
        selection = emptyList()
        viewportSelected = false
        history.clear()
        dirty = false
    }

    val defaultViewport = remember(system, config, canvasSize) { ViewportStore.default(system, config, canvasSize) }
    val currentViewport = viewport ?: defaultViewport
    val gameAspect = remember(system) { ViewportStore.nominalAspect(system) }

    // Live preview on the paused game: push every change, the host restores the saved value when we close.
    LaunchedEffect(currentViewport, canvasSize) { if (canvasSize != Size.Zero) onViewportPreview?.invoke(currentViewport) }

    fun requestClose() { if (dirty) confirmDiscard = true else onClose() }
    BackHandler { requestClose() }

    fun save() {
        val l = layout ?: return
        val vp = currentViewport
        scope.launch {
            PadLayoutStore.save(profile, config, l)
            ViewportStore.save(profile, config, vp)
            settings.set(Settings.Keys.padOpacity, opacity)
            settings.set(PadLayoutStore.Keys.layoutSnapToGrid, snap)
            settings.set(ViewportPrefs.keepAspect, keepAspect)
            Toast.makeText(context, R.string.le_saved, Toast.LENGTH_SHORT).show()
            dirty = false
            onClose()
        }
    }

    fun snapshot(): VectorEditState? = layout?.let { VectorEditState(it, currentViewport) }
    fun restore(s: VectorEditState) { layout = s.layout; viewport = s.viewport; dirty = true }

    /** Applies [transform] to the layout as one undo step ([key] coalesces slider/nudge bursts). */
    fun edit(key: String? = null, transform: (PadLayout) -> PadLayout) {
        val before = snapshot() ?: return
        history.record(before, key)
        layout = transform(before.layout)
        dirty = true
    }
    fun editViewport(key: String? = null, transform: (ViewportRect) -> ViewportRect) {
        val before = snapshot() ?: return
        history.record(before, key)
        viewport = transform(before.viewport).normalized()
        dirty = true
    }
    fun undo() { val cur = snapshot() ?: return; history.undo(cur)?.let(::restore) }
    fun redo() { val cur = snapshot() ?: return; history.redo(cur)?.let(::restore) }

    val layoutState = rememberUpdatedState(layout)
    val viewportState = rememberUpdatedState(currentViewport)
    val scaleState = rememberUpdatedState(globalScale)
    val snapState = rememberUpdatedState(snap)
    val vibrateState = rememberUpdatedState(vibrate)
    val selectionState = rememberUpdatedState(selection)
    val viewportSelectedState = rememberUpdatedState(viewportSelected)
    val canvasState = rememberUpdatedState(canvasSize)
    // The editor opened from 설정 runs under the navigation bar: a control dragged into it would be
    // drawn where the bar is and could not be picked up again, so it stops at the edge of the bar.
    val padInsets = rememberPadInsets()
    val insetsState = rememberUpdatedState(padInsets)

    fun PadElement.rect(size: Size = canvasState.value) = rectOn(size, density, scaleState.value, insetsState.value)
    fun viewportRect(size: Size = canvasState.value) = viewportState.value.toRect(size)

    val host = remember {
        object : EditorDragHost<Any> {
            private var startLayout: PadLayout? = null
            private var startRects: Map<PadElementId, Rect> = emptyMap()
            private var startViewport: Rect = Rect.Zero

            override fun hitTest(pos: Offset): Any? {
                val pad = layoutState.value?.elements?.asReversed()?.firstOrNull { e -> e.visible && inflate(e.rect(), 0.2f).contains(pos) }?.id
                if (pad != null) return pad
                return if (viewportRect().contains(pos)) ViewportItem else null
            }
            override fun rectOf(id: Any): Rect? = if (id === ViewportItem) viewportRect() else layoutState.value?.get(id as PadElementId)?.rect()
            override fun selection(): List<Any> = selectionState.value
            override fun otherRects(exclude: Set<Any>): List<Rect> =
                if (ViewportItem in exclude) emptyList()
                else layoutState.value?.elements.orEmpty().filter { it.visible && it.id !in exclude }.map { it.rect() }
            override fun fixedRects(): List<Rect> = listOf(viewportRect())
            override fun fixedRects(moving: Set<Any>): List<Rect> {
                val s = canvasState.value
                // The viewport snaps to the screen edges/centre; buttons snap to the viewport's edges/centre.
                return if (ViewportItem in moving) listOf(Rect(Offset.Zero, s)) else listOf(viewportRect())
            }
            override fun axisLockOn(): Boolean = chrome.axisLock
            override fun onTap(id: Any?) {
                if (id === ViewportItem) {
                    viewportSelected = !viewportSelectedState.value
                    selection = emptyList()
                } else {
                    viewportSelected = false
                    selection = if (id == null) emptyList() else listOf(id as PadElementId)
                }
            }
            override fun onLongPress(id: Any) {
                if (id === ViewportItem) { viewportSelected = true; return }
                val pid = id as PadElementId
                selection = if (pid in selection) selection - pid else selection + pid
            }
            override fun onDragStart(ids: Set<Any>) {
                val before = snapshot() ?: return
                startLayout = before.layout
                startRects = ids.filterIsInstance<PadElementId>().mapNotNull { id -> before.layout[id]?.let { id to it.rect() } }.toMap()
                startViewport = viewportRect()
                history.record(before)
            }
            override fun onDragMove(ids: Set<Any>, delta: Offset) {
                val s = canvasState.value
                if (s == Size.Zero) return
                if (ViewportItem in ids) {
                    viewport = ViewportRect.fromRect(clampInside(startViewport.translate(delta), s), s)
                    return
                }
                var l = startLayout ?: return
                for (id in ids) {
                    val c = (startRects[id] ?: continue).center + delta
                    l = l.update(id as PadElementId) { it.copy(x = (c.x / s.width).coerceIn(0f, 1f), y = (c.y / s.height).coerceIn(0f, 1f)) }
                }
                layout = l
            }
            override fun onDragEnd(ids: Set<Any>) { dirty = true }
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
        if (s == Size.Zero) return
        if (viewportSelected) {
            editViewport("nudge") { v ->
                ViewportRect.fromRect(clampInside(v.toRect(s).translate(Offset(dxDp * density, dyDp * density)), s), s)
            }
            return
        }
        if (selection.isEmpty()) return
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

    val viewportLabel = stringResource(R.string.vp_label)

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
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
                val label = textMeasurer.measure(viewportLabel, TextStyle(color = OneEmuColors.Accent, fontSize = 11.sp))
                drawViewport(currentViewport.toRect(canvasSize), gameAspect, viewportSelected, filled = showMockGame, density = density, label = label)
                for (e in l.elements) {
                    val rect = e.rectOn(canvasSize, density, globalScale, padInsets)
                    val alpha = if (e.visible) opacity else 0.15f
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, canvasSize), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                    drawPadElement(e, rect, profile, PadElementVisual(selected = e.id in selection), textMeasurer)
                    drawContext.canvas.restore()
                }
                drawGuides(drag.guides, OneEmuColors.Accent, density)
            }
        }

        if (viewportSelected && canvasSize != Size.Zero) {
            var resizeStart by remember { mutableStateOf(Rect.Zero) }
            ViewportHandles(
                rect = currentViewport.toRect(canvasSize),
                onDragStart = {
                    resizeStart = currentViewport.toRect(canvasSize)
                    snapshot()?.let { history.record(it) }
                },
                onDrag = { corner, total ->
                    val s = canvasSize
                    val minPx = Size(ViewportRect.MIN_SIZE * s.width, ViewportRect.MIN_SIZE * s.height)
                    viewport = ViewportRect.fromRect(resizeViewport(resizeStart, corner, total, keepAspect, s, minPx), s)
                    dirty = true
                },
                onDragEnd = { dirty = true },
            )
        }

        // Corner handles on a single selected button: drag to resize, the way the viewport already
        // works. The slider stays for fine values, but nobody looks for a slider first.
        val handleTarget = selection.singleOrNull()?.let { id -> layout?.get(id) }?.takeIf { it.visible }
        if (!viewportSelected && handleTarget != null && canvasSize != Size.Zero) {
            var startRect by remember { mutableStateOf(Rect.Zero) }
            var startScale by remember { mutableFloatStateOf(1f) }
            ViewportHandles(
                rect = handleTarget.rectOn(canvasSize, density, globalScale, padInsets),
                onDragStart = {
                    startRect = handleTarget.rectOn(canvasSize, density, globalScale, padInsets)
                    startScale = handleTarget.scale
                    snapshot()?.let { history.record(it) }
                },
                onDrag = { corner, total ->
                    // Uniform scale: how much further the dragged corner is from the centre than it started.
                    val c = startRect.center
                    val from = (corner.point(startRect) - c).getDistance()
                    if (from > 1f) {
                        val to = (corner.point(startRect) + total - c).getDistance()
                        val next = (startScale * to / from).coerceIn(ELEMENT_SCALE_MIN, ELEMENT_SCALE_MAX)
                        layout = layout?.update(handleTarget.id) { it.copy(scale = next) }
                        dirty = true
                    }
                },
                onDragEnd = { dirty = true },
            )
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
            orientationToggle = configSelector ?: { ScreenConfigSelector(config, onSelect = null) },
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
            if (viewportSelected) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                    Text(
                        stringResource(R.string.vp_size_readout, (currentViewport.w * 100).roundToInt(), (currentViewport.h * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall, color = OneEmuColors.OnSurfaceMuted, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                ViewportToolbar(
                    keepAspect = keepAspect,
                    onKeepAspect = { keepAspect = it; dirty = true },
                    onTop = { editViewport { ViewportQuick.top(it) } },
                    onCenter = { editViewport { ViewportQuick.center(it) } },
                    onFull = { editViewport { ViewportQuick.full() } },
                    onDefault = { editViewport { defaultViewport } },
                )
            } else if (selection.size >= 2) {
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
                    Text(sel.id.displayName(profile), style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f).padding(start = 4.dp))
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
                        valueRange = ELEMENT_SCALE_MIN..ELEMENT_SCALE_MAX,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(sel.scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                }
                // A stick that also presses the d-pad. Plenty of games never read the analog sticks -
                // Tekken on PlayStation is one - and there the stick does nothing until it does this.
                if (sel.id.kind == PadElementId.Kind.STICK) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.le_stick_dpad), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.le_stick_dpad_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = OneEmuColors.OnSurfaceMuted,
                            )
                        }
                        Switch(
                            checked = sel.dpadToo,
                            onCheckedChange = { v -> edit { l -> l.update(sel.id) { it.copy(dpadToo = v) } } },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
                TextButton(onClick = {
                    val l = layout
                    selection = if (l == null) emptyList() else l.elements.filter { it.visible }.map { it.id }
                    viewportSelected = false
                }) { Text(stringResource(R.string.le_select_all), maxLines = 1) }
                TextButton(onClick = { edit { it.mirrored() } }) { Text(stringResource(R.string.le_mirror_all), maxLines = 1) }
                TextButton(onClick = { showCopyFrom = true }) { Text(stringResource(R.string.le_copy_from), maxLines = 1) }
                FilterChip(
                    selected = viewportSelected,
                    onClick = { viewportSelected = !viewportSelected; if (viewportSelected) selection = emptyList() },
                    label = { Text(stringResource(R.string.vp_select)) },
                )
                TextButton(onClick = { showElementList = true }) { Text(stringResource(R.string.le_elements)) }
                fun applyPreset(preset: DefaultLayouts.Preset) {
                    val before = snapshot()
                    if (before != null) history.record(before)
                    layout = DefaultLayouts.forProfile(profile, config, preset)
                    viewport = defaultViewport
                    dirty = true
                    selection = emptyList()
                }
                TextButton(onClick = { applyPreset(DefaultLayouts.Preset.DEFAULT) }) {
                    Text(stringResource(R.string.le_reset), maxLines = 1)
                }
                TextButton(onClick = { applyPreset(DefaultLayouts.Preset.ARCADE) }) {
                    Text(stringResource(R.string.le_preset_arcade), maxLines = 1)
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
                // Every control the pad has, not just the ones this system's default layout happened to place:
                // the core decides what a button does, so a system whose default shows two buttons (Jazz²) may
                // still want X and Y. What is already in the layout comes first, the rest follows.
                val defaults = DefaultLayouts.forProfile(profile, config)
                val ids = remember(l, defaults, profile) {
                    val arcadeOnly = setOf(
                        PadElementId.COIN, PadElementId.ARCADE_1, PadElementId.ARCADE_2, PadElementId.ARCADE_3,
                        PadElementId.ARCADE_4, PadElementId.ARCADE_5, PadElementId.ARCADE_6,
                    )
                    val usable = PadElementId.entries.filter { it !in arcadeOnly || system == SystemId.ARCADE }
                    (l?.elements.orEmpty().map { it.id } + defaults.elements.map { it.id } + usable).distinct()
                }
                LazyColumn(Modifier.height(360.dp)) {
                    items(ids, key = { it }) { id ->
                        val placed = l?.get(id)
                        fun toggle(on: Boolean) = edit { cur ->
                            if (cur[id] != null) cur.update(id) { el -> el.copy(visible = on) }
                            // Not in this layout yet: drop it where the default layout has it, else in the middle.
                            else cur.withElement(id, defaults[id]?.x ?: id.defaultSpot.first, defaults[id]?.y ?: id.defaultSpot.second)
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { toggle(placed?.visible != true) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = placed?.visible == true, onCheckedChange = ::toggle)
                            Text(id.displayName(profile), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showElementList = false }) { Text(stringResource(R.string.ok)) } },
        )
    }

    if (showCopyFrom) {
        CopyLayoutDialog(
            profile = profile,
            config = config,
            onDismiss = { showCopyFrom = false },
            onPicked = { picked ->
                showCopyFrom = false
                val before = snapshot()
                if (before != null) history.record(before)
                layout = picked
                dirty = true
                selection = emptyList()
            },
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

/** How far a single button may be scaled, shared by the slider and the corner handles. */
private const val ELEMENT_SCALE_MIN = 0.6f
private const val ELEMENT_SCALE_MAX = 1.8f

/**
 * Picks a layout to copy in: the same system in another screen shape first (setting up portrait and
 * then landscape is the usual order), then the same shape on another system.
 */
@Composable
private fun CopyLayoutDialog(
    profile: PadProfile,
    config: ScreenConfig,
    onDismiss: () -> Unit,
    onPicked: (PadLayout) -> Unit,
) {
    data class Source(val label: String, val load: suspend () -> PadLayout)

    val scope = rememberCoroutineScope()
    val otherConfigs = ScreenConfig.entries.filter { it != config }
    val otherProfiles = remember(profile) { PadProfile.ordered.filter { it != profile } }
    // stringResource cannot be called from inside buildList, so the labels are resolved first.
    val configLabels = otherConfigs.map { stringResource(it.labelRes) }
    val sources = remember(profile, config, configLabels) {
        buildList {
            otherConfigs.forEachIndexed { i, c -> add(Source(configLabels[i]) { PadLayoutStore.load(profile, c) }) }
            for (s in otherProfiles) add(Source(s.displayName) { PadLayoutStore.load(s, config) })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.le_copy_from)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.le_copy_from_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OneEmuColors.OnSurfaceMuted,
                )
                LazyColumn(Modifier.height(320.dp)) {
                    items(sources.size, key = { it }) { i ->
                        val src = sources[i]
                        if (i == otherConfigs.size) {
                            Text(
                                stringResource(R.string.le_copy_from_other_systems),
                                style = MaterialTheme.typography.labelLarge,
                                color = OneEmuColors.Accent,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        Text(
                            src.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { scope.launch { onPicked(src.load()) } }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
