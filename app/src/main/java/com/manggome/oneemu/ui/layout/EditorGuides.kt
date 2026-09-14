package com.manggome.oneemu.ui.layout

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Shared editing geometry for the two virtual-pad editors (vector `LayoutEditor`, image `SkinEditor`):
 * PowerPoint-style smart guides, axis lock, undo history, alignment operations and the pointer loop
 * that drives them. Everything here works in canvas pixels; the editors convert to/from their
 * normalized 0..1 coordinates.
 */

/** Distance (dp) within which an edge/centre snaps to a guide. */
const val SNAP_THRESHOLD_DP = 6f
/** Movement (dp) after which the axis lock decides between horizontal and vertical. */
const val AXIS_LOCK_DECIDE_DP = 12f

enum class GuideKind { ALIGN, MIRROR, SPACING, AXIS }

sealed interface Guide {
    /** Straight guide line; [pos] is x for vertical lines, y for horizontal ones. */
    data class Line(val vertical: Boolean, val pos: Float, val from: Float, val to: Float, val kind: GuideKind) : Guide
    /** Equal-gap marker spanning [from]..[to] along the axis, drawn at [at] on the other axis. */
    data class Gap(val horizontal: Boolean, val from: Float, val to: Float, val at: Float) : Guide
}

class SnapOutcome(val rect: Rect, val snappedX: Boolean, val snappedY: Boolean, val guides: List<Guide>)

/** The mock game rectangle both editors draw when opened from settings (4:3 image). */
fun mockGameRect(size: Size, landscape: Boolean): Rect {
    val aspect = 4f / 3f
    return if (landscape) {
        val h = size.height * 0.92f
        val w = minOf(h * aspect, size.width * 0.6f)
        Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, w / aspect))
    } else {
        val w = size.width
        Rect(Offset(0f, size.height * 0.06f), Size(w, w / aspect))
    }
}

fun unionOf(rects: List<Rect>): Rect? {
    var r: Rect? = null
    for (b in rects) r = r?.let { Rect(min(it.left, b.left), min(it.top, b.top), max(it.right, b.right), max(it.bottom, b.bottom)) } ?: b
    return r
}

// ---------------------------------------------------------------------------------------------
// Smart guides
// ---------------------------------------------------------------------------------------------

/** A rect seen along one axis: [lo]..[hi] on the axis, [plo]..[phi] on the perpendicular one. */
private class Span(val lo: Float, val hi: Float, val plo: Float, val phi: Float) {
    val mid get() = (lo + hi) / 2f
    val len get() = hi - lo
    val pmid get() = (plo + phi) / 2f
    fun overlapsPerp(o: Span) = phi > o.plo && plo < o.phi
}

private fun Rect.spanX() = Span(left, right, top, bottom)
private fun Rect.spanY() = Span(top, bottom, left, right)

private class Cand(
    val delta: Float,
    val kind: GuideKind,
    /** Guide line positions on this axis once snapped. */
    val lines: List<Float>,
    val from: Float,
    val to: Float,
    /** Gap markers as (from, to, at). */
    val gaps: List<Triple<Float, Float, Float>>,
    val priority: Int,
)

object SmartGuides {
    /**
     * Snaps [moving] against [others] (draggable elements), [fixed] (game image, skin frame) and the
     * canvas centre lines. Rules, in priority order when several are within [threshold] px:
     *  1. alignment — left/centre/right (top/centre/bottom) of the element to the same line of another
     *     element, to a fixed rect's edges/centre, or to the screen centre line (solid guide);
     *  2. mirror — the element's distance from the right screen edge equals another element's distance
     *     from the left edge, by centre or by edge (dashed guides + gap markers), horizontal axis only;
     *  3. equal spacing — three elements on a row/column get equal gaps (gap markers).
     * [allowX]/[allowY] are false for an axis frozen by the axis lock.
     */
    fun snap(
        moving: Rect,
        others: List<Rect>,
        fixed: List<Rect>,
        canvas: Size,
        threshold: Float,
        allowX: Boolean = true,
        allowY: Boolean = true,
    ): SnapOutcome {
        val othersX = others.map { it.spanX() }
        val othersY = others.map { it.spanY() }
        val fixedX = fixed.map { it.spanX() }
        val fixedY = fixed.map { it.spanY() }
        val pad = threshold * 3f

        val dx = if (allowX) pick(candidates(moving.spanX(), othersX, fixedX, canvas.width, canvas.height, mirror = true, pad), threshold) else null
        val dy = if (allowY) pick(candidates(moving.spanY(), othersY, fixedY, canvas.height, canvas.width, mirror = false, pad), threshold) else null
        val snapped = moving.translate(dx ?: 0f, dy ?: 0f)

        val guides = ArrayList<Guide>()
        if (dx != null) {
            for (c in candidates(snapped.spanX(), othersX, fixedX, canvas.width, canvas.height, mirror = true, pad)) {
                if (abs(c.delta) > 0.5f) continue
                for (l in c.lines) guides += Guide.Line(vertical = true, pos = l, from = c.from, to = c.to, kind = c.kind)
                for ((f, t, at) in c.gaps) guides += Guide.Gap(horizontal = true, from = f, to = t, at = at)
            }
        }
        if (dy != null) {
            for (c in candidates(snapped.spanY(), othersY, fixedY, canvas.height, canvas.width, mirror = false, pad)) {
                if (abs(c.delta) > 0.5f) continue
                for (l in c.lines) guides += Guide.Line(vertical = false, pos = l, from = c.from, to = c.to, kind = c.kind)
                for ((f, t, at) in c.gaps) guides += Guide.Gap(horizontal = false, from = f, to = t, at = at)
            }
        }
        return SnapOutcome(snapped, dx != null, dy != null, dedupe(guides))
    }

    private fun pick(cands: List<Cand>, threshold: Float): Float? {
        val band = (threshold / 3f).coerceAtLeast(1f)
        return cands.filter { abs(it.delta) <= threshold }
            .minWithOrNull(compareBy<Cand>({ (abs(it.delta) / band).toInt() }, { it.priority }, { abs(it.delta) }))
            ?.delta
    }

    private fun candidates(m: Span, others: List<Span>, fixed: List<Span>, canvasLen: Float, canvasPerp: Float, mirror: Boolean, pad: Float): List<Cand> {
        val out = ArrayList<Cand>()
        fun align(target: Float, movingLine: Float, from: Float, to: Float, prio: Int) {
            out += Cand(target - movingLine, GuideKind.ALIGN, listOf(target), from, to, emptyList(), prio)
        }
        // Screen centre line.
        align(canvasLen / 2f, m.mid, 0f, canvasPerp, 0)
        // Fixed rects: game image / skin frame edges and centre.
        for (f in fixed) {
            for (t in floatArrayOf(f.lo, f.mid, f.hi)) for (ml in floatArrayOf(m.lo, m.mid, m.hi)) align(t, ml, 0f, canvasPerp, 1)
        }
        for (o in others) {
            val from = min(o.plo, m.plo) - pad
            val to = max(o.phi, m.phi) + pad
            align(o.lo, m.lo, from, to, 2)
            align(o.hi, m.hi, from, to, 2)
            align(o.mid, m.mid, from, to, 2)
            align(o.lo, m.hi, from, to, 2)
            align(o.hi, m.lo, from, to, 2)
            if (mirror) {
                fun mirrorCand(oLine: Float, mLine: Float) {
                    val target = canvasLen - oLine
                    // Gap markers show the two equal edge distances (element→nearest screen edge on each side).
                    val gaps = if (target <= canvasLen / 2f) listOf(Triple(0f, target, m.pmid), Triple(oLine, canvasLen, o.pmid))
                    else listOf(Triple(target, canvasLen, m.pmid), Triple(0f, oLine, o.pmid))
                    out += Cand(target - mLine, GuideKind.MIRROR, listOf(target, oLine), from, to, gaps, 3)
                }
                mirrorCand(o.mid, m.mid)
                mirrorCand(o.lo, m.hi)
                mirrorCand(o.hi, m.lo)
            }
        }
        // Equal spacing with two other elements on the same row/column.
        val inline = others.filter { it.overlapsPerp(m) }.sortedBy { it.mid }
        for (i in inline.indices) for (j in i + 1 until inline.size) {
            val a = inline[i]
            val b = inline[j]
            if (b.lo <= a.hi) continue
            if (inline.any { it !== a && it !== b && it.mid > a.hi && it.mid < b.lo }) continue
            val g = b.lo - a.hi
            val at = m.pmid
            if (m.mid > b.mid) {
                out += Cand((b.hi + g) - m.lo, GuideKind.SPACING, emptyList(), 0f, 0f, listOf(Triple(a.hi, b.lo, at), Triple(b.hi, b.hi + g, at)), 4)
            }
            if (m.mid < a.mid) {
                out += Cand((a.lo - g) - m.hi, GuideKind.SPACING, emptyList(), 0f, 0f, listOf(Triple(a.lo - g, a.lo, at), Triple(a.hi, b.lo, at)), 4)
            }
            if (g > m.len && m.mid > a.mid && m.mid < b.mid) {
                val half = (g - m.len) / 2f
                out += Cand((a.hi + b.lo) / 2f - m.mid, GuideKind.SPACING, emptyList(), 0f, 0f, listOf(Triple(a.hi, a.hi + half, at), Triple(b.lo - half, b.lo, at)), 4)
            }
        }
        return out
    }

    private fun dedupe(guides: List<Guide>): List<Guide> = guides.distinctBy { g ->
        when (g) {
            is Guide.Line -> Triple(g.vertical, (g.pos * 2).roundToInt(), g.kind)
            is Guide.Gap -> Triple(g.horizontal, (g.from * 2).roundToInt(), (g.to * 2).roundToInt())
        }
    }.take(24)
}

/** Draws [guides]: 1 dp lines, solid for alignment, dashed for mirror/spacing/axis; gap markers get end ticks. */
fun DrawScope.drawGuides(guides: List<Guide>, color: Color, density: Float) {
    val stroke = 1f * density
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))
    val tick = 4f * density
    for (g in guides) when (g) {
        is Guide.Line -> {
            val effect = if (g.kind == GuideKind.ALIGN) null else dash
            val from = if (g.to <= g.from) 0f else g.from
            val to = if (g.to <= g.from) (if (g.vertical) size.height else size.width) else g.to
            if (g.vertical) drawLine(color, Offset(g.pos, from), Offset(g.pos, to), stroke, pathEffect = effect)
            else drawLine(color, Offset(from, g.pos), Offset(to, g.pos), stroke, pathEffect = effect)
        }
        is Guide.Gap -> {
            if (g.horizontal) {
                drawLine(color, Offset(g.from, g.at), Offset(g.to, g.at), stroke, pathEffect = dash)
                drawLine(color, Offset(g.from, g.at - tick), Offset(g.from, g.at + tick), stroke)
                drawLine(color, Offset(g.to, g.at - tick), Offset(g.to, g.at + tick), stroke)
            } else {
                drawLine(color, Offset(g.at, g.from), Offset(g.at, g.to), stroke, pathEffect = dash)
                drawLine(color, Offset(g.at - tick, g.from), Offset(g.at + tick, g.from), stroke)
                drawLine(color, Offset(g.at - tick, g.to), Offset(g.at + tick, g.to), stroke)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Axis lock (PowerPoint Shift)
// ---------------------------------------------------------------------------------------------

enum class Axis { HORIZONTAL, VERTICAL }

/** Constrains a drag to its dominant axis once [decideDistPx] of travel has happened while engaged. */
class AxisLock(private val decideDistPx: Float) {
    var axis: Axis? = null
        private set

    fun apply(total: Offset, engaged: Boolean): Offset {
        if (!engaged) { axis = null; return total }
        if (axis == null) {
            if (total.getDistance() < decideDistPx) return total
            axis = if (abs(total.x) >= abs(total.y)) Axis.HORIZONTAL else Axis.VERTICAL
        }
        return if (axis == Axis.HORIZONTAL) Offset(total.x, 0f) else Offset(0f, total.y)
    }
}

// ---------------------------------------------------------------------------------------------
// Undo history
// ---------------------------------------------------------------------------------------------

/** Linear undo/redo of whole layout snapshots. [record] the state *before* a change. */
class UndoHistory<T>(private val capacity: Int = 50) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var coalesceKey: String? = null

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Consecutive records with the same non-null [key] (slider drags, nudge repeats) collapse into one step. */
    fun record(before: T, key: String? = null) {
        if (key != null && key == coalesceKey) return
        coalesceKey = key
        past.addLast(before)
        while (past.size > capacity) past.removeFirst()
        future.clear()
        refresh()
    }

    fun endCoalesce() { coalesceKey = null }

    fun undo(current: T): T? {
        val s = past.removeLastOrNull() ?: return null
        future.addLast(current)
        coalesceKey = null
        refresh()
        return s
    }

    fun redo(current: T): T? {
        val s = future.removeLastOrNull() ?: return null
        past.addLast(current)
        coalesceKey = null
        refresh()
        return s
    }

    fun clear() { past.clear(); future.clear(); coalesceKey = null; refresh() }

    private fun refresh() { canUndo = past.isNotEmpty(); canRedo = future.isNotEmpty() }
}

// ---------------------------------------------------------------------------------------------
// Multi-select alignment
// ---------------------------------------------------------------------------------------------

/** Alignment/distribution over selected rects; each returns the new centre for every input rect. */
object AlignOps {
    /** 가로 정렬(중심): one row — every centre Y becomes the selection's middle. */
    fun alignRow(rects: List<Rect>): List<Offset> {
        val u = unionOf(rects) ?: return rects.map { it.center }
        return rects.map { Offset(it.center.x, u.center.y) }
    }

    /** 세로 정렬(중심): one column — every centre X becomes the selection's middle. */
    fun alignColumn(rects: List<Rect>): List<Offset> {
        val u = unionOf(rects) ?: return rects.map { it.center }
        return rects.map { Offset(u.center.x, it.center.y) }
    }

    /** 가로 균등 배치: equal horizontal gaps between the leftmost and rightmost element. */
    fun distributeHorizontally(rects: List<Rect>): List<Offset> {
        if (rects.size < 3) return rects.map { it.center }
        val order = rects.indices.sortedBy { rects[it].center.x }
        val first = rects[order.first()]
        val last = rects[order.last()]
        val total = rects.sumOf { it.width.toDouble() }.toFloat()
        val gap = (last.right - first.left - total) / (rects.size - 1)
        val out = rects.map { it.center }.toMutableList()
        var x = first.left
        for (i in order) {
            val r = rects[i]
            out[i] = Offset(x + r.width / 2f, r.center.y)
            x += r.width + gap
        }
        return out
    }

    /** 세로 균등 배치: equal vertical gaps between the topmost and bottommost element. */
    fun distributeVertically(rects: List<Rect>): List<Offset> {
        if (rects.size < 3) return rects.map { it.center }
        val order = rects.indices.sortedBy { rects[it].center.y }
        val first = rects[order.first()]
        val last = rects[order.last()]
        val total = rects.sumOf { it.height.toDouble() }.toFloat()
        val gap = (last.bottom - first.top - total) / (rects.size - 1)
        val out = rects.map { it.center }.toMutableList()
        var y = first.top
        for (i in order) {
            val r = rects[i]
            out[i] = Offset(r.center.x, y + r.height / 2f)
            y += r.height + gap
        }
        return out
    }

    /**
     * 좌우 대칭 배치: pairs elements from the outside in and mirrors the right one of each pair around the
     * screen's vertical centre (same Y as its partner); an odd middle element goes to the centre.
     */
    fun mirrorHorizontally(rects: List<Rect>, canvasWidth: Float): List<Offset> {
        val out = rects.map { it.center }.toMutableList()
        val order = rects.indices.sortedBy { rects[it].center.x }
        var i = 0
        var j = order.size - 1
        while (i < j) {
            val l = rects[order[i]]
            out[order[j]] = Offset(canvasWidth - l.center.x, l.center.y)
            i++; j--
        }
        if (i == j) out[order[i]] = Offset(canvasWidth / 2f, rects[order[i]].center.y)
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Pointer loop shared by both editors
// ---------------------------------------------------------------------------------------------

/**
 * Editor-side hooks for [editorGestures]. Rects are canvas pixels; [onDragMove] receives the delta of the
 * dragged selection's union rect from where the drag started (already snapped/axis-locked).
 */
interface EditorDragHost<Id : Any> {
    fun hitTest(pos: Offset): Id?
    fun rectOf(id: Id): Rect?
    fun selection(): List<Id>
    fun otherRects(exclude: Set<Id>): List<Rect>
    fun fixedRects(): List<Rect>
    fun axisLockOn(): Boolean
    fun onTap(id: Id?)
    fun onLongPress(id: Id)
    fun onDragStart(ids: Set<Id>)
    fun onDragMove(ids: Set<Id>, delta: Offset)
    fun onDragEnd(ids: Set<Id>)
    /** Last word on the dragged rect (e.g. grid snap on axes the smart guides left alone). */
    fun adjust(rect: Rect, snappedX: Boolean, snappedY: Boolean): Rect = rect
    fun haptic()
}

/** Observable drag state the editors render (guides, readout). */
class EditorDragState {
    var guides by mutableStateOf<List<Guide>>(emptyList())
    var dragging by mutableStateOf(false)
    /** Union rect of what is being dragged, after snapping; null when idle. */
    var dragRect by mutableStateOf<Rect?>(null)
}

private enum class Phase { UP, DRAG }

private suspend fun AwaitPointerEventScope.awaitDragOrUp(id: PointerId, origin: Offset, slop: Float, onMove: (Offset) -> Unit): Phase {
    while (true) {
        val ev = awaitPointerEvent()
        val p = ev.changes.firstOrNull { it.id == id } ?: return Phase.UP
        if (!p.pressed) { p.consume(); return Phase.UP }
        p.consume()
        onMove(p.position)
        if ((p.position - origin).getDistance() > slop) return Phase.DRAG
    }
}

/**
 * Tap selects, long-press toggles multi-selection, drag moves (the whole selection when the grabbed element
 * belongs to it). A second finger held anywhere on the canvas, or [EditorDragHost.axisLockOn], engages the
 * axis lock. Smart guides snap within [SNAP_THRESHOLD_DP].
 */
suspend fun <Id : Any> PointerInputScope.editorGestures(host: EditorDragHost<Id>, state: EditorDragState, densityPx: Float) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val id = host.hitTest(down.position)
        if (id == null) { host.onTap(null); return@awaitEachGesture }
        down.consume()
        var pos = down.position
        val phase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            awaitDragOrUp(down.id, down.position, viewConfiguration.touchSlop) { pos = it }
        }
        when (phase) {
            null -> {
                host.onLongPress(id)
                host.haptic()
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
                return@awaitEachGesture
            }
            Phase.UP -> { host.onTap(id); return@awaitEachGesture }
            Phase.DRAG -> {}
        }

        val selection = host.selection()
        val ids: Set<Id> = if (id in selection && selection.size > 1) selection.toSet() else setOf(id)
        if (ids.size == 1) host.onTap(id)
        val startUnion = unionOf(ids.mapNotNull { host.rectOf(it) }) ?: return@awaitEachGesture
        val others = host.otherRects(ids)
        val fixed = host.fixedRects()
        val canvas = Size(size.width.toFloat(), size.height.toFloat())
        val lock = AxisLock(AXIS_LOCK_DECIDE_DP * densityPx)
        val threshold = SNAP_THRESHOLD_DP * densityPx
        var wasSnappedX = false
        var wasSnappedY = false
        host.onDragStart(ids)
        state.dragging = true

        fun step(pointer: Offset, extraFingers: Int) {
            val raw = pointer - down.position
            val delta = lock.apply(raw, engaged = extraFingers > 0 || host.axisLockOn())
            val moved = startUnion.translate(delta)
            val out = SmartGuides.snap(
                moved, others, fixed, canvas, threshold,
                allowX = lock.axis != Axis.VERTICAL, allowY = lock.axis != Axis.HORIZONTAL,
            )
            val adjusted = host.adjust(out.rect, out.snappedX, out.snappedY)
            if ((out.snappedX && !wasSnappedX) || (out.snappedY && !wasSnappedY)) host.haptic()
            wasSnappedX = out.snappedX
            wasSnappedY = out.snappedY
            val guides = ArrayList(out.guides)
            when (lock.axis) {
                Axis.HORIZONTAL -> guides += Guide.Line(vertical = false, pos = adjusted.center.y, from = 0f, to = canvas.width, kind = GuideKind.AXIS)
                Axis.VERTICAL -> guides += Guide.Line(vertical = true, pos = adjusted.center.x, from = 0f, to = canvas.height, kind = GuideKind.AXIS)
                null -> {}
            }
            state.guides = guides
            state.dragRect = adjusted
            host.onDragMove(ids, adjusted.topLeft - startUnion.topLeft)
        }

        step(pos, 0)
        var lastExtra = 0
        while (true) {
            val ev = awaitPointerEvent()
            val p = ev.changes.firstOrNull { it.id == down.id }
            if (p == null || !p.pressed) { p?.consume(); break }
            val moved = p.positionChanged() // must be read before consuming: consumed changes report no movement
            ev.changes.forEach { it.consume() }
            val extra = ev.changes.count { it.pressed && it.id != down.id }
            if (moved || extra != lastExtra) step(p.position, extra)
            lastExtra = extra
        }
        state.dragging = false
        state.guides = emptyList()
        state.dragRect = null
        host.onDragEnd(ids)
    }
}
