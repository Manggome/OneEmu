package com.manggome.oneemu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.emu.ViewportRect
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The "화면" element shared by both pad editors: the rectangle the native renderer letterboxes the game
 * into (see emu/Viewport.kt). It is dragged through the same EditorDragHost loop as the buttons (id =
 * [ViewportItem]) and resized with the four corner handles below.
 */

/** Id of the viewport inside an `EditorDragHost<Any>`; buttons keep their own id types. */
object ViewportItem

/** Preferences owned by the viewport editing UI. */
object ViewportPrefs {
    /** 비율 유지: corner handles scale width and height together. */
    val keepAspect = booleanPreferencesKey("layout_editor_viewport_keep_aspect")
}

enum class ViewportCorner(val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);

    fun point(r: Rect): Offset = Offset(if (right) r.right else r.left, if (bottom) r.bottom else r.top)
    val opposite: ViewportCorner get() = entries.first { it.right != right && it.bottom != bottom }
}

/** Largest rect of [aspect] (w/h) centred inside [rect]; where the actual image lands. */
fun fitAspect(rect: Rect, aspect: Float): Rect {
    if (rect.width <= 0f || rect.height <= 0f || aspect <= 0f) return rect
    var w = rect.width
    var h = rect.height
    if (w / h > aspect) w = h * aspect else h = w / aspect
    return Rect(Offset(rect.center.x - w / 2f, rect.center.y - h / 2f), Size(w, h))
}

/** Handle touch-target side in dp and the drawn knob radius. */
const val VIEWPORT_HANDLE_DP = 40f
private const val KNOB_RADIUS_DP = 7f

/**
 * Draws the viewport [rect]: dashed accent border, the letterboxed image area for [aspect] (filled dim
 * when [filled], i.e. settings mode where no real frame is behind; outline only over a live game), the
 * [label] in the top-left and corner knobs when [selected].
 */
fun DrawScope.drawViewport(rect: Rect, aspect: Float, selected: Boolean, filled: Boolean, density: Float, label: TextLayoutResult?) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val accent = OneEmuColors.Accent
    val fit = fitAspect(rect, aspect)
    if (filled) {
        drawRect(Color(0xFF2B2B2B), fit.topLeft, fit.size)
        drawRect(Color(0x66000000), rect.topLeft, rect.size)
    }
    // Image area: thin solid line so users see where the frame really sits inside the box.
    drawRect(accent.copy(alpha = if (filled) 0.35f else 0.55f), fit.topLeft, fit.size, style = Stroke(1f * density))
    val stroke = (if (selected) 2.5f else 1.5f) * density
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density))
    drawRoundRect(
        accent.copy(alpha = if (selected) 1f else 0.8f), rect.topLeft, rect.size, CornerRadius(4f * density),
        style = Stroke(width = stroke, pathEffect = dash),
    )
    if (label != null) {
        val pad = 4f * density
        val at = Offset(rect.left + pad * 2, rect.top + pad * 2)
        drawRoundRect(
            Color(0xCC1E1E1E), Offset(at.x - pad, at.y - pad / 2),
            Size(label.size.width + pad * 2, label.size.height + pad), CornerRadius(3f * density),
        )
        drawText(label, topLeft = at)
    }
    if (selected) {
        val r = KNOB_RADIUS_DP * density
        for (c in ViewportCorner.entries) {
            val p = c.point(rect)
            drawCircle(Color(0xFF1E1E1E), r, p)
            drawCircle(accent, r, p, style = Stroke(2f * density))
        }
    }
}

/** Keeps [r] inside the canvas by translation (size unchanged unless larger than the canvas). */
fun clampInside(r: Rect, canvas: Size): Rect {
    val w = min(r.width, canvas.width)
    val h = min(r.height, canvas.height)
    val left = r.left.coerceIn(0f, canvas.width - w)
    val top = r.top.coerceIn(0f, canvas.height - h)
    return Rect(Offset(left, top), Size(w, h))
}

/**
 * New viewport rect after dragging [corner] of [start] by [delta] (canvas px). The opposite corner is
 * the anchor. With [keepAspect] the rect keeps its own width:height ratio and follows whichever axis
 * moved more. Result stays inside [canvas] and at least [minSize].
 */
fun resizeViewport(start: Rect, corner: ViewportCorner, delta: Offset, keepAspect: Boolean, canvas: Size, minSize: Size): Rect {
    val anchor = corner.opposite.point(start)
    val moving = corner.point(start) + delta
    var w = abs(moving.x - anchor.x)
    var h = abs(moving.y - anchor.y)
    val maxW = if (corner.right) canvas.width - anchor.x else anchor.x
    val maxH = if (corner.bottom) canvas.height - anchor.y else anchor.y
    if (keepAspect && start.width > 0f && start.height > 0f) {
        val ratio = start.width / start.height
        val relW = abs(w - start.width) / start.width
        val relH = abs(h - start.height) / start.height
        if (relW >= relH) h = w / ratio else w = h * ratio
        // Minimum first, then shrink uniformly to fit the canvas.
        val up = max(minSize.width / w, minSize.height / h)
        if (up > 1f) { w *= up; h *= up }
        val down = min(min(maxW / w, maxH / h), 1f)
        if (down < 1f) { w *= down; h *= down }
    } else {
        w = w.coerceIn(min(minSize.width, maxW), maxW)
        h = h.coerceIn(min(minSize.height, maxH), maxH)
    }
    val left = if (corner.right) anchor.x else anchor.x - w
    val top = if (corner.bottom) anchor.y else anchor.y - h
    return Rect(Offset(left, top), Size(w, h))
}

/**
 * Four invisible 40 dp drag targets over the corners of [rect]; the knobs themselves are drawn by
 * [drawViewport]. [onDrag] receives the total delta since the drag began.
 */
@Composable
fun ViewportHandles(
    rect: Rect,
    onDragStart: () -> Unit,
    onDrag: (corner: ViewportCorner, total: Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val rectState = rememberUpdatedState(rect)
    val startState = rememberUpdatedState(onDragStart)
    val dragState = rememberUpdatedState(onDrag)
    val endState = rememberUpdatedState(onDragEnd)
    val half = VIEWPORT_HANDLE_DP / 2f
    for (corner in ViewportCorner.entries) {
        Box(
            Modifier
                .offset {
                    val d = density
                    val p = corner.point(rectState.value)
                    IntOffset((p.x - half * d).toInt(), (p.y - half * d).toInt())
                }
                .size(VIEWPORT_HANDLE_DP.dp)
                .pointerInput(corner) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero; startState.value() },
                        onDragEnd = { endState.value() },
                        onDragCancel = { endState.value() },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            dragState.value(corner, total)
                        },
                    )
                },
        )
    }
}

/** Debug-ish outline for the handle targets (unused in production; kept for tuning). */
@Suppress("unused")
private fun Modifier.handleOutline(): Modifier = border(1.dp, Color.Red, CircleShape).background(Color.Transparent)

/** Row of viewport quick actions: 비율 유지 toggle, 위쪽에 붙이기 / 가운데 / 전체 화면 / 기본값. */
@Composable
fun ViewportToolbar(
    keepAspect: Boolean,
    onKeepAspect: (Boolean) -> Unit,
    onTop: () -> Unit,
    onCenter: () -> Unit,
    onFull: () -> Unit,
    onDefault: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = keepAspect, onClick = { onKeepAspect(!keepAspect) }, label = { Text(stringResource(R.string.vp_keep_aspect)) })
        TextButton(onClick = onTop) { Text(stringResource(R.string.vp_snap_top), maxLines = 1) }
        TextButton(onClick = onCenter) { Text(stringResource(R.string.vp_center), maxLines = 1) }
        TextButton(onClick = onFull) { Text(stringResource(R.string.vp_full), maxLines = 1) }
        TextButton(onClick = onDefault) { Text(stringResource(R.string.vp_default), maxLines = 1) }
    }
}

/** Quick placements on the unit square. */
object ViewportQuick {
    fun top(v: ViewportRect): ViewportRect = v.copy(y = 0f).normalized()
    fun center(v: ViewportRect): ViewportRect = v.copy(x = (1f - v.w) / 2f, y = (1f - v.h) / 2f).normalized()
    fun full(): ViewportRect = ViewportRect.FULL
}

/**
 * Chips naming the screen configuration being edited. With [onSelect] every configuration is offered
 * (settings mode); without it only [current] is shown as a static label (in-game mode).
 */
@Composable
fun ScreenConfigSelector(current: ScreenConfig, onSelect: ((ScreenConfig) -> Unit)?) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = if (onSelect == null) listOf(current) else ScreenConfig.entries
        for (c in shown) {
            FilterChip(
                selected = c == current,
                onClick = { onSelect?.invoke(c) },
                enabled = onSelect != null || c == current,
                label = { Text(stringResource(c.labelRes), maxLines = 1) },
            )
        }
    }
}
