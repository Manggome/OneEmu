package com.manggome.oneemu.ui.skins

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.skin.DescAction
import com.manggome.oneemu.emu.skin.LoadedSkin
import com.manggome.oneemu.emu.skin.Overlay
import com.manggome.oneemu.emu.skin.PlacedDesc
import com.manggome.oneemu.emu.skin.SkinInfo
import com.manggome.oneemu.emu.skin.SkinLayout
import com.manggome.oneemu.emu.skin.SkinLoader
import com.manggome.oneemu.emu.skin.SkinStore
import com.manggome.oneemu.emu.skin.SkinVisual
import com.manggome.oneemu.emu.skin.drawOverlay
import com.manggome.oneemu.emu.skin.groupPlaced
import com.manggome.oneemu.emu.skin.placeOverlay
import com.manggome.oneemu.emu.skin.resolved
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.layout.AlignOps
import com.manggome.oneemu.ui.layout.AlignToolbar
import com.manggome.oneemu.ui.layout.EditorChrome
import com.manggome.oneemu.ui.layout.EditorDragHost
import com.manggome.oneemu.ui.layout.EditorDragState
import com.manggome.oneemu.ui.layout.NudgeButtons
import com.manggome.oneemu.ui.layout.UndoHistory
import com.manggome.oneemu.ui.layout.drawGuides
import com.manggome.oneemu.ui.layout.editorGestures
import com.manggome.oneemu.ui.layout.mockGameRect
import com.manggome.oneemu.ui.layout.rememberEditorChromeState
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A draggable cluster: indices into the placed desc list. */
private typealias Group = List<Int>

/**
 * Drag-to-arrange editor for an image skin. Descs are moved as clusters (d-pad arms + diagonals, ABXY
 * diamond, stick + background) and the result is saved per (skin, system, orientation) through
 * [SkinStore.saveLayout]. Shares smart guides, axis lock, nudge, alignment, undo and the tool panel with
 * the vector `LayoutEditor` so the two feel the same.
 */
@Composable
fun SkinEditor(
    system: SystemId,
    landscape: Boolean,
    skinInfo: SkinInfo,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    orientationToggle: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val screenAspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
    val haptics = remember { Haptics(context) }

    val loaded by produceState<Result<LoadedSkin>?>(null, skinInfo.id) { value = runCatching { SkinLoader.load(context, skinInfo) } }
    var layout by remember { mutableStateOf<SkinLayout?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<Group>>(emptyList()) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var vibrate by remember { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val history = remember { UndoHistory<SkinLayout>(50) }
    val chrome = rememberEditorChromeState()
    val drag = remember { EditorDragState() }

    LaunchedEffect(skinInfo.id, system, landscape) {
        layout = SkinStore.loadLayout(skinInfo.id, system.id, landscape)
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
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
            SkinStore.saveLayout(skinInfo.id, system.id, landscape, l)
            settings.set(Settings.Keys.padOpacity, opacity)
            Toast.makeText(context, R.string.se_saved, Toast.LENGTH_SHORT).show()
            dirty = false
            onClose()
        }
    }

    val skin = loaded?.getOrNull()
    val overlay: Overlay? = remember(skin, landscape, screenAspect) {
        val cfg = skin?.cfg(landscape) ?: return@remember null
        cfg.pick(landscape, screenAspect, preferAnalog = system.hasAnalog)?.let { cfg.resolved(it, landscape) }
    }
    val currentLayout = layout ?: SkinLayout.EMPTY
    val placement = remember(overlay, canvasSize, currentLayout, globalScale) {
        overlay?.let { placeOverlay(it, canvasSize, landscape, currentLayout, globalScale) }
    }
    // Clusters come from the skin's own design (no user offsets) so dragging one group next to another
    // never merges them.
    val groups: List<Group> = remember(overlay, canvasSize, landscape) {
        overlay?.let { groupPlaced(placeOverlay(it, canvasSize, landscape, SkinLayout.EMPTY, 1f).second) }.orEmpty()
    }

    /** Applies [transform] as one undo step ([key] coalesces slider/nudge bursts). */
    fun edit(key: String? = null, transform: (SkinLayout) -> SkinLayout) {
        val l = layout ?: SkinLayout.EMPTY
        history.record(l, key)
        layout = transform(l)
        dirty = true
    }
    fun undo() { history.undo(currentLayout)?.let { layout = it; dirty = true } }
    fun redo() { history.redo(currentLayout)?.let { layout = it; dirty = true } }

    val layoutState = rememberUpdatedState(layout)
    val placedState = rememberUpdatedState(placement?.second.orEmpty())
    val frameState = rememberUpdatedState(placement?.first?.box)
    val groupsState = rememberUpdatedState(groups)
    val overlayState = rememberUpdatedState(overlay)
    val selectionState = rememberUpdatedState(selection)
    val canvasState = rememberUpdatedState(canvasSize)
    val vibrateState = rememberUpdatedState(vibrate)
    val landscapeState = rememberUpdatedState(landscape)

    /** Moves every desc of [group] by a normalized offset, starting from [base]. */
    fun SkinLayout.moveGroup(ov: Overlay, placed: List<PlacedDesc>, group: Group, dxN: Float, dyN: Float): SkinLayout {
        var l = this
        for (i in group) {
            val d = placed.getOrNull(i)?.desc ?: continue
            l = l.update(ov, d) { it.copy(dx = (it.dx + dxN).coerceIn(-1f, 1f), dy = (it.dy + dyN).coerceIn(-1f, 1f)) }
        }
        return l
    }

    val host = remember {
        object : EditorDragHost<Group> {
            private var startLayout: SkinLayout = SkinLayout.EMPTY

            override fun hitTest(pos: Offset): Group? {
                val placed = placedState.value
                return groupsState.value.asReversed().firstOrNull { g -> inflate(groupBounds(placed, g), 0.15f).contains(pos) }
            }
            override fun rectOf(id: Group): Rect? = groupBounds(placedState.value, id).takeIf { it != Rect.Zero }
            override fun selection(): List<Group> = selectionState.value
            override fun otherRects(exclude: Set<Group>): List<Rect> {
                val placed = placedState.value
                return groupsState.value.filter { g -> g !in exclude && g.any { placed.getOrNull(it)?.visible == true } }
                    .map { groupBounds(placed, it) }.filter { it != Rect.Zero }
            }
            override fun fixedRects(): List<Rect> = buildList {
                if (showMockGame) add(mockGameRect(canvasState.value, landscapeState.value))
                frameState.value?.takeIf { it != Rect.Zero }?.let(::add)
            }
            override fun axisLockOn(): Boolean = chrome.axisLock
            override fun onTap(id: Group?) { selection = if (id == null) emptyList() else listOf(id) }
            override fun onLongPress(id: Group) { selection = if (id in selection) selection.filter { it != id } else selection + listOf(id) }
            override fun onDragStart(ids: Set<Group>) {
                startLayout = layoutState.value ?: SkinLayout.EMPTY
                history.record(startLayout)
            }
            override fun onDragMove(ids: Set<Group>, delta: Offset) {
                val ov = overlayState.value ?: return
                val s = canvasState.value
                if (s == Size.Zero) return
                val placed = placedState.value
                var l = startLayout
                for (g in ids) l = l.moveGroup(ov, placed, g, delta.x / s.width, delta.y / s.height)
                layout = l
            }
            override fun onDragEnd(ids: Set<Group>) { dirty = true }
            override fun haptic() { if (vibrateState.value) haptics.tick(0) }
        }
    }

    fun nudge(dxDp: Int, dyDp: Int) {
        val ov = overlay ?: return
        val s = canvasSize
        if (s == Size.Zero || selection.isEmpty()) return
        val placed = placement?.second ?: return
        edit("nudge") { l -> selection.fold(l) { acc, g -> acc.moveGroup(ov, placed, g, dxDp * density / s.width, dyDp * density / s.height) } }
    }

    fun align(op: (List<Rect>) -> List<Offset>) {
        val ov = overlay ?: return
        val s = canvasSize
        val placed = placement?.second ?: return
        val groupsSel = selection.filter { groupBounds(placed, it) != Rect.Zero }
        if (groupsSel.size < 2 || s == Size.Zero) return
        val rects = groupsSel.map { groupBounds(placed, it) }
        val centers = op(rects)
        edit { l ->
            var out = l
            groupsSel.forEachIndexed { i, g ->
                val d = centers[i] - rects[i].center
                out = out.moveGroup(ov, placed, g, d.x / s.width, d.y / s.height)
            }
            out
        }
    }

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
        if (showMockGame) MockGame(landscape)

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) { editorGestures(host, drag, density) },
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.coerceIn(0.15f, 1f) }) {
                val s = skin ?: return@Canvas
                val ov = overlay ?: return@Canvas
                val pl = placement ?: return@Canvas
                drawOverlay(s, ov, pl.first, pl.second, SkinVisual(showHidden = true), Color.Transparent)
            }
            Canvas(Modifier.fillMaxSize()) {
                val pl = placement?.second ?: return@Canvas
                for (sel in selection) {
                    val r = inflate(groupBounds(pl, sel), 0.12f)
                    drawRoundRect(
                        Color(0xFFFFD166), r.topLeft, r.size, androidx.compose.ui.geometry.CornerRadius(12f),
                        style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    )
                }
                drawGuides(drag.guides, OneEmuColors.Accent, density)
            }
        }

        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OneEmuColors.Accent) }
            skin == null || overlay == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.skins_load_failed), color = OneEmuColors.OnSurface)
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
            title = stringResource(R.string.se_title),
            subtitle = skinInfo.name + (overlay?.let { " · " + stringResource(R.string.se_variant, it.name) } ?: ""),
            orientationToggle = orientationToggle,
            onCancel = { requestClose() },
            onSave = { save() },
            saveEnabled = skin != null,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            onUndo = { undo() },
            onRedo = { redo() },
            dragging = drag.dragging,
            hint = stringResource(R.string.se_hint),
            readout = readout,
        ) {
            val placed = placement?.second
            val ov = overlay
            val single = selection.singleOrNull()
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
            } else if (single != null && placed != null && ov != null) {
                val members = single.mapNotNull { placed.getOrNull(it) }
                val first = members.firstOrNull()
                val visible = members.any { it.visible }
                val scale = first?.let { currentLayout[ov, it.desc]?.scale } ?: 1f
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                    Text(groupLabel(members), style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Text(stringResource(R.string.le_visible), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = visible,
                        onCheckedChange = { v -> edit { l -> members.fold(l) { acc, m -> acc.update(ov, m.desc) { it.copy(visible = v) } } } },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.le_scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Slider(
                        value = scale,
                        onValueChange = { s -> edit("scale") { l -> members.fold(l) { acc, m -> acc.update(ov, m.desc) { it.copy(scale = s) } } } },
                        onValueChangeFinished = { history.endCoalesce() },
                        valueRange = 0.6f..1.8f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.le_opacity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                Slider(value = opacity, onValueChange = { opacity = it; dirty = true }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { edit { SkinLayout.EMPTY }; selection = emptyList() }) { Text(stringResource(R.string.se_reset)) }
            }
        }
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

@Composable
private fun groupLabel(members: List<PlacedDesc>): String {
    val actions = members.map { it.desc.action }
    return when {
        actions.any { it == DescAction.DpadArea } || members.all { it.desc.id in DIRECTIONS || it.desc.id.contains('|') || it.desc.action == DescAction.Image } && members.any { it.desc.id in DIRECTIONS } ->
            stringResource(R.string.se_group_dpad)
        actions.any { it == DescAction.AbxyArea } -> stringResource(R.string.se_group_abxy)
        actions.any { it is DescAction.Analog } -> stringResource(R.string.se_group_stick)
        actions.any { it == DescAction.MenuToggle } && actions.none { it is DescAction.Press } -> stringResource(R.string.se_group_menu)
        actions.any { it is DescAction.FastForward } && actions.none { it is DescAction.Press } -> stringResource(R.string.se_group_ff)
        actions.any { it is DescAction.OverlayNext } && actions.none { it is DescAction.Press } -> stringResource(R.string.se_group_toggle)
        else -> members.filter { it.desc.action is DescAction.Press && !it.desc.id.contains('|') }.map { it.desc.id.uppercase() }.distinct()
            .ifEmpty { members.map { it.desc.id.uppercase() }.distinct() }.joinToString(" · ")
    }
}

private val DIRECTIONS = setOf("up", "down", "left", "right")

private fun groupBounds(placed: List<PlacedDesc>, group: Group): Rect {
    var r: Rect? = null
    for (i in group) {
        val p = placed.getOrNull(i) ?: continue
        if (!p.desc.drawable && !p.desc.interactive) continue
        val b = p.bounds
        r = r?.let { Rect(minOf(it.left, b.left), minOf(it.top, b.top), maxOf(it.right, b.right), maxOf(it.bottom, b.bottom)) } ?: b
    }
    return r ?: Rect.Zero
}

private fun inflate(r: Rect, fraction: Float): Rect {
    val dx = (r.width * fraction / 2f).coerceAtLeast(8f)
    val dy = (r.height * fraction / 2f).coerceAtLeast(8f)
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

/** Same faint game rectangle the vector editor shows so users can place controls around it. */
@Composable
private fun MockGame(landscape: Boolean) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.6f }) {
        val rect = mockGameRect(size, landscape)
        drawRect(Color(0xFF2B2B2B), rect.topLeft, rect.size)
        drawRect(OneEmuColors.Divider, rect.topLeft, rect.size, style = Stroke(2f))
    }
}
