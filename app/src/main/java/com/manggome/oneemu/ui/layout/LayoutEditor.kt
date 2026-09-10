package com.manggome.oneemu.ui.layout

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElement
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadElementVisual
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.PadLayoutStore
import com.manggome.oneemu.emu.pad.drawPadElement
import com.manggome.oneemu.emu.pad.rectOn
import com.manggome.oneemu.model.SystemId
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
    val context = LocalContext.current
    val settings = remember { OneEmuApp.get().settings }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()

    var layout by remember { mutableStateOf<PadLayout?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PadElementId?>(null) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var snap by remember { mutableStateOf(false) }
    var showElementList by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(system, landscape) {
        layout = PadLayoutStore.load(system, landscape)
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
        snap = settings.get(PadLayoutStore.Keys.layoutSnapToGrid, false)
        selected = null
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

    val layoutState = rememberUpdatedState(layout)
    val scaleState = rememberUpdatedState(globalScale)
    val snapState = rememberUpdatedState(snap)

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
        if (showMockGame) MockGame(landscape)

        // Elements + drag handling.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val l = layoutState.value ?: return@awaitEachGesture
                        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                        val hit = l.elements.asReversed().firstOrNull { e ->
                            e.visible && inflate(e.rectOn(size, density, scaleState.value), 0.2f).contains(down.position)
                        }
                        selected = hit?.id
                        if (hit == null) return@awaitEachGesture
                        down.consume()
                        val startRect = hit.rectOn(size, density, scaleState.value)
                        val grabOffset = down.position - startRect.center
                        var moved = false
                        drag(down.id) { change ->
                            change.consume()
                            moved = true
                            val center = change.position - grabOffset
                            val nx = (center.x / size.width).coerceIn(0f, 1f)
                            val ny = (center.y / size.height).coerceIn(0f, 1f)
                            layout = layoutState.value?.update(hit.id) { it.copy(x = nx, y = ny) }
                        }
                        if (moved) {
                            dirty = true
                            if (snapState.value) {
                                layout = layoutState.value?.update(hit.id) { it.copy(x = snapTo(it.x), y = snapTo(it.y)) }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val l = layout ?: return@Canvas
                if (canvasSize == Size.Zero) return@Canvas
                if (snap) drawGrid()
                for (e in l.elements) {
                    val rect = e.rectOn(canvasSize, density, globalScale)
                    val alpha = if (e.visible) opacity else 0.15f
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, canvasSize), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                    drawPadElement(e, rect, system, PadElementVisual(selected = e.id == selected), textMeasurer)
                    drawContext.canvas.restore()
                }
            }
        }

        // Top bar.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.le_title), style = MaterialTheme.typography.titleMedium, color = OneEmuColors.OnSurface, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.width(12.dp))
            orientationToggle?.invoke()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { requestClose() }) { Text(stringResource(R.string.le_cancel)) }
            TextButton(onClick = { save() }) { Text(stringResource(R.string.le_save)) }
        }

        // Bottom bar.
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = OneEmuColors.Surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val sel = selected?.let { id -> layout?.get(id) }
                if (sel != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(sel.id.displayName, style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.le_visible), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = sel.visible,
                            onCheckedChange = { v -> layout = layout?.update(sel.id) { it.copy(visible = v) }; dirty = true },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.le_scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                        Slider(
                            value = sel.scale,
                            onValueChange = { s -> layout = layout?.update(sel.id) { it.copy(scale = s) }; dirty = true },
                            valueRange = 0.6f..1.8f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(sel.scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                    }
                } else {
                    Text(stringResource(R.string.le_hint), style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.OnSurfaceMuted)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.le_opacity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Slider(value = opacity, onValueChange = { opacity = it; dirty = true }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                    Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = snap, onClick = { snap = !snap }, label = { Text(stringResource(R.string.le_snap)) })
                    TextButton(onClick = { showElementList = true }) { Text(stringResource(R.string.le_elements)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { layout = DefaultLayouts.forSystem(system, landscape); selected = null; dirty = true }) {
                        Text(stringResource(R.string.le_reset))
                    }
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
                            Modifier.fillMaxWidth().clickable { layout = layout?.update(e.id) { it.copy(visible = !it.visible) }; dirty = true }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = e.visible, onCheckedChange = { v -> layout = layout?.update(e.id) { it.copy(visible = v) }; dirty = true })
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
        val aspect = 4f / 3f
        val rect = if (landscape) {
            val h = size.height * 0.92f
            val w = minOf(h * aspect, size.width * 0.6f)
            Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, w / aspect))
        } else {
            val w = size.width
            Rect(Offset(0f, size.height * 0.06f), Size(w, w / aspect))
        }
        drawRect(Color(0xFF2B2B2B), rect.topLeft, rect.size)
        drawRect(OneEmuColors.Divider, rect.topLeft, rect.size, style = Stroke(2f))
        val t = textMeasurer.measure(label, TextStyle(color = OneEmuColors.OnSurfaceMuted, fontSize = 14.sp))
        drawText(t, topLeft = Offset(rect.center.x - t.size.width / 2f, rect.center.y - t.size.height / 2f))
    }
}
