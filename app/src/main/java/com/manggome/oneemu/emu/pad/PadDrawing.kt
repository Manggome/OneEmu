package com.manggome.oneemu.emu.pad

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.theme.OneEmuColors

/** Colours shared by the live pad and the layout editor. */
object PadStyle {
    val outline = OneEmuColors.Accent
    val fill = Color(0x59000000)
    val pressedFill = OneEmuColors.Accent.copy(alpha = 0.6f)
    val label = Color(0xFFF2F2F2)
    val selected = Color(0xFFFFD166)
}

/** Everything needed to draw one element in a particular state. */
data class PadElementVisual(
    /** libretro mask currently pressed; used to highlight d-pad arms and buttons. */
    val pressedMask: Int = 0,
    val pressedElements: Set<PadElementId> = emptySet(),
    /** Stick thumb offset, -1..1 per axis. */
    val stickOffset: Offset = Offset.Zero,
    val selected: Boolean = false,
)

fun DrawScope.drawPadElement(
    element: PadElement,
    rect: Rect,
    system: SystemId,
    visual: PadElementVisual,
    textMeasurer: TextMeasurer,
) {
    val id = element.id
    val strokeW = (rect.width * 0.035f).coerceIn(1.5f, 4f)
    val pressed = id in visual.pressedElements || (id.mask != 0 && visual.pressedMask and id.mask == id.mask)
    when (id.kind) {
        PadElementId.Kind.DPAD -> drawDpad(rect, visual.pressedMask, strokeW)
        PadElementId.Kind.ROUND -> drawRound(rect, id.label(system), pressed, strokeW, textMeasurer)
        PadElementId.Kind.CLUSTER -> drawCluster(rect, system, visual.pressedMask, strokeW, textMeasurer)
        PadElementId.Kind.PILL -> drawPill(rect, id.label(system), pressed, strokeW, textMeasurer, 12.sp.toPx())
        PadElementId.Kind.STICK -> drawStick(rect, visual.stickOffset, pressed, strokeW)
        PadElementId.Kind.SMALL -> drawPill(rect, id.label(system), pressed, strokeW, textMeasurer, 13.sp.toPx())
    }
    if (visual.selected) {
        drawRoundRect(
            color = PadStyle.selected,
            topLeft = Offset(rect.left - strokeW * 2, rect.top - strokeW * 2),
            size = Size(rect.width + strokeW * 4, rect.height + strokeW * 4),
            cornerRadius = CornerRadius(rect.height * 0.25f),
            style = Stroke(width = strokeW, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
    }
}

private fun DrawScope.drawLabel(text: String, center: Offset, sizePx: Float, textMeasurer: TextMeasurer, bold: Boolean = true) {
    if (text.isEmpty()) return
    val style = TextStyle(color = PadStyle.label, fontSize = (sizePx / density).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    val result = textMeasurer.measure(text, style)
    drawText(result, topLeft = Offset(center.x - result.size.width / 2f, center.y - result.size.height / 2f))
}

private fun DrawScope.drawRound(rect: Rect, label: String, pressed: Boolean, strokeW: Float, textMeasurer: TextMeasurer) {
    val r = rect.width / 2f
    drawCircle(if (pressed) PadStyle.pressedFill else PadStyle.fill, r, rect.center)
    drawCircle(PadStyle.outline, r - strokeW / 2f, rect.center, style = Stroke(strokeW))
    drawLabel(label, rect.center, rect.width * 0.36f, textMeasurer)
}

private fun DrawScope.drawPill(rect: Rect, label: String, pressed: Boolean, strokeW: Float, textMeasurer: TextMeasurer, labelPx: Float) {
    val cr = CornerRadius(rect.height / 2f)
    drawRoundRect(if (pressed) PadStyle.pressedFill else PadStyle.fill, rect.topLeft, rect.size, cr)
    drawRoundRect(
        PadStyle.outline,
        Offset(rect.left + strokeW / 2f, rect.top + strokeW / 2f),
        Size(rect.width - strokeW, rect.height - strokeW),
        cr,
        style = Stroke(strokeW),
    )
    drawLabel(label, rect.center, minOf(labelPx, rect.height * 0.5f), textMeasurer, bold = false)
}

private fun DrawScope.drawDpad(rect: Rect, mask: Int, strokeW: Float) {
    val c = rect.center
    val size = rect.width
    val arm = size * 0.30f     // half-thickness of each arm
    val half = size / 2f - strokeW
    val cr = CornerRadius(arm * 0.35f)

    // Base cross.
    val cross = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(c.x - arm, c.y - half), Size(arm * 2, half * 2)), cr))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(c.x - half, c.y - arm), Size(half * 2, arm * 2)), cr))
    }
    drawPath(cross, PadStyle.fill, style = Fill)

    // Pressed arms.
    fun armRect(dir: Int): Rect = when (dir) {
        Buttons.UP -> Rect(Offset(c.x - arm, c.y - half), Size(arm * 2, half - arm))
        Buttons.DOWN -> Rect(Offset(c.x - arm, c.y + arm), Size(arm * 2, half - arm))
        Buttons.LEFT -> Rect(Offset(c.x - half, c.y - arm), Size(half - arm, arm * 2))
        else -> Rect(Offset(c.x + arm, c.y - arm), Size(half - arm, arm * 2))
    }
    for (dir in intArrayOf(Buttons.UP, Buttons.DOWN, Buttons.LEFT, Buttons.RIGHT)) {
        if (mask and dir != 0) {
            val r = armRect(dir)
            drawRoundRect(PadStyle.pressedFill, r.topLeft, r.size, cr)
        }
    }
    if (mask and (Buttons.UP or Buttons.DOWN or Buttons.LEFT or Buttons.RIGHT) != 0) {
        drawRect(PadStyle.pressedFill, Offset(c.x - arm, c.y - arm), Size(arm * 2, arm * 2))
    }
    drawPath(cross, PadStyle.outline, style = Stroke(strokeW))

    // Direction arrows.
    val tri = arm * 0.42f
    val dist = half - arm * 0.75f
    fun arrow(dx: Float, dy: Float) {
        val tip = Offset(c.x + dx * dist, c.y + dy * dist)
        val base = Offset(c.x + dx * (dist - tri), c.y + dy * (dist - tri))
        val px = -dy; val py = dx
        val p = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + px * tri * 0.8f, base.y + py * tri * 0.8f)
            lineTo(base.x - px * tri * 0.8f, base.y - py * tri * 0.8f)
            close()
        }
        drawPath(p, PadStyle.label.copy(alpha = 0.85f))
    }
    arrow(0f, -1f); arrow(0f, 1f); arrow(-1f, 0f); arrow(1f, 0f)
}

/** Four buttons in a diamond inside [rect]: X top, A right, B bottom, Y left. */
fun clusterButtonRects(rect: Rect): Map<PadElementId, Rect> {
    val d = rect.width * 0.36f
    val bw = rect.width * 0.34f
    val c = rect.center
    fun at(dx: Float, dy: Float) = Rect(Offset(c.x + dx * d - bw / 2, c.y + dy * d - bw / 2), Size(bw, bw))
    return mapOf(
        PadElementId.BUTTON_X to at(0f, -1f),
        PadElementId.BUTTON_A to at(1f, 0f),
        PadElementId.BUTTON_B to at(0f, 1f),
        PadElementId.BUTTON_Y to at(-1f, 0f),
    )
}

private fun DrawScope.drawCluster(rect: Rect, system: SystemId, mask: Int, strokeW: Float, textMeasurer: TextMeasurer) {
    for ((id, r) in clusterButtonRects(rect)) {
        drawRound(r, id.label(system), mask and id.mask != 0, strokeW, textMeasurer)
    }
}

private fun DrawScope.drawStick(rect: Rect, offset: Offset, pressed: Boolean, strokeW: Float) {
    val r = rect.width / 2f
    drawCircle(PadStyle.fill, r, rect.center)
    drawCircle(PadStyle.outline.copy(alpha = 0.8f), r - strokeW / 2f, rect.center, style = Stroke(strokeW))
    val thumbR = r * 0.45f
    val travel = r - thumbR
    val thumbCenter = Offset(rect.center.x + offset.x * travel, rect.center.y + offset.y * travel)
    drawCircle(if (pressed) PadStyle.pressedFill else PadStyle.outline.copy(alpha = 0.35f), thumbR, thumbCenter)
    drawCircle(PadStyle.outline, thumbR - strokeW / 2f, thumbCenter, style = Stroke(strokeW))
}
