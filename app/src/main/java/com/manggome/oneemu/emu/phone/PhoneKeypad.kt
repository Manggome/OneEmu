package com.manggome.oneemu.emu.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.emu.pad.PhoneKeys

/** Every key a WIPI handset has, with its face legend and the 천지인 / alphabet line printed under digits. */
enum class PhoneKey(val bit: Int, val label: String, val sub: String = "") {
    K1(PhoneKeys.D1, "1", "ㅣ"),
    K2(PhoneKeys.D2, "2", "·  ABC"),
    K3(PhoneKeys.D3, "3", "ㅡ  DEF"),
    K4(PhoneKeys.D4, "4", "ㄱㅋ GHI"),
    K5(PhoneKeys.D5, "5", "ㄴㄹ JKL"),
    K6(PhoneKeys.D6, "6", "ㄷㅌ MNO"),
    K7(PhoneKeys.D7, "7", "ㅂㅍ PQRS"),
    K8(PhoneKeys.D8, "8", "ㅅㅎ TUV"),
    K9(PhoneKeys.D9, "9", "ㅈㅊ WXYZ"),
    STAR(PhoneKeys.STAR, "*"),
    K0(PhoneKeys.D0, "0", "ㅇㅁ"),
    HASH(PhoneKeys.HASH, "#"),
    LSK(PhoneKeys.LSK, "좌메뉴"),
    RSK(PhoneKeys.RSK, "우메뉴"),
    CALL(PhoneKeys.CALL, "통화"),
    CLR(PhoneKeys.CLR, "취소"),
    END(PhoneKeys.END, "종료"),
    OK(PhoneKeys.OK, "OK"),
    UP(PhoneKeys.UP, "▲"),
    DOWN(PhoneKeys.DOWN, "▼"),
    LEFT(PhoneKeys.LEFT, "◀"),
    RIGHT(PhoneKeys.RIGHT, "▶");

    val isDigitGrid: Boolean get() = bit and PhoneKeys.GRID != 0
    val isArrow: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

    companion object {
        val GRID = listOf(K1, K2, K3, K4, K5, K6, K7, K8, K9, STAR, K0, HASH)

        /** Every key with the bit it presses, for pickers (gamepad mapping). */
        val PICKABLE = listOf(UP, DOWN, LEFT, RIGHT, OK, CLR, LSK, RSK, CALL, END) + GRID

        fun ofBit(bit: Int): PhoneKey? = entries.firstOrNull { it.bit == bit }
    }
}

/** One key placed on the keypad canvas, in canvas pixels. */
data class PlacedKey(val key: PhoneKey, val rect: Rect)

/** A block of keys laid out in design units (dp-like), placed later at a scale and offset. */
class KeyBlock(val width: Float, val height: Float, val keys: List<Pair<PhoneKey, Rect>>) {
    fun place(origin: Offset, scale: Float): List<PlacedKey> = keys.map { (k, r) ->
        PlacedKey(k, Rect(origin.x + r.left * scale, origin.y + r.top * scale, origin.x + r.right * scale, origin.y + r.bottom * scale))
    }
}

/**
 * The handset layouts, in design units. The portrait phone block follows the classic bar phone: 좌메뉴 over 통화 on
 * the left, the d-pad with OK in the middle, 우메뉴 over 취소 on the right, then the 3x4 number grid.
 */
object PhoneBlocks {
    private fun r(l: Float, t: Float, w: Float, h: Float) = Rect(l, t, l + w, t + h)

    private fun dpad(cx: Float, cy: Float, cell: Float): List<Pair<PhoneKey, Rect>> {
        val h = cell / 2f
        return listOf(
            PhoneKey.UP to Rect(cx - h, cy - h - cell, cx + h, cy - h),
            PhoneKey.LEFT to Rect(cx - h - cell, cy - h, cx - h, cy + h),
            PhoneKey.OK to Rect(cx - h, cy - h, cx + h, cy + h),
            PhoneKey.RIGHT to Rect(cx + h, cy - h, cx + h + cell, cy + h),
            PhoneKey.DOWN to Rect(cx - h, cy + h, cx + h, cy + h + cell),
        )
    }

    private fun grid(left: Float, top: Float, width: Float, rowH: Float, gap: Float): List<Pair<PhoneKey, Rect>> {
        val colW = (width - 2 * gap) / 3f
        return PhoneKey.GRID.mapIndexed { i, k ->
            val c = i % 3
            val row = i / 3
            k to r(left + c * (colW + gap), top + row * (rowH + gap), colW, rowH)
        }
    }

    /** Portrait bar phone. [fold] swaps the d-pad for a single row of the four function keys. */
    fun phone(fold: Boolean): KeyBlock {
        val w = 360f
        val keys = mutableListOf<Pair<PhoneKey, Rect>>()
        val gridTop: Float
        if (fold) {
            val fw = (w - 20f - 3 * 8f) / 4f
            listOf(PhoneKey.LSK, PhoneKey.CALL, PhoneKey.CLR, PhoneKey.RSK).forEachIndexed { i, k -> keys += k to r(10f + i * (fw + 8f), 6f, fw, 40f) }
            gridTop = 56f
        } else {
            keys += PhoneKey.LSK to r(10f, 30f, 96f, 42f)
            keys += PhoneKey.CALL to r(10f, 78f, 96f, 42f)
            keys += dpad(180f, 74f, 44f)
            keys += PhoneKey.RSK to r(254f, 30f, 96f, 42f)
            keys += PhoneKey.CLR to r(254f, 78f, 96f, 42f)
            gridTop = 150f
        }
        keys += grid(10f, gridTop, w - 20f, 46f, 7f)
        return KeyBlock(w, gridTop + 4 * 46f + 3 * 7f + 8f, keys)
    }

    /** Landscape, left hand: soft key and 통화 on top, the d-pad below. */
    fun leftHand(): KeyBlock {
        val keys = mutableListOf<Pair<PhoneKey, Rect>>()
        keys += PhoneKey.LSK to r(8f, 8f, 88f, 42f)
        keys += PhoneKey.CALL to r(104f, 8f, 88f, 42f)
        keys += dpad(100f, 170f, 58f)
        return KeyBlock(200f, 270f, keys)
    }

    /** Landscape, right hand: 취소 and the other soft key on top, the number grid below. */
    fun rightHand(): KeyBlock {
        val keys = mutableListOf<Pair<PhoneKey, Rect>>()
        keys += PhoneKey.CLR to r(8f, 8f, 88f, 42f)
        keys += PhoneKey.RSK to r(104f, 8f, 88f, 42f)
        keys += grid(8f, 60f, 184f, 46f, 6f)
        return KeyBlock(200f, 60f + 4 * 46f + 3 * 6f + 8f, keys)
    }
}

private object KeyColors {
    val classicTop = Color(0xFF3C3F45)
    val classicBottom = Color(0xFF1C1E21)
    val classicEdge = Color(0xFF6A6F78)
    val classicPressed = Color(0xFF7A808A)
    val flatFill = Color(0xFF2E3136)
    val flatPressed = Color(0xFF7FD1C8)
    val label = Color(0xFFF2F2F2)
    val sub = Color(0xFFB0B4BA)
    val call = Color(0xFF6BD68A)
    val end = Color(0xFFE06464)
}

/**
 * Draws [keys] and reports the pressed set as [PhoneKeys] bits. Every finger is tracked on its own and may slide
 * from key to key; [onPressed] fires only when the set changes, with a haptic tick for newly pressed keys.
 */
@Composable
fun PhoneKeypad(
    keys: List<PlacedKey>,
    design: WipiPrefs.Design,
    opacity: Float,
    onBits: (Int) -> Unit,
    onTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val keysState = rememberUpdatedState(keys)
    val onBitsState = rememberUpdatedState(onBits)
    val onTickState = rememberUpdatedState(onTick)
    var pressed by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val fingers = HashMap<PointerId, PhoneKey?>()
                var last = 0
                fun hit(p: Offset): PhoneKey? {
                    val ks = keysState.value
                    ks.firstOrNull { it.rect.contains(p) }?.let { return it.key }
                    // A little slop around keys so edge taps land.
                    return ks.firstOrNull { k ->
                        val dx = k.rect.width * 0.12f
                        val dy = k.rect.height * 0.12f
                        Rect(k.rect.left - dx, k.rect.top - dy, k.rect.right + dx, k.rect.bottom + dy).contains(p)
                    }?.key
                }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            when {
                                c.changedToDownIgnoreConsumed() -> fingers[c.id] = hit(c.position)
                                c.changedToUpIgnoreConsumed() -> fingers.remove(c.id)
                                c.pressed -> fingers[c.id] = hit(c.position)
                            }
                            c.consume()
                        }
                        val bits = fingers.values.fold(0) { acc, k -> acc or (k?.bit ?: 0) }
                        if (bits != last) {
                            if (bits and last.inv() != 0) onTickState.value()
                            last = bits
                            pressed = bits
                            onBitsState.value(bits)
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.coerceIn(0f, 1f) }) {
            for (k in keys) drawKey(k, design, pressed and k.key.bit != 0, measurer)
        }
    }
}

private fun DrawScope.drawKey(k: PlacedKey, design: WipiPrefs.Design, down: Boolean, measurer: TextMeasurer) {
    val r = k.rect
    val corner = CornerRadius(minOf(r.width, r.height) * 0.16f)
    val topLeft = Offset(r.left, r.top)
    val size = Size(r.width, r.height)
    when (design) {
        WipiPrefs.Design.CLASSIC -> {
            val brush = if (down) Brush.verticalGradient(listOf(KeyColors.classicPressed, KeyColors.classicTop), r.top, r.bottom)
            else Brush.verticalGradient(listOf(KeyColors.classicTop, KeyColors.classicBottom), r.top, r.bottom)
            drawRoundRect(brush, topLeft, size, corner)
            drawRoundRect(KeyColors.classicEdge, topLeft, size, corner, style = Stroke(width = maxOf(1.5f, r.height * 0.03f)))
            // Top highlight, the bevel of a real key.
            drawLine(Color.White.copy(alpha = 0.18f), Offset(r.left + corner.x, r.top + 2f), Offset(r.right - corner.x, r.top + 2f), strokeWidth = 1.5f)
        }
        WipiPrefs.Design.FLAT -> {
            drawRoundRect(if (down) KeyColors.flatPressed.copy(alpha = 0.7f) else KeyColors.flatFill, topLeft, size, corner)
        }
    }

    val key = k.key
    val color = when (key) {
        PhoneKey.CALL -> KeyColors.call
        PhoneKey.END -> KeyColors.end
        else -> KeyColors.label
    }
    val base = minOf(r.height, r.width)
    if (key.sub.isNotEmpty()) {
        val main = measurer.measure(key.label, TextStyle(color = color, fontSize = (base * 0.40f / density).sp, fontWeight = FontWeight.Bold))
        val sub = measurer.measure(key.sub, TextStyle(color = KeyColors.sub, fontSize = (base * 0.20f / density).sp))
        val total = main.size.height + sub.size.height * 0.85f
        val y = r.center.y - total / 2f
        drawText(main, topLeft = Offset(r.center.x - main.size.width / 2f, y))
        drawText(sub, topLeft = Offset(r.center.x - sub.size.width / 2f, y + main.size.height * 0.92f))
    } else {
        val factor = when {
            key.isArrow -> 0.42f
            key.label.length >= 3 -> 0.34f
            key == PhoneKey.OK -> 0.36f
            else -> 0.46f
        }
        val text = measurer.measure(key.label, TextStyle(color = color, fontSize = (base * factor / density).sp, fontWeight = FontWeight.Bold))
        drawText(text, topLeft = Offset(r.center.x - text.size.width / 2f, r.center.y - text.size.height / 2f))
    }
}
