package com.manggome.oneemu.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.MainActivity
import java.io.File

/**
 * Home-screen shortcuts that start a game directly: one pinned by the player (게임 메뉴 → 홈 화면에 추가),
 * and the last few games played offered on a long press of the app icon. Both open [MainActivity] with
 * [ACTION_PLAY], which launches the game the same way "다른 앱으로 열기" does.
 */
object GameShortcuts {
    const val ACTION_PLAY = "com.manggome.oneemu.action.PLAY"
    const val EXTRA_GAME_ID = "game_id"
    private const val ICON_PX = 192
    private const val RECENT_COUNT = 3

    fun playIntent(context: Context, gameId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_PLAY)
            .putExtra(EXTRA_GAME_ID, gameId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun info(context: Context, game: GameEntity, id: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(game.title.take(24).ifBlank { "?" })
            .setLongLabel(game.title.take(60).ifBlank { "?" })
            .setIcon(IconCompat.createWithBitmap(icon(context, game)))
            .setIntent(playIntent(context, game.id))
            .build()

    /** Asks the launcher to pin [game]; false when the launcher cannot pin shortcuts at all. */
    fun pin(context: Context, game: GameEntity): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, info(context, game, "game_${game.id}"), null)
    }

    /** The long-press menu on the app icon: the last games played, most recent first. Call off the main thread. */
    fun updateRecent(context: Context, recent: List<GameEntity>) {
        runCatching {
            val list = recent.take(RECENT_COUNT).mapIndexed { i, g ->
                ShortcutInfoCompat.Builder(context, "recent_$i")
                    .setShortLabel(g.title.take(24).ifBlank { "?" })
                    .setLongLabel(g.title.take(60).ifBlank { "?" })
                    .setIcon(IconCompat.createWithBitmap(icon(context, g)))
                    .setIntent(playIntent(context, g.id))
                    .setRank(i)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        }
    }

    /** The game's own picture cropped to a rounded square, or its initials on the system colour. */
    private fun icon(context: Context, game: GameEntity): Bitmap {
        val source = listOfNotNull(game.thumbnail?.takeIf { it.isNotBlank() }, game.autoIcon?.takeIf { it.isNotBlank() })
            .firstNotNullOfOrNull { decode(context, it) }
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds = RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat())
        val system = SystemId.fromId(game.system)
        paint.color = system?.color?.toArgb() ?: 0xFF555555.toInt()
        canvas.drawRoundRect(bounds, ICON_PX * 0.2f, ICON_PX * 0.2f, paint)
        if (source != null) {
            // Centre-crop into the rounded square.
            val side = minOf(source.width, source.height)
            val src = Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
            val layer = canvas.saveLayer(bounds, null)
            canvas.drawRoundRect(bounds, ICON_PX * 0.2f, ICON_PX * 0.2f, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, src, bounds, paint)
            paint.xfermode = null
            canvas.restoreToCount(layer)
        } else {
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = ICON_PX * 0.36f
            val text = game.title.trim().take(2).ifEmpty { "?" }
            canvas.drawText(text, ICON_PX / 2f, ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        }
        return out
    }

    private fun decode(context: Context, source: String): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        if (source.startsWith("content://") || source.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(source))?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else {
            File(source).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath, opts) }
        }
    }.getOrNull()
}
