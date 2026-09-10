package com.manggome.oneemu.emu.skin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.pad.PadInput
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.theme.OneEmuColors
import java.util.BitSet

/**
 * Adapts an overlay for display in one orientation: `overlay_next` buttons that lead to a menu page act as
 * menu toggles, buttons that lead to the other orientation's layout are hidden (we auto-rotate instead).
 */
fun OverlayCfg.resolved(overlay: Overlay, landscape: Boolean): Overlay {
    val wanted = if (landscape) OverlayOrientation.LANDSCAPE else OverlayOrientation.PORTRAIT
    val hasMenuButton = overlay.descs.any { it.action == DescAction.MenuToggle && it.image != null }
    var changed = false
    val descs = overlay.descs.map { d ->
        val a = d.action as? DescAction.OverlayNext ?: return@map d
        val target = byName(a.target ?: overlay.next) ?: after(overlay)
        val replacement = when {
            target == null -> DescAction.Unsupported
            target.isMenuLike -> if (hasMenuButton) DescAction.Unsupported else DescAction.MenuToggle
            target.orientation != null && target.orientation != wanted -> DescAction.Unsupported
            else -> null
        } ?: return@map d
        changed = true
        d.copy(action = replacement)
    }
    return if (changed) overlay.copy(descs = descs) else overlay
}

/** Live state the drawing code needs; [pressed] is indexed like the placed list. */
class SkinVisual(
    val pressed: BitSet? = null,
    /** libretro mask currently down; button images whose mask is contained in it light up too. */
    val pressedMask: Int = 0,
    val leftStick: Offset = Offset.Zero,
    val rightStick: Offset = Offset.Zero,
    val fastForwardActive: Boolean = false,
    /** Editor: draw hidden descs faintly instead of skipping them. */
    val showHidden: Boolean = false,
)

/** Draws background + every drawable desc of one placed overlay. Shared by the pad, the editor and the picker. */
fun DrawScope.drawOverlay(skin: LoadedSkin, overlay: Overlay, frame: OverlayFrame, placed: List<PlacedDesc>, visual: SkinVisual, tint: Color) {
    overlay.backgroundImage?.let { bg ->
        val img = skin.image(bg) ?: return@let
        val box = if (overlay.fullScreen) Rect(Offset.Zero, frame.screen) else frame.box
        drawImage(img, dstOffset = IntOffset(box.left.toInt(), box.top.toInt()), dstSize = IntSize(box.width.toInt(), box.height.toInt()))
    }
    for (i in placed.indices) {
        val p = placed[i]
        val d = p.desc
        if (!d.drawable) continue
        if (!p.visible && !visual.showHidden) continue
        val img = skin.image(d.image) ?: continue
        var w = (p.rx * 2f).toInt()
        var h = (p.ry * 2f).toInt()
        if (w <= 0 || h <= 0) continue
        var cx = p.cx
        var cy = p.cy
        val analog = d.action as? DescAction.Analog
        if (analog != null) {
            val v = if (analog.right) visual.rightStick else visual.leftStick
            cx += v.x * p.rx * 0.5f
            cy += v.y * p.ry * 0.5f
        }
        val press = d.action as? DescAction.Press
        val pressed = (visual.pressed?.get(i) == true) ||
            (press != null && visual.pressedMask and press.mask == press.mask) ||
            (visual.fastForwardActive && d.action is DescAction.FastForward)
        if (pressed) { w = (w * 1.06f).toInt(); h = (h * 1.06f).toInt() }
        val dst = IntOffset((cx - w / 2f).toInt(), (cy - h / 2f).toInt())
        val size = IntSize(w, h)
        val alpha = if (!p.visible) 0.25f else 1f
        drawImage(img, dstOffset = dst, dstSize = size, alpha = alpha)
        if (pressed) drawImage(img, dstOffset = dst, dstSize = size, colorFilter = ColorFilter.tint(tint, BlendMode.SrcAtop))
    }
}

/**
 * Image-skin counterpart of `VirtualPad`: same callbacks, same [PadInput] output, driven by a RetroArch
 * overlay. Every pointer is tracked separately; touches that hit nothing fall through to [onPointer]
 * inside [gameRect] (touch-screen systems).
 */
@Composable
fun SkinPad(
    skin: LoadedSkin,
    layout: SkinLayout,
    system: SystemId,
    landscape: Boolean,
    opacity: Float,
    globalScale: Float,
    hapticMs: Int,
    haptics: Haptics?,
    gameRect: Rect?,
    fastForwardActive: Boolean,
    onInput: (PadInput) -> Unit,
    onPointer: (x: Float, y: Float, pressed: Boolean) -> Unit,
    onMenu: () -> Unit,
    onFastForward: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    val screenAspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
    var size by remember { mutableStateOf(Size.Zero) }
    val cfg = skin.cfg(landscape)

    var overlayName by remember(skin, landscape) { mutableStateOf<String?>(null) }
    val overlay = remember(cfg, landscape, overlayName, screenAspect) {
        val base = cfg.byName(overlayName) ?: cfg.pick(landscape, screenAspect, preferAnalog = system.hasAnalog)
        base?.let { cfg.resolved(it, landscape) }
    }
    val placement = remember(overlay, size, layout, globalScale) {
        overlay?.let { placeOverlay(it, size, landscape, layout, globalScale) }
    }

    // Latest values for the long-lived pointer coroutine.
    val overlayState = rememberUpdatedState(overlay)
    val placedState = rememberUpdatedState(placement?.second ?: emptyList())
    val hapticState = rememberUpdatedState(hapticMs)
    val hapticsState = rememberUpdatedState(haptics)
    val gameRectState = rememberUpdatedState(gameRect)
    val ffState = rememberUpdatedState(fastForwardActive)
    val onInputState = rememberUpdatedState(onInput)
    val onPointerState = rememberUpdatedState(onPointer)
    val onMenuState = rememberUpdatedState(onMenu)
    val onFfState = rememberUpdatedState(onFastForward)
    val switchOverlay = rememberUpdatedState<(DescAction.OverlayNext) -> Unit> { a ->
        val cur = overlayState.value ?: return@rememberUpdatedState
        val next = cfg.byName(a.target ?: cur.next) ?: cfg.after(cur) ?: return@rememberUpdatedState
        overlayName = next.name
    }

    // Visual state: the BitSet is mutated in place, [visualVersion] tells the canvas to redraw.
    val pressedBits = remember { BitSet() }
    var visualVersion by remember { mutableIntStateOf(0) }
    var pressedMask by remember { mutableIntStateOf(0) }
    var leftStick by remember { mutableStateOf(Offset.Zero) }
    var rightStick by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(skin, landscape) {
                val tracks = ArrayList<Track>(10)
                val free = ArrayList<Track>(10)
                val nextBits = BitSet()
                var lastInput = PadInput()
                var lastMask = 0

                fun trackFor(id: PointerId): Track? { for (t in tracks) if (t.id == id) return t; return null }

                fun evaluatePad(t: Track, px: Float, py: Float, placed: List<PlacedDesc>, overlay: Overlay) {
                    t.mask = 0; t.lx = 0f; t.ly = 0f; t.rx = 0f; t.ry = 0f
                    t.next.clear()
                    val bound = t.bound
                    if (bound != null) {
                        if (bound.contains(px, py, bound.desc.rangeMod ?: overlay.rangeMod)) {
                            t.apply(bound, px, py); t.next.add(bound)
                            swapHeld(t); return
                        }
                        t.bound = null
                    }
                    for (p in placed) {
                        if (!p.visible || !p.desc.interactive || !p.desc.action.isPad) continue
                        val scale = if (t.held.contains(p)) (p.desc.rangeMod ?: overlay.rangeMod) else 1f
                        if (!p.contains(px, py, scale)) continue
                        if (p.desc.exclusive) {
                            t.next.clear(); t.mask = 0; t.lx = 0f; t.ly = 0f; t.rx = 0f; t.ry = 0f
                            t.apply(p, px, py); t.next.add(p)
                            break
                        }
                        t.apply(p, px, py); t.next.add(p)
                        if (p.desc.rangeModExclusive) t.bound = p
                    }
                    swapHeld(t)
                }

                fun recompute() {
                    var mask = 0
                    var l = Offset.Zero
                    var r = Offset.Zero
                    nextBits.clear()
                    val placed = placedState.value
                    for (t in tracks) {
                        if (t.kind != Kind.PAD) continue
                        mask = mask or t.mask
                        if (t.lx != 0f || t.ly != 0f) l = Offset(t.lx, t.ly)
                        if (t.rx != 0f || t.ry != 0f) r = Offset(t.rx, t.ry)
                        for (p in t.held) { val i = placed.indexOf(p); if (i >= 0) nextBits.set(i) }
                    }
                    // Haptic tick on any newly pressed desc or direction.
                    var newPress = mask and lastMask.inv() != 0
                    if (!newPress) {
                        var i = nextBits.nextSetBit(0)
                        while (i >= 0) { if (!pressedBits.get(i)) { newPress = true; break }; i = nextBits.nextSetBit(i + 1) }
                    }
                    if (newPress && hapticState.value >= 0) hapticsState.value?.tick(hapticState.value)
                    lastMask = mask
                    if (mask != pressedMask) pressedMask = mask
                    if (nextBits != pressedBits) { pressedBits.clear(); pressedBits.or(nextBits); visualVersion++ }
                    if (l != leftStick) leftStick = l
                    if (r != rightStick) rightStick = r
                    val input = PadInput(mask, (l.x * 32767).toInt(), (l.y * 32767).toInt(), (r.x * 32767).toInt(), (r.y * 32767).toInt())
                    if (input != lastInput) { lastInput = input; onInputState.value(input) }
                }

                fun begin(change: PointerInputChange) {
                    val p = change.position
                    val t = (free.removeLastOrNull() ?: Track()).reset(change.id)
                    tracks += t
                    val overlay = overlayState.value ?: return
                    val placed = placedState.value
                    // Tap-style hotkeys first (menu / overlay switch / fast-forward).
                    for (pd in placed) {
                        if (!pd.visible || !pd.desc.interactive || pd.desc.action.isPad) continue
                        if (pd.contains(p.x, p.y)) {
                            t.kind = Kind.TAP; t.tap = pd; t.downAt = System.currentTimeMillis(); t.ffWasActive = ffState.value
                            if ((pd.desc.action as? DescAction.FastForward)?.hold == true) onFfState.value(true)
                            return
                        }
                    }
                    t.kind = Kind.PAD
                    evaluatePad(t, p.x, p.y, placed, overlay)
                    if (t.held.isEmpty()) {
                        val gr = gameRectState.value
                        if (gr != null && gr.contains(p) && tracks.none { it.kind == Kind.GAME }) {
                            t.kind = Kind.GAME
                            t.game(p, gr, onPointerState.value)
                        } else {
                            // Missed everything; stay a PAD tracker so a slide onto a button still registers.
                        }
                    }
                }

                fun move(change: PointerInputChange) {
                    val t = trackFor(change.id) ?: return
                    val p = change.position
                    when (t.kind) {
                        Kind.PAD -> overlayState.value?.let { evaluatePad(t, p.x, p.y, placedState.value, it) }
                        Kind.GAME -> t.game(p, t.gameRect, onPointerState.value)
                        else -> {}
                    }
                }

                fun end(change: PointerInputChange) {
                    val t = trackFor(change.id) ?: return
                    tracks.remove(t)
                    val p = change.position
                    when (t.kind) {
                        Kind.TAP -> {
                            val pd = t.tap
                            if (pd != null) {
                                val inside = pd.contains(p.x, p.y, 1.6f)
                                when (val a = pd.desc.action) {
                                    DescAction.MenuToggle -> if (inside) onMenuState.value()
                                    is DescAction.OverlayNext -> if (inside) switchOverlay.value(a)
                                    is DescAction.FastForward -> {
                                        if (a.hold) onFfState.value(false)
                                        else if (inside) onFfState.value(!t.ffWasActive)
                                    }
                                    else -> {}
                                }
                            }
                        }
                        Kind.GAME -> onPointerState.value(t.lastX, t.lastY, false)
                        else -> {}
                    }
                    free += t.reset(PointerId(-1))
                }

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            when {
                                change.changedToDownIgnoreConsumed() -> begin(change)
                                change.changedToUpIgnoreConsumed() -> end(change)
                                change.pressed -> move(change)
                            }
                            change.consume()
                        }
                        recompute()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.coerceIn(0.05f, 1f) }) {
            val ov = overlay ?: return@Canvas
            val pl = placement ?: return@Canvas
            @Suppress("UNUSED_EXPRESSION") visualVersion
            drawOverlay(skin, ov, pl.first, pl.second, SkinVisual(pressedBits, pressedMask, leftStick, rightStick, fastForwardActive), PressTint)
        }
    }
}

private val PressTint = OneEmuColors.Accent.copy(alpha = 0.45f)

private enum class Kind { NONE, PAD, TAP, GAME }

/** Per-pointer state; instances are pooled so the pointer loop does not allocate. */
private class Track {
    var id: PointerId = PointerId(-1)
    var kind = Kind.NONE
    var held = ArrayList<PlacedDesc>(6)
    var next = ArrayList<PlacedDesc>(6)
    var bound: PlacedDesc? = null
    var mask = 0
    var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f
    var tap: PlacedDesc? = null
    var downAt = 0L
    var ffWasActive = false
    var gameRect: Rect = Rect.Zero
    var lastX = 0f; var lastY = 0f

    fun reset(id: PointerId): Track {
        this.id = id; kind = Kind.NONE; held.clear(); next.clear(); bound = null
        mask = 0; lx = 0f; ly = 0f; rx = 0f; ry = 0f; tap = null; downAt = 0L; ffWasActive = false
        gameRect = Rect.Zero; lastX = 0f; lastY = 0f
        return this
    }

    fun apply(p: PlacedDesc, px: Float, py: Float) {
        when (val a = p.desc.action) {
            is DescAction.Press -> mask = mask or a.mask
            DescAction.DpadArea -> mask = mask or areaDpadMask(px, py, p)
            DescAction.AbxyArea -> mask = mask or areaAbxyMask(px, py, p)
            is DescAction.Analog -> {
                val v = analogValue(px, py, p)
                if (a.right) { rx = v.x; ry = v.y } else { lx = v.x; ly = v.y }
            }
            else -> {}
        }
    }

    fun game(p: Offset, rect: Rect, onPointer: (Float, Float, Boolean) -> Unit) {
        gameRect = rect
        lastX = ((p.x - rect.left) / rect.width).coerceIn(0f, 1f)
        lastY = ((p.y - rect.top) / rect.height).coerceIn(0f, 1f)
        onPointer(lastX, lastY, true)
    }
}

private fun swapHeld(t: Track) {
    val tmp = t.held
    t.held = t.next
    t.next = tmp
}
