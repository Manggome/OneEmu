package com.manggome.oneemu.emu.pad

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.model.SystemId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Live values the overlay reports; the emulator screen merges them with the physical gamepad. */
data class PadInput(val mask: Int = 0, val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0)

/**
 * The on-screen controller. Handles every pointer itself so multi-touch and finger slides between
 * buttons work; touches that land on nothing are forwarded to [onPointer] as game-screen touches
 * when [gameRect] is given (NDS / 3DS).
 *
 * @param hapticMs -1 disables press feedback, 0 uses the system tick, >0 vibrates for that long.
 */
@Composable
fun VirtualPad(
    layout: PadLayout,
    system: SystemId,
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
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(Size.Zero) }
    val textMeasurer = rememberTextMeasurer()

    // Latest parameters, readable from the long-lived pointer coroutine.
    val layoutState = rememberUpdatedState(layout)
    val scaleState = rememberUpdatedState(globalScale)
    val hapticState = rememberUpdatedState(hapticMs)
    val hapticsState = rememberUpdatedState(haptics)
    val gameRectState = rememberUpdatedState(gameRect)
    val ffState = rememberUpdatedState(fastForwardActive)
    val onInputState = rememberUpdatedState(onInput)
    val onPointerState = rememberUpdatedState(onPointer)
    val onMenuState = rememberUpdatedState(onMenu)
    val onFfState = rememberUpdatedState(onFastForward)

    // Visual state read by the canvas.
    var pressedMask by remember { mutableIntStateOf(0) }
    var pressedElements by remember { mutableStateOf<Set<PadElementId>>(emptySet()) }
    var leftStick by remember { mutableStateOf(Offset.Zero) }
    var rightStick by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                val trackers = HashMap<PointerId, Tracker>()
                var lastInput = PadInput()
                var lastPressedKeys = 0L

                fun placedElements(): List<Placed> {
                    val s = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    return layoutState.value.elements.filter { it.visible }.map { Placed(it, it.rectOn(s, density, scaleState.value)) }
                }

                fun recompute() {
                    var mask = 0
                    val els = HashSet<PadElementId>()
                    var l = Offset.Zero
                    var r = Offset.Zero
                    for (t in trackers.values) when (t) {
                        is Tracker.Dpad -> mask = mask or t.mask
                        is Tracker.Btn -> { mask = mask or t.mask; els += t.elements }
                        is Tracker.Stick -> { els += t.id; if (t.id == PadElementId.LEFT_STICK) l = t.value else r = t.value }
                        is Tracker.Small -> els += t.id
                        else -> {}
                    }
                    pressedMask = mask
                    pressedElements = els
                    leftStick = l
                    rightStick = r
                    val input = PadInput(mask, (l.x * 32767).toInt(), (l.y * 32767).toInt(), (r.x * 32767).toInt(), (r.y * 32767).toInt())
                    if (input != lastInput) {
                        lastInput = input
                        onInputState.value(input)
                    }
                    // Haptic tick on any newly pressed direction/button.
                    var keys = mask.toLong()
                    for (e in els) keys = keys or (1L shl (16 + e.ordinal))
                    if (keys and lastPressedKeys.inv() != 0L && hapticState.value >= 0) hapticsState.value?.tick(hapticState.value)
                    lastPressedKeys = keys
                }

                fun begin(change: PointerInputChange) {
                    val p = change.position
                    val placed = placedElements()
                    val hit = hitTest(p, placed)
                    val tracker: Tracker = when (hit?.element?.id?.kind) {
                        null -> {
                            val gr = gameRectState.value
                            if (gr != null && gr.contains(p) && trackers.values.none { it is Tracker.GameTouch }) {
                                Tracker.GameTouch(gr).also { it.update(p, onPointerState.value) }
                            } else Tracker.None
                        }
                        PadElementId.Kind.DPAD -> Tracker.Dpad(hit.rect.center, hit.rect.width / 2f).also { it.mask = dpadMask(p, it.center, it.radius) }
                        PadElementId.Kind.STICK -> Tracker.Stick(hit.element.id, hit.rect.center, hit.rect.width / 2f).also { it.update(p) }
                        PadElementId.Kind.SMALL -> Tracker.Small(hit.element.id, hit.rect, System.currentTimeMillis(), ffState.value).also {
                            if (it.id == PadElementId.FAST_FORWARD) onFfState.value(true)
                        }
                        else -> Tracker.Btn().also { it.update(p, hit) }
                    }
                    trackers[change.id] = tracker
                }

                fun move(change: PointerInputChange) {
                    val p = change.position
                    when (val t = trackers[change.id]) {
                        is Tracker.Dpad -> t.mask = dpadMask(p, t.center, t.radius)
                        is Tracker.Stick -> t.update(p)
                        is Tracker.Btn -> t.update(p, hitTest(p, placedElements(), slidableOnly = true))
                        is Tracker.GameTouch -> t.update(p, onPointerState.value)
                        else -> {}
                    }
                }

                fun end(change: PointerInputChange) {
                    when (val t = trackers.remove(change.id)) {
                        is Tracker.Small -> {
                            val inside = inflate(t.rect, 0.6f).contains(change.position)
                            if (t.id == PadElementId.MENU) {
                                if (inside) onMenuState.value()
                            } else if (t.id == PadElementId.FAST_FORWARD) {
                                val held = System.currentTimeMillis() - t.downAt
                                onFfState.value(if (held < LONG_PRESS_MS) !t.ffWasActive else t.ffWasActive)
                            }
                        }
                        is Tracker.GameTouch -> onPointerState.value(t.lastX, t.lastY, false)
                        else -> {}
                    }
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
            if (size == Size.Zero) return@Canvas
            for (element in layout.elements) {
                if (!element.visible) continue
                val rect = element.rectOn(size, density, globalScale)
                val stick = when (element.id) {
                    PadElementId.LEFT_STICK -> leftStick
                    PadElementId.RIGHT_STICK -> rightStick
                    else -> Offset.Zero
                }
                val elements = if (element.id == PadElementId.FAST_FORWARD && fastForwardActive) pressedElements + element.id else pressedElements
                drawPadElement(element, rect, system, PadElementVisual(pressedMask, elements, stick), textMeasurer)
            }
        }
    }
}

private const val LONG_PRESS_MS = 350L

private class Placed(val element: PadElement, val rect: Rect)

private sealed class Tracker {
    object None : Tracker()

    class Dpad(val center: Offset, val radius: Float) : Tracker() { var mask = 0 }

    class Stick(val id: PadElementId, val center: Offset, val radius: Float) : Tracker() {
        var value = Offset.Zero
        fun update(p: Offset) {
            val travel = radius * 0.55f
            var dx = (p.x - center.x) / travel
            var dy = (p.y - center.y) / travel
            val len = hypot(dx, dy)
            if (len > 1f) { dx /= len; dy /= len }
            value = Offset(dx, dy)
        }
    }

    /** A finger on face buttons; may slide between them. */
    class Btn : Tracker() {
        var mask = 0
        var elements: Set<PadElementId> = emptySet()
        fun update(p: Offset, hit: Placed?) {
            if (hit == null) { mask = 0; elements = emptySet(); return }
            if (hit.element.id.kind == PadElementId.Kind.CLUSTER) {
                elements = clusterHit(p, hit.rect)
            } else {
                elements = setOf(hit.element.id)
            }
            mask = elements.fold(0) { acc, e -> acc or e.mask }
        }
    }

    class Small(val id: PadElementId, val rect: Rect, val downAt: Long, val ffWasActive: Boolean) : Tracker()

    class GameTouch(val rect: Rect) : Tracker() {
        var lastX = 0f
        var lastY = 0f
        fun update(p: Offset, onPointer: (Float, Float, Boolean) -> Unit) {
            lastX = ((p.x - rect.left) / rect.width).coerceIn(0f, 1f)
            lastY = ((p.y - rect.top) / rect.height).coerceIn(0f, 1f)
            onPointer(lastX, lastY, true)
        }
    }
}

private fun inflate(r: Rect, fraction: Float): Rect {
    val dx = r.width * fraction / 2f
    val dy = r.height * fraction / 2f
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

/** Topmost element under [p] (later elements win), with a little slop so edge taps register. */
private fun hitTest(p: Offset, placed: List<Placed>, slidableOnly: Boolean = false): Placed? {
    for (i in placed.indices.reversed()) {
        val item = placed[i]
        val id = item.element.id
        if (slidableOnly && !id.slidable) continue
        val r = item.rect
        val hit = when (id.kind) {
            PadElementId.Kind.ROUND, PadElementId.Kind.STICK, PadElementId.Kind.CLUSTER ->
                hypot(p.x - r.center.x, p.y - r.center.y) <= r.width / 2f * (if (id.kind == PadElementId.Kind.STICK) 1.1f else 1.25f)
            PadElementId.Kind.DPAD -> inflate(r, 0.2f).contains(p)
            PadElementId.Kind.PILL, PadElementId.Kind.SMALL -> inflate(r, 0.3f).contains(p)
        }
        if (hit) return item
    }
    return null
}

/** 8-way d-pad: cardinal arms, with 45° diagonal zones at the corners. */
internal fun dpadMask(p: Offset, center: Offset, radius: Float): Int {
    val dx = p.x - center.x
    val dy = p.y - center.y
    if (hypot(dx, dy) < radius * 0.18f) return 0
    val ax = abs(dx)
    val ay = abs(dy)
    var m = 0
    if (ax > ay * 0.414f) m = m or (if (dx < 0) Buttons.LEFT else Buttons.RIGHT)
    if (ay > ax * 0.414f) m = m or (if (dy < 0) Buttons.UP else Buttons.DOWN)
    return m
}

/** Which of the four cluster buttons a finger at [p] presses; between two buttons both press. */
private fun clusterHit(p: Offset, rect: Rect): Set<PadElementId> {
    val dx = p.x - rect.center.x
    val dy = p.y - rect.center.y
    if (hypot(dx, dy) < rect.width * 0.08f) return emptySet()
    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
    // 0° = right (A), 90° = down (B), 180° = left (Y), 270° = up (X)
    val sector = ((deg + 22.5) / 45.0).toInt() % 8
    return when (sector) {
        0 -> setOf(PadElementId.BUTTON_A)
        1 -> setOf(PadElementId.BUTTON_A, PadElementId.BUTTON_B)
        2 -> setOf(PadElementId.BUTTON_B)
        3 -> setOf(PadElementId.BUTTON_B, PadElementId.BUTTON_Y)
        4 -> setOf(PadElementId.BUTTON_Y)
        5 -> setOf(PadElementId.BUTTON_Y, PadElementId.BUTTON_X)
        6 -> setOf(PadElementId.BUTTON_X)
        else -> setOf(PadElementId.BUTTON_X, PadElementId.BUTTON_A)
    }
}
