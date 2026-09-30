package com.manggome.oneemu.emu.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the game picture and the handset keys go on a screen of the given size. */
data class PhoneLayout(val game: Rect, val keys: List<PlacedKey>, val menuAt: Offset, val speedAt: Offset, val overlay: Boolean)

/**
 * Lays the handset out on a [w] x [h] pixel screen.
 *
 * - PHONE: the bar phone - picture on top, a thin row with the menu/speed buttons, the keypad at the bottom at
 *   [WipiPrefs.State.keypadScale] of the width.
 * - TWO_HAND: picture in the middle, d-pad and soft/통화 keys left, number grid and 취소/soft key right.
 * - OVERLAY: picture as large as it gets, the keys drawn over it (translucent, still touchable).
 *
 * [WipiPrefs.State.screenScale] shrinks the picture's area around its centre (top for PHONE).
 */
fun layoutPhone(
    w: Float,
    h: Float,
    dp: Float,
    state: WipiPrefs.State,
    landscape: Boolean,
    keypadVisible: Boolean,
    /** Game picture width / height (240x320 -> 0.75). The picture keeps it exactly; nothing is ever stretched. */
    gameAspect: Float = 0.75f,
): PhoneLayout {
    val arrangement = state.arrangement(landscape)
    val margin = 8f * dp
    val bar = 36f * dp
    val ss = state.screenScale

    // The picture: as large as fits [area] at the game's own aspect, times the screen-size setting, centred
    // horizontally (and vertically unless topAnchored).
    val aspect = if (gameAspect > 0.1f && gameAspect < 10f) gameAspect else 0.75f
    fun shrink(area: Rect, topAnchored: Boolean): Rect {
        var nw = area.width
        var nh = nw / aspect
        if (nh > area.height) {
            nh = area.height
            nw = nh * aspect
        }
        nw *= ss
        nh *= ss
        val left = area.left + (area.width - nw) / 2f
        val top = if (topAnchored) area.top else area.top + (area.height - nh) / 2f
        return Rect(left, top, left + nw, top + nh)
    }

    if (!keypadVisible) {
        val game = shrink(Rect(0f, 0f, w, h), topAnchored = !landscape)
        return PhoneLayout(game, emptyList(), Offset(margin, margin), Offset(w - margin - 40f * dp, margin), overlay = false)
    }

    val twoSided = arrangement == WipiPrefs.Arrangement.TWO_HAND || (arrangement == WipiPrefs.Arrangement.OVERLAY && landscape)
    if (twoSided) {
        val left = PhoneBlocks.leftHand()
        val right = PhoneBlocks.rightHand()
        val panel = min(w * 0.34f, max(w * 0.24f, (w - h * 0.75f) / 2f))
        val sl = min((panel - margin) / left.width, (h - 2 * margin - bar) / left.height) * state.keypadScale
        val sr = min((panel - margin) / right.width, (h - 2 * margin - bar) / right.height) * state.keypadScale
        val lo = Offset((panel - left.width * sl) / 2f, h - margin - left.height * sl)
        val ro = Offset(w - panel + (panel - right.width * sr) / 2f, h - margin - right.height * sr)
        val overlay = arrangement == WipiPrefs.Arrangement.OVERLAY
        val area = if (overlay) Rect(0f, 0f, w, h) else Rect(panel, 0f, w - panel, h)
        return PhoneLayout(
            game = shrink(area, topAnchored = false),
            keys = left.place(lo, sl) + right.place(ro, sr),
            menuAt = Offset(margin, margin),
            speedAt = Offset(w - margin - 40f * dp, margin),
            overlay = overlay,
        )
    }

    // Portrait bar phone (also the portrait overlay).
    val block = PhoneBlocks.phone(state.foldDpad)
    // Full width at 100%, but never more than about half the screen's height: on wide screens (foldable covers,
    // tablets) a width-filling keypad would leave the game a strip.
    val s = min((w - 2 * margin) / block.width, h * 0.46f / block.height) * state.keypadScale
    val kh = block.height * s
    val origin = Offset((w - block.width * s) / 2f, h - margin - kh)
    val overlay = arrangement == WipiPrefs.Arrangement.OVERLAY
    val barTop = origin.y - bar
    val area = if (overlay) Rect(0f, 0f, w, h) else Rect(0f, 0f, w, max(barTop, h * 0.3f))
    return PhoneLayout(
        game = shrink(area, topAnchored = !overlay),
        keys = block.place(origin, s),
        menuAt = Offset(margin + 4f * dp, barTop + (bar - 30f * dp) / 2f),
        speedAt = Offset(w - margin - 44f * dp, barTop + (bar - 30f * dp) / 2f),
        overlay = overlay,
    )
}

/**
 * The feature-phone play screen drawn over the game surface: sets the game viewport and draws the handset keys,
 * plus the ☰ (pause menu) and ▶▶ (fast-forward) buttons.
 */
@Composable
fun PhoneScreen(
    state: WipiPrefs.State,
    landscape: Boolean,
    keypadVisible: Boolean,
    fastForward: Boolean,
    gameAspect: Float,
    onBits: (Int) -> Unit,
    onTick: () -> Unit,
    onViewport: (x: Float, y: Float, w: Float, h: Float) -> Unit,
    onMenu: () -> Unit,
    onFastForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dp = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val layout = remember(w, h, state, landscape, keypadVisible, gameAspect) { layoutPhone(w, h, dp, state, landscape, keypadVisible, gameAspect) }

        LaunchedEffect(layout.game, w, h) {
            if (w > 0f && h > 0f) onViewport(layout.game.left / w, layout.game.top / h, layout.game.width / w, layout.game.height / h)
        }

        if (layout.keys.isNotEmpty()) {
            PhoneKeypad(
                keys = layout.keys,
                design = state.design,
                opacity = if (layout.overlay) state.overlayOpacity.coerceAtLeast(0.02f) else 1f,
                onBits = onBits,
                onTick = onTick,
            )
        }

        SmallButton(layout.menuAt, active = false, onClick = onMenu) { c -> drawMenuGlyph(c) }
        SmallButton(layout.speedAt, active = fastForward, onClick = onFastForward) { c -> drawFastForwardGlyph(c) }
    }
}

/** Drawn glyphs: text arrows and ☰ render as colour emoji on many phones. */
@Composable
private fun SmallButton(at: Offset, active: Boolean, onClick: () -> Unit, glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val ink = if (active) Color(0xFF111111) else Color(0xFFE6E6E6)
    Box(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(40.dp, 30.dp)
            .background(if (active) Color(0xCC7FD1C8) else Color(0x99222428), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0x665A5E66), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp, 14.dp)) { glyph(ink) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMenuGlyph(c: Color) {
    val t = size.height * 0.14f
    for (i in 0..2) {
        val y = size.height * (0.15f + i * 0.35f)
        drawRect(c, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, t))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFastForwardGlyph(c: Color) {
    val w = size.width / 2f
    for (i in 0..1) {
        val x0 = i * w
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(x0, 0f); lineTo(x0 + w, size.height / 2f); lineTo(x0, size.height); close()
            },
            c,
        )
    }
}
