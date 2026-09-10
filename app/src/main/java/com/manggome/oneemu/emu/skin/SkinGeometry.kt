package com.manggome.oneemu.emu.skin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A desc laid out in pixels for the current screen. */
class PlacedDesc(
    val desc: OverlayDesc,
    val cx: Float,
    val cy: Float,
    /** Half extents in pixels (already include user/global scale). */
    val rx: Float,
    val ry: Float,
    val visible: Boolean,
) {
    val bounds: Rect get() = Rect(cx - rx, cy - ry, cx + rx, cy + ry)

    /** Hit test with RetroArch reach semantics; [scale] is the range_mod growth while held. */
    fun contains(px: Float, py: Float, scale: Float = 1f): Boolean {
        val dx = px - cx
        val dy = py - cy
        val hx = rx * scale * (if (dx < 0f) desc.reachLeft else desc.reachRight)
        val hy = ry * scale * (if (dy < 0f) desc.reachUp else desc.reachDown)
        if (hx <= 0f || hy <= 0f) return false
        return if (desc.shape == HitShape.RECT) abs(dx) <= hx && abs(dy) <= hy
        else (dx / hx) * (dx / hx) + (dy / hy) * (dy / hy) <= 1f
    }
}

/** Where the overlay's design box landed on screen (background image target). */
class OverlayFrame(val box: Rect, val screen: Size)

/**
 * Places every desc of [overlay] on a [screen] the way RetroArch's auto-scale does: the overlay's design
 * aspect box is centred and, unless blocked, the two halves are pushed apart to fill the leftover axis.
 * [layout] applies the user's per-desc offsets/scales; [globalScale] is the pad-size setting.
 */
fun placeOverlay(overlay: Overlay, screen: Size, landscape: Boolean, layout: SkinLayout, globalScale: Float): Pair<OverlayFrame, List<PlacedDesc>> {
    val w = screen.width
    val h = screen.height
    if (w <= 0f || h <= 0f) return OverlayFrame(Rect.Zero, screen) to emptyList()
    val design = overlay.designAspect(landscape)
    var boxW = w
    var boxH = h
    var sepX = 0f
    var sepY = 0f
    if (w / h > design) {
        boxW = h * design
        sepX = (w - boxW) / 2f
    } else {
        boxH = w / design
        sepY = (h - boxH) / 2f
    }
    val offX = (w - boxW) / 2f
    val offY = (h - boxH) / 2f
    val shiftX = if (overlay.blockXSeparation) 0f else sepX
    val shiftY = if (overlay.blockYSeparation) 0f else sepY

    val placed = ArrayList<PlacedDesc>(overlay.descs.size)
    for (d in overlay.descs) {
        val edit = layout[overlay, d]
        val s = globalScale * (edit?.scale ?: 1f)
        var cx = offX + d.x * boxW + when { d.x < 0.5f -> -shiftX; d.x > 0.5f -> shiftX; else -> 0f }
        var cy = offY + d.y * boxH + when { d.y < 0.5f -> -shiftY; d.y > 0.5f -> shiftY; else -> 0f }
        if (edit != null) { cx += edit.dx * w; cy += edit.dy * h }
        placed += PlacedDesc(d, cx, cy, d.rx * boxW * s, d.ry * boxH * s, edit?.visible ?: true)
    }
    return OverlayFrame(Rect(offX, offY, offX + boxW, offY + boxH), screen) to placed
}

/** 8-way direction from a finger inside a dpad area (elliptical normalisation, 45° diagonal zones). */
fun areaDpadMask(px: Float, py: Float, p: PlacedDesc): Int {
    val dx = (px - p.cx) / p.rx
    val dy = (py - p.cy) / p.ry
    if (hypot(dx, dy) < 0.12f) return 0
    val ax = abs(dx)
    val ay = abs(dy)
    var m = 0
    if (ax > ay * 0.414f) m = m or (if (dx < 0) Buttons.LEFT else Buttons.RIGHT)
    if (ay > ax * 0.414f) m = m or (if (dy < 0) Buttons.UP else Buttons.DOWN)
    return m
}

/** ABXY diamond area: X top, A right, B bottom, Y left, with the between-button sectors pressing two. */
fun areaAbxyMask(px: Float, py: Float, p: PlacedDesc): Int {
    val dx = (px - p.cx) / p.rx
    val dy = (py - p.cy) / p.ry
    if (hypot(dx, dy) < 0.12f) return 0
    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
    return when (((deg + 22.5) / 45.0).toInt() % 8) {
        0 -> Buttons.A
        1 -> Buttons.A or Buttons.B
        2 -> Buttons.B
        3 -> Buttons.B or Buttons.Y
        4 -> Buttons.Y
        5 -> Buttons.Y or Buttons.X
        6 -> Buttons.X
        else -> Buttons.X or Buttons.A
    }
}

/** Stick deflection (-1..1 per axis, unit-circle clamped) for a finger at [px],[py]. */
fun analogValue(px: Float, py: Float, p: PlacedDesc): Offset {
    val travelX = p.rx * p.desc.saturatePct
    val travelY = p.ry * p.desc.saturatePct
    if (travelX <= 0f || travelY <= 0f) return Offset.Zero
    var dx = (px - p.cx) / travelX
    var dy = (py - p.cy) / travelY
    val len = hypot(dx, dy)
    if (len > 1f) { dx /= len; dy /= len }
    return Offset(dx, dy)
}

/**
 * Groups descs whose bounds overlap into clusters (d-pad arms + area + diagonals, ABXY diamond, stick +
 * its background) so the editor can drag them together. Returns one list of desc indices per group.
 */
fun groupPlaced(placed: List<PlacedDesc>): List<List<Int>> {
    val n = placed.size
    val parent = IntArray(n) { it }
    fun find(i: Int): Int { var a = i; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
    fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
    val boxes = placed.map { shrink(it.bounds, 0.85f) }
    for (i in 0 until n) {
        if (!placed[i].desc.drawable && !placed[i].desc.interactive) continue
        for (j in i + 1 until n) {
            if (!placed[j].desc.drawable && !placed[j].desc.interactive) continue
            if (boxes[i].overlaps(boxes[j])) union(i, j)
        }
    }
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) {
        if (!placed[i].desc.drawable && !placed[i].desc.interactive) continue
        groups.getOrPut(find(i)) { ArrayList() }.add(i)
    }
    return groups.values.toList()
}

private fun shrink(r: Rect, f: Float): Rect {
    val dx = r.width * (1f - f) / 2f
    val dy = r.height * (1f - f) / 2f
    return Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
}
