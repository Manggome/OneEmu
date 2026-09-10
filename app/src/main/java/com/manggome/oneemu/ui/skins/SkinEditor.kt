package com.manggome.oneemu.ui.skins

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
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
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag-to-arrange editor for an image skin. Descs are moved as clusters (d-pad arms + diagonals, ABXY
 * diamond, stick + background) and the result is saved per (skin, system, orientation) through
 * [SkinStore.saveLayout]. Mirrors the vector `LayoutEditor` UI so the two feel the same.
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
    val screenAspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)

    val loaded by produceState<Result<LoadedSkin>?>(null, skinInfo.id) { value = runCatching { SkinLoader.load(context, skinInfo) } }
    var layout by remember { mutableStateOf<SkinLayout?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Int>?>(null) }
    var opacity by remember { mutableStateOf(Settings.DEFAULT_PAD_OPACITY) }
    var globalScale by remember { mutableStateOf(Settings.DEFAULT_PAD_SCALE) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(skinInfo.id, system, landscape) {
        layout = SkinStore.loadLayout(skinInfo.id, system.id, landscape)
        opacity = settings.get(Settings.Keys.padOpacity, Settings.DEFAULT_PAD_OPACITY)
        globalScale = settings.get(Settings.Keys.padScale, Settings.DEFAULT_PAD_SCALE)
        selected = null
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
    val groups = remember(placement) { placement?.second?.let(::groupPlaced).orEmpty() }

    val layoutState = rememberUpdatedState(layout)
    val placedState = rememberUpdatedState(placement?.second.orEmpty())
    val groupsState = rememberUpdatedState(groups)
    val overlayState = rememberUpdatedState(overlay)

    Box(modifier.fillMaxSize().background(if (showMockGame) OneEmuColors.Background else Color(0x66000000))) {
        if (showMockGame) MockGame(landscape)

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val ov = overlayState.value ?: return@awaitEachGesture
                        val placed = placedState.value
                        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                        val hit = groupsState.value.asReversed().firstOrNull { g -> inflate(groupBounds(placed, g), 0.15f).contains(down.position) }
                        selected = hit
                        if (hit == null) return@awaitEachGesture
                        down.consume()
                        var last = down.position
                        var moved = false
                        drag(down.id) { change ->
                            change.consume()
                            val delta = change.position - last
                            last = change.position
                            if (delta == Offset.Zero) return@drag
                            moved = true
                            val dxN = delta.x / size.width
                            val dyN = delta.y / size.height
                            var l = layoutState.value ?: SkinLayout.EMPTY
                            for (i in hit) {
                                val d = placed.getOrNull(i)?.desc ?: continue
                                l = l.update(ov, d) { it.copy(dx = (it.dx + dxN).coerceIn(-1f, 1f), dy = (it.dy + dyN).coerceIn(-1f, 1f)) }
                            }
                            layout = l
                        }
                        if (moved) dirty = true
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.coerceIn(0.15f, 1f) }) {
                val s = skin ?: return@Canvas
                val ov = overlay ?: return@Canvas
                val pl = placement ?: return@Canvas
                drawOverlay(s, ov, pl.first, pl.second, SkinVisual(showHidden = true), Color.Transparent)
            }
            Canvas(Modifier.fillMaxSize()) {
                val pl = placement?.second ?: return@Canvas
                val sel = selected ?: return@Canvas
                val r = inflate(groupBounds(pl, sel), 0.12f)
                drawRoundRect(
                    Color(0xFFFFD166), r.topLeft, r.size, androidx.compose.ui.geometry.CornerRadius(12f),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
        }

        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OneEmuColors.Accent) }
            skin == null || overlay == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.skins_load_failed), color = OneEmuColors.OnSurface)
            }
        }

        // Top bar.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.se_title), style = MaterialTheme.typography.titleMedium, color = OneEmuColors.OnSurface)
                Text(
                    skinInfo.name + (overlay?.let { " · " + stringResource(R.string.se_variant, it.name) } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = OneEmuColors.OnSurfaceMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            orientationToggle?.invoke()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { requestClose() }) { Text(stringResource(R.string.le_cancel)) }
            TextButton(onClick = { save() }, enabled = skin != null) { Text(stringResource(R.string.le_save)) }
        }

        // Bottom bar.
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = OneEmuColors.Surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val sel = selected
                val placed = placement?.second
                val ov = overlay
                if (sel != null && placed != null && ov != null) {
                    val members = sel.mapNotNull { placed.getOrNull(it) }
                    val first = members.firstOrNull()
                    val visible = members.any { it.visible }
                    val scale = first?.let { currentLayout[ov, it.desc]?.scale } ?: 1f
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(groupLabel(members), style = MaterialTheme.typography.labelLarge, color = OneEmuColors.Accent, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.le_visible), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = visible,
                            onCheckedChange = { v ->
                                var l = currentLayout
                                for (m in members) l = l.update(ov, m.desc) { it.copy(visible = v) }
                                layout = l; dirty = true
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.le_scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                        Slider(
                            value = scale,
                            onValueChange = { s ->
                                var l = currentLayout
                                for (m in members) l = l.update(ov, m.desc) { it.copy(scale = s) }
                                layout = l; dirty = true
                            },
                            valueRange = 0.6f..1.8f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                    }
                } else {
                    Text(stringResource(R.string.se_hint), style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.OnSurfaceMuted)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.le_opacity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Slider(value = opacity, onValueChange = { opacity = it; dirty = true }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                    Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { layout = SkinLayout.EMPTY; selected = null; dirty = true }) { Text(stringResource(R.string.se_reset)) }
                }
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

private fun groupBounds(placed: List<PlacedDesc>, group: List<Int>): Rect {
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
    }
}
