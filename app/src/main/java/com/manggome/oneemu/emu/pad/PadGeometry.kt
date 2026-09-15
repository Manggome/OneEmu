package com.manggome.oneemu.emu.pad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.manggome.oneemu.emu.EmulatorSession
import com.manggome.oneemu.emu.ViewportRect
import kotlin.math.floor

/**
 * Mirrors VideoGL::present() so touch-screen coordinates line up with what the native side draws: the
 * frame is aspect-fitted inside [viewport] (normalized rect of the surface, default whole surface).
 * [aspectMode]: 0 core aspect, 1 stretch, 2 integer scale, 3 square pixels. [rotation] is the
 * libretro rotation in quarter turns (0 for every touch-screen system we ship).
 */
fun computeGameRect(
    surfaceSize: IntSize,
    geometry: EmulatorSession.Geometry,
    aspectMode: Int,
    rotation: Int = 0,
    viewport: ViewportRect = ViewportRect.FULL,
): Rect {
    val sw = surfaceSize.width.toFloat()
    val sh = surfaceSize.height.toFloat()
    if (sw <= 0f || sh <= 0f) return Rect.Zero
    val fw = geometry.width.toFloat()
    val fh = geometry.height.toFloat()
    // Same clamps as the C++ side.
    val vpX = viewport.x.coerceIn(0f, 1f)
    val vpY = viewport.y.coerceIn(0f, 1f)
    val vpW = viewport.w.coerceIn(0.05f, 1f - vpX)
    val vpH = viewport.h.coerceIn(0.05f, 1f - vpY)
    val rx = vpX * sw
    val ry = vpY * sh
    val rw = vpW * sw
    val rh = vpH * sh
    if (fw <= 0f || fh <= 0f) return Rect(rx, ry, rx + rw, ry + rh)

    var aspect = if (geometry.aspect > 0f) geometry.aspect else fw / fh
    if (aspectMode == 3) aspect = fw / fh
    val rotated = rotation % 2 == 1
    if (rotated) aspect = 1f / aspect

    var outW = rw
    var outH = rh
    if (aspectMode != 1) {
        if (outW / outH > aspect) outW = outH * aspect else outH = outW / aspect
        if (aspectMode == 2) {
            val baseH = if (rotated) fw else fh
            val scale = floor(outH / baseH).toInt()
            if (scale >= 1) { outH = baseH * scale; outW = outH * aspect }
        }
    }
    val left = (rx + (rw - outW) / 2f).toInt().toFloat()
    val top = (ry + (rh - outH) / 2f).toInt().toFloat()
    val w = outW.toInt().coerceAtLeast(1).toFloat()
    val h = outH.toInt().coerceAtLeast(1).toFloat()
    return Rect(left, top, left + w, top + h)
}

/** Pixel rectangle of a pad element for the given screen size and density. */
fun PadElement.rectOn(screen: Size, density: Float, globalScale: Float): Rect {
    val w = id.baseWidthDp * density * globalScale * scale
    val h = id.baseHeightDp * density * globalScale * scale
    val cx = (x * screen.width).coerceIn(w / 2f, (screen.width - w / 2f).coerceAtLeast(w / 2f))
    val cy = (y * screen.height).coerceIn(h / 2f, (screen.height - h / 2f).coerceAtLeast(h / 2f))
    return Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
}
