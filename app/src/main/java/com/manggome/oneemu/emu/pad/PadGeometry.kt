package com.manggome.oneemu.emu.pad

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
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

/**
 * Screen edges a pad control must stay clear of, in pixels.
 *
 * A button under the navigation bar cannot be pressed: the bar is on top of it and takes the touch.
 * The layout editor opens in the main activity, which shows the bars, so a START or SELECT saved near
 * the bottom edge is unreachable there even though the same layout works in-game, where the bars are
 * hidden. Positions are stored as fractions of the screen and stay that way - the nudge happens when
 * the rectangle is worked out, so a layout keeps working on a device with different bars.
 */
data class PadInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    companion object { val NONE = PadInsets() }
}

/** Pixel rectangle of a pad element for the given screen size and density, kept out of [insets]. */
fun PadElement.rectOn(screen: Size, density: Float, globalScale: Float, insets: PadInsets = PadInsets.NONE): Rect {
    val w = id.baseWidthDp * density * globalScale * scale
    val h = id.baseHeightDp * density * globalScale * scale
    // A control wider than the room left over is centred in it rather than pushed off the other side.
    fun clamp(center: Float, size: Float, start: Float, end: Float): Float {
        val lo = start + size / 2f
        val hi = end - size / 2f
        return if (hi < lo) (start + end) / 2f else center.coerceIn(lo, hi)
    }
    val cx = clamp(x * screen.width, w, insets.left, screen.width - insets.right)
    val cy = clamp(y * screen.height, h, insets.top, screen.height - insets.bottom)
    return Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
}

/**
 * The bars in the way right now. In game these are hidden and this is empty, so nothing moves; the
 * layout editor reached from 설정 runs in the main activity, where the navigation bar is real.
 */
@Composable
fun rememberPadInsets(): PadInsets {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    return PadInsets(
        left = bars.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat(),
        top = bars.getTop(density).toFloat(),
        right = bars.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat(),
        bottom = bars.getBottom(density).toFloat(),
    )
}
