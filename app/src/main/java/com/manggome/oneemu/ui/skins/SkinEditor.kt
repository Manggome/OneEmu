package com.manggome.oneemu.ui.skins

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.manggome.oneemu.emu.skin.DescAction
import com.manggome.oneemu.emu.skin.LoadedSkin
import com.manggome.oneemu.emu.skin.Overlay
import com.manggome.oneemu.emu.skin.PlacedDesc
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.emu.skin.SkinInfo
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.PadLayoutStore
import androidx.compose.runtime.collectAsState
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
import com.manggome.oneemu.ui.layout.ScreenConfigSelector
import com.manggome.oneemu.ui.layout.UndoHistory
import com.manggome.oneemu.ui.layout.ViewportHandles
import com.manggome.oneemu.ui.layout.ViewportItem
import com.manggome.oneemu.ui.layout.ViewportPrefs
import com.manggome.oneemu.ui.layout.ViewportQuick
import com.manggome.oneemu.ui.layout.ViewportToolbar
import com.manggome.oneemu.ui.layout.clampInside
import com.manggome.oneemu.ui.layout.drawGuides
import com.manggome.oneemu.ui.layout.drawViewport
import com.manggome.oneemu.ui.layout.editorGestures
import com.manggome.oneemu.ui.layout.rememberEditorChromeState
import com.manggome.oneemu.ui.layout.resizeViewport
import com.manggome.oneemu.ui.theme.OneEmuColors
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.text.style.TextAlign
import com.manggome.oneemu.ui.layout.EditorSettingsDialog
import com.manggome.oneemu.ui.layout.EditorTool
import com.manggome.oneemu.ui.layout.EditorToolRow
import com.manggome.oneemu.ui.layout.MenuLine
import com.manggome.oneemu.ui.layout.PercentSlider
import com.manggome.oneemu.ui.layout.SelectionHeader
import com.manggome.oneemu.ui.layout.SwitchLine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A draggable cluster: indices into the placed desc list. */
private typealias Group = List<Int>

/** One undo step of the skin editor: desc edits + viewport together. */
private data class SkinEditState(val layout: SkinLayout, val viewport: ViewportRect)

/**
 * Drag-to-arrange editor for an image skin. Descs are moved as clusters (d-pad arms + diagonals, ABXY
 * diamond, stick + background) and the result is saved per (skin, system, screen configuration) through
 * [SkinStore.saveLayout]; the game viewport ("화면") is edited alongside and saved through [ViewportStore].
 * Shares smart guides, axis lock, nudge, alignment, undo, viewport handles and the tool panel with the
 * vector `LayoutEditor` so the two feel the same.
 */
@Composable
fun SkinEditor(
    profile: PadProfile,
    config: ScreenConfig,
    skinInfo: SkinInfo,
    showMockGame: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    configSelector: (@Composable () -> Unit)? = null,
    onViewportPreview: ((ViewportRect) -> Unit)? = null,
) {
    val system = profile.system
    val context = LocalContext.current
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val screenAspect = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1)
    val landscape = config.landscape
    val haptics = remember { Haptics(context) }

    val loaded by produceState<Result<LoadedSkin>?>(null, skinInfo.id) { value = runCatching { SkinLoader.load(context, skinInfo) } }
    // The 배속 button lives in the vector layout even while a skin is active (PadHost draws it on top).
    val padLayout by remember(system, config) { PadLayoutStore.observe(profile, config) }.collectAsState(initial = null)
    // Controls a RetroArch overlay has no concept of; PadHost draws them over the skin.
    val overlayExtras = listOf(
        PadElementId.SPEED, PadElementId.TURBO, PadElementId.SAVE_STATE, PadElementId.LOAD_STATE,
    )
    var layout by remember { mutableStateOf<SkinLayout?>(null) }
    var viewport by remember { mutableStateOf<ViewportRect?>(null) }
    var viewportSelected by remember { mutableStateOf(false) }
    var keepAspect by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<Group>>(emptyList()) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var vibrate by remember { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showLayoutMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val history = remember { UndoHistory<SkinEditState>(50) }
    val chrome = rememberEditorChromeState()
    val drag = remember { EditorDragState() }

    LaunchedEffect(skinInfo.id, system, config) {
        layout = SkinStore.loadLayout(skinInfo.id, profile.key, config)
        viewport = ViewportStore.loadSaved(profile, config)
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
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
    LaunchedEffect(currentViewport, canvasSize) { if (canvasSize != Size.Zero) onViewportPreview?.invoke(currentViewport) }

    fun requestClose() { if (dirty) confirmDiscard = true else onClose() }
    BackHandler { requestClose() }

    fun save() {
        val l = layout ?: return
        val vp = currentViewport
        scope.launch {
            SkinStore.saveLayout(skinInfo.id, profile.key, config, l)
            ViewportStore.save(profile, config, vp)
            settings.set(Settings.Keys.padOpacity, opacity)
            settings.set(ViewportPrefs.keepAspect, keepAspect)
            Toast.makeText(context, R.string.se_saved, Toast.LENGTH_SHORT).show()
            dirty = false
            onClose()
        }
    }

    val skin = loaded?.getOrNull()
    // Which of the skin's overlays is being edited. A skin often ships several for one orientation - an
    // arcade cabinet with eight buttons and the same one with four - and in game a button on the overlay
    // switches between them, so the editor lets the user say which one they mean. Null = the one a game
    // would start on. Layouts are stored per overlay, so editing one never disturbs another.
    var variantName by rememberSaveable(skinInfo.id, landscape) { mutableStateOf<String?>(null) }
    val variants: List<Overlay> = remember(skin, landscape) { skin?.cfg(landscape)?.variants(landscape).orEmpty() }
    val overlay: Overlay? = remember(skin, landscape, screenAspect, variantName) {
        val cfg = skin?.cfg(landscape) ?: return@remember null
        val chosen = cfg.byName(variantName) ?: cfg.pick(landscape, screenAspect, preferAnalog = system.hasAnalog)
        chosen?.let { cfg.resolved(it, landscape) }
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

    fun snapshot() = SkinEditState(currentLayout, currentViewport)
    fun restore(s: SkinEditState) { layout = s.layout; viewport = s.viewport; dirty = true }

    /** Applies [transform] as one undo step ([key] coalesces slider/nudge bursts). */
    fun edit(key: String? = null, transform: (SkinLayout) -> SkinLayout) {
        val before = snapshot()
        history.record(before, key)
        layout = transform(before.layout)
        dirty = true
    }
    fun editViewport(key: String? = null, transform: (ViewportRect) -> ViewportRect) {
        val before = snapshot()
        history.record(before, key)
        viewport = transform(before.viewport).normalized()
        dirty = true
    }
    fun undo() { history.undo(snapshot())?.let(::restore) }
    fun redo() { history.redo(snapshot())?.let(::restore) }

    val layoutState = rememberUpdatedState(layout)
    val viewportState = rememberUpdatedState(currentViewport)
    val viewportSelectedState = rememberUpdatedState(viewportSelected)
    val placedState = rememberUpdatedState(placement?.second.orEmpty())
    val frameState = rememberUpdatedState(placement?.first?.box)
    val groupsState = rememberUpdatedState(groups)
    val overlayState = rememberUpdatedState(overlay)
    val selectionState = rememberUpdatedState(selection)
    val canvasState = rememberUpdatedState(canvasSize)
    val vibrateState = rememberUpdatedState(vibrate)

    fun viewportRect(size: Size = canvasState.value) = viewportState.value.toRect(size)

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
        object : EditorDragHost<Any> {
            private var startLayout: SkinLayout = SkinLayout.EMPTY
            private var startViewport: Rect = Rect.Zero

            @Suppress("UNCHECKED_CAST")
            private fun Any.asGroup(): Group = this as Group

            override fun hitTest(pos: Offset): Any? {
                val placed = placedState.value
                val g = groupsState.value.asReversed().firstOrNull { g -> inflate(groupBounds(placed, g), 0.15f).contains(pos) }
                if (g != null) return g
                return if (viewportRect().contains(pos)) ViewportItem else null
            }
            override fun rectOf(id: Any): Rect? =
                if (id === ViewportItem) viewportRect() else groupBounds(placedState.value, id.asGroup()).takeIf { it != Rect.Zero }
            override fun selection(): List<Any> = selectionState.value
            override fun otherRects(exclude: Set<Any>): List<Rect> {
                if (ViewportItem in exclude) return emptyList()
                val placed = placedState.value
                return groupsState.value.filter { g -> g !in exclude && g.any { placed.getOrNull(it)?.visible == true } }
                    .map { groupBounds(placed, it) }.filter { it != Rect.Zero }
            }
            override fun fixedRects(): List<Rect> = buildList {
                add(viewportRect())
                frameState.value?.takeIf { it != Rect.Zero }?.let(::add)
            }
            override fun fixedRects(moving: Set<Any>): List<Rect> =
                if (ViewportItem in moving) listOf(Rect(Offset.Zero, canvasState.value)) else fixedRects()
            override fun axisLockOn(): Boolean = chrome.axisLock
            override fun onTap(id: Any?) {
                if (id === ViewportItem) {
                    viewportSelected = !viewportSelectedState.value
                    selection = emptyList()
                } else {
                    viewportSelected = false
                    selection = if (id == null) emptyList() else listOf(id.asGroup())
                }
            }
            override fun onLongPress(id: Any) {
                if (id === ViewportItem) { viewportSelected = true; return }
                val g = id.asGroup()
                selection = if (g in selection) selection.filter { it != g } else selection + listOf(g)
            }
            override fun onDragStart(ids: Set<Any>) {
                startLayout = layoutState.value ?: SkinLayout.EMPTY
                startViewport = viewportRect()
                history.record(SkinEditState(startLayout, viewportState.value))
            }
            override fun onDragMove(ids: Set<Any>, delta: Offset) {
                val s = canvasState.value
                if (s == Size.Zero) return
                if (ViewportItem in ids) {
                    viewport = ViewportRect.fromRect(clampInside(startViewport.translate(delta), s), s)
                    return
                }
                val ov = overlayState.value ?: return
                val placed = placedState.value
                var l = startLayout
                for (g in ids) l = l.moveGroup(ov, placed, g.asGroup(), delta.x / s.width, delta.y / s.height)
                layout = l
            }
            override fun onDragEnd(ids: Set<Any>) { dirty = true }
            override fun haptic() { if (vibrateState.value) haptics.tick(0) }
        }
    }

    fun nudge(dxDp: Int, dyDp: Int) {
        val s = canvasSize
        if (s == Size.Zero) return
        if (viewportSelected) {
            editViewport("nudge") { v -> ViewportRect.fromRect(clampInside(v.toRect(s).translate(Offset(dxDp * density, dyDp * density)), s), s) }
            return
        }
        val ov = overlay ?: return
        if (selection.isEmpty()) return
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

    val viewportLabel = stringResource(R.string.vp_label)

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) { editorGestures(host, drag, density) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (canvasSize == Size.Zero) return@Canvas
                val label = textMeasurer.measure(viewportLabel, TextStyle(color = OneEmuColors.Accent, fontSize = 11.sp))
                drawViewport(currentViewport.toRect(canvasSize), gameAspect, viewportSelected, filled = showMockGame, density = density, label = label)
            }
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

        if (viewportSelected && canvasSize != Size.Zero) {
            var resizeStart by remember { mutableStateOf(Rect.Zero) }
            ViewportHandles(
                rect = currentViewport.toRect(canvasSize),
                onDragStart = { resizeStart = currentViewport.toRect(canvasSize); history.record(snapshot()) },
                onDrag = { corner, total ->
                    val s = canvasSize
                    val minPx = Size(ViewportRect.MIN_SIZE * s.width, ViewportRect.MIN_SIZE * s.height)
                    viewport = ViewportRect.fromRect(resizeViewport(resizeStart, corner, total, keepAspect, s, minPx), s)
                    dirty = true
                },
                onDragEnd = { dirty = true },
            )
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
            orientationToggle = configSelector ?: { ScreenConfigSelector(config, onSelect = null) },
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
            val members = if (single != null && placed != null) single.mapNotNull { placed.getOrNull(it) } else emptyList()
            when {
                viewportSelected -> {
                    SelectionHeader(
                        stringResource(R.string.le_viewport_title, (currentViewport.w * 100).roundToInt(), (currentViewport.h * 100).roundToInt()),
                        onDone = { viewportSelected = false },
                    ) { NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() }) }
                    ViewportToolbar(
                        keepAspect = keepAspect,
                        onKeepAspect = { keepAspect = it; dirty = true },
                        onTop = { editViewport { ViewportQuick.top(it) } },
                        onCenter = { editViewport { ViewportQuick.center(it) } },
                        onFull = { editViewport { ViewportQuick.full() } },
                        onDefault = { editViewport { defaultViewport } },
                    )
                }
                selection.size >= 2 -> {
                    SelectionHeader(stringResource(R.string.le_selected_n, selection.size), onDone = { selection = emptyList() }) {
                        NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                    }
                    AlignToolbar(
                        count = selection.size,
                        onAlignRow = { align(AlignOps::alignRow) },
                        onAlignColumn = { align(AlignOps::alignColumn) },
                        onDistributeH = { align(AlignOps::distributeHorizontally) },
                        onDistributeV = { align(AlignOps::distributeVertically) },
                        onMirror = { align { AlignOps.mirrorHorizontally(it, canvasSize.width) } },
                    )
                }
                members.isNotEmpty() && ov != null -> {
                    val visible = members.any { it.visible }
                    val scale = currentLayout[ov, members.first().desc]?.scale ?: 1f
                    SelectionHeader(groupLabel(members), onDone = { selection = emptyList() }) {
                        NudgeButtons(enabled = true, onNudge = ::nudge, onRelease = { history.endCoalesce() })
                    }
                    PercentSlider(
                        stringResource(R.string.le_scale),
                        scale,
                        { s -> edit("scale") { l -> members.fold(l) { acc, m -> acc.update(ov, m.desc) { it.copy(scale = s) } } } },
                        0.6f..1.8f,
                        onFinished = { history.endCoalesce() },
                    )
                    SwitchLine(
                        stringResource(R.string.le_visible),
                        visible,
                        { v -> edit { l -> members.fold(l) { acc, m -> acc.update(ov, m.desc) { it.copy(visible = v) } } } },
                    )
                }
                else -> {
                    // A skin's variants (portrait/landscape art, a second button set...) are what is being
                    // edited, not a setting, so they stay in view. Both live in the same SkinLayout under
                    // their own keys: nothing needs saving on the way across.
                    if (variants.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            for (v in variants) {
                                FilterChip(
                                    selected = v.name == overlay?.name,
                                    onClick = { variantName = v.name; selection = emptyList(); viewportSelected = false },
                                    label = { Text(v.name) },
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.le_idle_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OneEmuColors.OnSurfaceMuted,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    EditorToolRow(
                        listOf(
                            EditorTool(Icons.Outlined.AspectRatio, stringResource(R.string.le_tool_screen)) {
                                viewportSelected = true
                                selection = emptyList()
                            },
                            EditorTool(Icons.Outlined.Dashboard, stringResource(R.string.le_tool_layout)) { showLayoutMenu = true },
                            EditorTool(Icons.Outlined.Settings, stringResource(R.string.le_tool_settings)) { showSettings = true },
                        ),
                    )
                }
            }
        }
    }

    if (showLayoutMenu) {
        AlertDialog(
            onDismissRequest = { showLayoutMenu = false },
            title = { Text(stringResource(R.string.le_layout_menu_title)) },
            text = {
                Column {
                    MenuLine(stringResource(R.string.se_reset), stringResource(R.string.se_reset_desc)) {
                        showLayoutMenu = false
                        history.record(snapshot())
                        layout = SkinLayout.EMPTY
                        viewport = defaultViewport
                        dirty = true
                        selection = emptyList()
                    }
                    // An image skin cannot be rearranged into a cabinet, so this drops the skin for this
                    // pad and hands the vector pad the arcade preset instead (stick left, six flat buttons right).
                    MenuLine(stringResource(R.string.le_preset_arcade), stringResource(R.string.se_arcade_desc)) {
                        showLayoutMenu = false
                        scope.launch {
                            PadLayoutStore.save(profile, config, DefaultLayouts.forProfile(profile, config, DefaultLayouts.Preset.ARCADE))
                            SkinStore.select(profile.key, SkinStore.VECTOR)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLayoutMenu = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showSettings) {
        EditorSettingsDialog(
            opacity = opacity,
            onOpacity = { opacity = it; dirty = true },
            snap = null,
            onSnap = {},
            chrome = chrome,
            onDismiss = { showSettings = false },
        ) {
            // Buttons the skin's art does not have (fast-forward, menu...), drawn by the vector pad on top.
            for (id in overlayExtras) {
                SwitchLine(
                    stringResource(R.string.se_extra_button, id.displayName),
                    padLayout?.get(id)?.visible == true,
                    { on ->
                        val cur = padLayout ?: PadLayout(emptyList())
                        val next = if (!on) cur.update(id) { it.copy(visible = false) }
                        else cur.withElement(id, id.defaultSpot.first, id.defaultSpot.second)
                        scope.launch { PadLayoutStore.save(profile, config, next) }
                    },
                )
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
