package com.manggome.oneemu.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.GameThumbnail

/**
 * 게임팩 view: each game drawn as the thing it came on - a Famicom or Mega Drive cartridge, a Game Boy
 * or GBA cart, a DS/3DS card, a UMD, a disc, an arcade board - with its box art as the label. Shapes
 * are simple vector outlines in the system's colour; nothing is taken from anyone's artwork.
 */
enum class PackShape(
    /** Width / height of the shell. */
    val aspect: Float,
    /** Where the label sits, as fractions of the shell (left, top, width, height). */
    val label: FloatArray,
) {
    FAMICOM(0.86f, floatArrayOf(0.10f, 0.08f, 0.80f, 0.56f)),
    GAMEBOY(0.88f, floatArrayOf(0.12f, 0.20f, 0.76f, 0.56f)),
    GBA(1.72f, floatArrayOf(0.14f, 0.18f, 0.72f, 0.56f)),
    DS_CARD(0.92f, floatArrayOf(0.12f, 0.16f, 0.76f, 0.58f)),
    MEGA_DRIVE(0.78f, floatArrayOf(0.10f, 0.16f, 0.80f, 0.60f)),
    SEGA_CARD(0.80f, floatArrayOf(0.12f, 0.12f, 0.76f, 0.62f)),
    UMD(0.92f, floatArrayOf(0.20f, 0.14f, 0.60f, 0.62f)),
    DISC(1f, floatArrayOf(0f, 0f, 1f, 1f)),
    MINI_DISC(1f, floatArrayOf(0f, 0f, 1f, 1f)),
    ARCADE(1.2f, floatArrayOf(0.08f, 0.10f, 0.84f, 0.62f)),
    PC_BOX(0.78f, floatArrayOf(0.12f, 0.10f, 0.76f, 0.70f));

    companion object {
        fun of(system: SystemId?): PackShape = when (system) {
            SystemId.NES -> FAMICOM
            SystemId.GB, SystemId.GBC -> GAMEBOY
            SystemId.GBA -> GBA
            SystemId.NDS, SystemId.N3DS -> DS_CARD
            SystemId.MD -> MEGA_DRIVE
            SystemId.SMS, SystemId.GG -> SEGA_CARD
            SystemId.PSP -> UMD
            SystemId.PSX, SystemId.PS2 -> DISC
            SystemId.GC -> MINI_DISC
            SystemId.ARCADE -> ARCADE
            else -> PC_BOX
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GamePackItem(
    game: GameEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val system = SystemId.fromId(game.system)
    val shape = PackShape.of(system)
    val tint = system?.color ?: Color(0xFF888888)
    Column(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Every cell is square so rows line up; the shell sits in it at its own proportions.
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(if (shape.aspect >= 1f) 1f else shape.aspect).aspectRatio(shape.aspect)) {
                PackShell(game, shape, tint)
            }
        }
        Text(
            game.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 2.dp),
        )
    }
}

@Composable
private fun PackShell(game: GameEntity, shape: PackShape, tint: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(shape.aspect)) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.matchParentSize()) { drawShell(shape, tint) }
        val l = shape.label
        when (shape) {
            PackShape.DISC, PackShape.MINI_DISC -> {
                // The art is printed on the disc itself, with the hub hole on top of it.
                val d = if (shape == PackShape.MINI_DISC) 0.86f else 1f
                Box(Modifier.align(Alignment.Center).size(w * d)) {
                    GameThumbnail(game, Modifier.matchParentSize(), shape = CircleShape, contentScale = ContentScale.Crop, titleSize = 18.sp, badgeSize = 14.dp)
                    Canvas(Modifier.matchParentSize()) { drawDiscHub() }
                }
            }
            else -> Box(
                Modifier
                    .offset(w * l[0], h * l[1])
                    .size(w * l[2], h * l[3]),
            ) {
                GameThumbnail(game, Modifier.matchParentSize(), shape = RoundedCornerShape(labelCorner(w)), contentScale = ContentScale.Crop, titleSize = 16.sp, badgeSize = 14.dp)
            }
        }
    }
}

private fun labelCorner(w: Dp): Dp = (w.value * 0.04f).coerceIn(2f, 6f).dp

private fun shellColor(tint: Color, shape: PackShape): Color = when (shape) {
    // The machines' own plastics: grey Game Boy and Famicom-era carts, black Sega carts, a clear PSP shell.
    PackShape.GAMEBOY -> Color(0xFFB8BCC4)
    PackShape.MEGA_DRIVE, PackShape.SEGA_CARD -> Color(0xFF26282C)
    PackShape.UMD -> Color(0xFFD9DEE6)
    PackShape.ARCADE -> Color(0xFF1F5F3A)
    else -> lerp(tint, Color(0xFF3A3A3A), 0.35f)
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

private fun DrawScope.drawShell(shape: PackShape, tint: Color) {
    val w = size.width
    val h = size.height
    val body = shellColor(tint, shape)
    val shade = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.18f)))
    val edge = Color.Black.copy(alpha = 0.35f)
    val r = w * 0.05f
    when (shape) {
        PackShape.FAMICOM -> {
            // Short, wide-shouldered cart with ridges along the bottom.
            drawRoundRect(body, cornerRadius = CornerRadius(r))
            drawRoundRect(shade, cornerRadius = CornerRadius(r))
            for (i in 0 until 5) {
                val y = h * (0.72f + i * 0.05f)
                drawLine(edge, Offset(w * 0.12f, y), Offset(w * 0.88f, y), strokeWidth = w * 0.012f)
            }
        }
        PackShape.GAMEBOY -> {
            // The Game Boy cart: one corner cut, a grip notch at the top.
            val p = Path().apply {
                moveTo(0f, r); quadraticTo(0f, 0f, r, 0f)
                lineTo(w * 0.86f, 0f); lineTo(w, h * 0.10f)
                lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                lineTo(r, h); quadraticTo(0f, h, 0f, h - r); close()
            }
            drawPath(p, body)
            drawPath(p, shade)
            drawLine(edge, Offset(w * 0.18f, h * 0.08f), Offset(w * 0.70f, h * 0.08f), strokeWidth = w * 0.02f)
            drawRect(tint.copy(alpha = 0.9f), Offset(w * 0.12f, h * 0.80f), Size(w * 0.76f, h * 0.06f))
        }
        PackShape.GBA -> {
            drawRoundRect(body, cornerRadius = CornerRadius(h * 0.10f))
            drawRoundRect(shade, cornerRadius = CornerRadius(h * 0.10f))
            drawRoundRect(edge, Offset(w * 0.40f, 0f), Size(w * 0.20f, h * 0.10f), CornerRadius(h * 0.05f))
        }
        PackShape.DS_CARD -> {
            val p = Path().apply {
                moveTo(0f, r); quadraticTo(0f, 0f, r, 0f)
                lineTo(w * 0.80f, 0f); lineTo(w, h * 0.12f)
                lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                lineTo(r, h); quadraticTo(0f, h, 0f, h - r); close()
            }
            drawPath(p, body)
            drawPath(p, shade)
            for (i in 0 until 8) {
                val x = w * (0.18f + i * 0.085f)
                drawRect(Color(0xFFD4AF37).copy(alpha = 0.8f), Offset(x, h * 0.84f), Size(w * 0.05f, h * 0.10f))
            }
        }
        PackShape.MEGA_DRIVE -> {
            drawRoundRect(body, cornerRadius = CornerRadius(r))
            drawRoundRect(shade, cornerRadius = CornerRadius(r))
            drawRect(Color(0xFF3C3F45), Offset(0f, h * 0.80f), Size(w, h * 0.20f))
            drawRect(tint.copy(alpha = 0.9f), Offset(w * 0.10f, h * 0.08f), Size(w * 0.80f, h * 0.05f))
        }
        PackShape.SEGA_CARD -> {
            drawRoundRect(body, cornerRadius = CornerRadius(r * 1.5f))
            drawRoundRect(shade, cornerRadius = CornerRadius(r * 1.5f))
            drawRect(tint.copy(alpha = 0.9f), Offset(w * 0.12f, h * 0.80f), Size(w * 0.76f, h * 0.06f))
        }
        PackShape.UMD -> {
            // A disc in a rounded shell, the window showing the art.
            drawRoundRect(body, cornerRadius = CornerRadius(w * 0.30f, w * 0.30f))
            drawRoundRect(shade, cornerRadius = CornerRadius(w * 0.30f, w * 0.30f))
            drawRoundRect(edge, style = Stroke(width = w * 0.015f), cornerRadius = CornerRadius(w * 0.30f, w * 0.30f))
        }
        PackShape.DISC, PackShape.MINI_DISC -> {
            // Silver rim behind the printed face.
            val d = if (shape == PackShape.MINI_DISC) 0.86f else 1f
            drawCircle(Brush.sweepGradient(listOf(Color(0xFFE0E4EA), Color(0xFF9EA6B2), Color(0xFFF5F7FA), Color(0xFFB0B8C4), Color(0xFFE0E4EA))), radius = w * d / 2f)
        }
        PackShape.ARCADE -> {
            // A bare board: green PCB with chips and an edge connector.
            drawRoundRect(body, cornerRadius = CornerRadius(r * 0.6f))
            for (i in 0 until 12) {
                val x = w * (0.08f + i * 0.07f)
                drawRect(Color(0xFFD4AF37), Offset(x, h * 0.88f), Size(w * 0.04f, h * 0.12f))
            }
            drawRect(Color(0xFF111111), Offset(w * 0.12f, h * 0.76f), Size(w * 0.18f, h * 0.08f))
            drawRect(Color(0xFF111111), Offset(w * 0.40f, h * 0.76f), Size(w * 0.18f, h * 0.08f))
        }
        PackShape.PC_BOX -> {
            drawRoundRect(body, cornerRadius = CornerRadius(r * 0.5f))
            drawRoundRect(shade, cornerRadius = CornerRadius(r * 0.5f))
            drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 0f), Size(w * 0.06f, h))
        }
    }
}

/** The hole and clamping ring in the middle of a disc. */
private fun DrawScope.drawDiscHub() {
    val c = center
    val w = size.width
    drawCircle(Color.White.copy(alpha = 0.55f), radius = w * 0.17f, center = c)
    drawCircle(Color(0xFF9EA6B2), radius = w * 0.17f, center = c, style = Stroke(width = w * 0.01f))
    drawCircle(Color(0xFF1E1E1E), radius = w * 0.07f, center = c)
}
