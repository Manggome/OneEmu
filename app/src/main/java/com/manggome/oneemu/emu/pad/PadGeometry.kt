package com.manggome.oneemu.emu.pad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.manggome.oneemu.emu.EmulatorSession
import kotlin.math.floor

/**
 * Mirrors VideoGL::present() so touch-screen coordinates line up with what the native side draws.
 * [aspectMode]: 0 core aspect, 1 stretch, 2 integer scale, 3 square pixels. [rotation] is the
 * libretro rotation in quarter turns (0 for every touch-screen system we ship).
 */
fun computeGameRect(surfaceSize: IntSize, geometry: EmulatorSession.Geometry, aspectMode: Int, rotation: Int = 0): Rect {
    val sw = surfaceSize.width.toFloat()
    val sh = surfaceSize.height.toFloat()
    if (sw <= 0f || sh <= 0f) return Rect.Zero
    val fw = geometry.width.toFloat()
    val fh = geometry.height.toFloat()
    if (fw <= 0f || fh <= 0f) return Rect(0f, 0f, sw, sh)

    var aspect = if (geometry.aspect > 0f) geometry.aspect else fw / fh
    if (aspectMode == 3) aspect = fw / fh
    val rotated = rotation % 2 == 1
    if (rotated) aspect = 1f / aspect

    var outW = sw
    var outH = sh
    if (aspectMode != 1) {
        if (outW / outH > aspect) outW = outH * aspect else outH = outW / aspect
        if (aspectMode == 2) {
            val baseH = if (rotated) fw else fh
            val scale = floor(outH / baseH).toInt()
            if (scale >= 1) { outH = baseH * scale; outW = outH * aspect }
        }
    }
    val left = ((sw - outW) / 2f).toInt().toFloat()
    val top = ((sh - outH) / 2f).toInt().toFloat()
    return Rect(left, top, left + outW.toInt(), top + outH.toInt())
}

/** Pixel rectangle of a pad element for the given screen size and density. */
fun PadElement.rectOn(screen: Size, density: Float, globalScale: Float): Rect {
    val w = id.baseWidthDp * density * globalScale * scale
    val h = id.baseHeightDp * density * globalScale * scale
    val cx = (x * screen.width).coerceIn(w / 2f, (screen.width - w / 2f).coerceAtLeast(w / 2f))
    val cy = (y * screen.height).coerceIn(h / 2f, (screen.height - h / 2f).coerceAtLeast(h / 2f))
    return Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
}
