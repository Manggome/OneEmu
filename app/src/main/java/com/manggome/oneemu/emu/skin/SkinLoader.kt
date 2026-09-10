package com.manggome.oneemu.emu.skin

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A skin with both orientation cfgs parsed and every referenced PNG decoded once. */
class LoadedSkin(
    val info: SkinInfo,
    val portrait: OverlayCfg,
    val landscape: OverlayCfg,
    val images: Map<String, ImageBitmap>,
) {
    fun cfg(landscape: Boolean): OverlayCfg = if (landscape) this.landscape else portrait
    fun image(path: String?): ImageBitmap? = path?.let(images::get)
}

/** Decodes skins on IO and keeps the most recent ones in memory (bitmaps are shared between pad, editor and picker). */
object SkinLoader {
    private const val MAX_SIDE = 1024
    private const val MAX_CACHED = 12
    private val cache = LinkedHashMap<String, LoadedSkin>(16, 0.75f, true)

    suspend fun load(context: Context, info: SkinInfo): LoadedSkin {
        synchronized(cache) { cache[info.id]?.let { return it } }
        val loaded = withContext(Dispatchers.IO) { decode(context, info) }
        synchronized(cache) {
            cache[info.id] = loaded
            while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
        }
        return loaded
    }

    fun evict(id: String) { synchronized(cache) { cache.remove(id) } }

    private fun decode(context: Context, info: SkinInfo): LoadedSkin {
        val port = parse(context, info, info.portraitCfg)
        val land = if (info.landscapeCfg == info.portraitCfg) port else parse(context, info, info.landscapeCfg)
        val paths = HashSet<String>()
        for (cfg in listOf(port, land)) for (o in cfg.overlays) {
            o.backgroundImage?.let(paths::add)
            for (d in o.descs) d.image?.let(paths::add)
        }
        val images = HashMap<String, ImageBitmap>(paths.size)
        for (p in paths) decodeImage(context, info, p)?.let { images[p] = it }
        return LoadedSkin(info, normalize(port, images), normalize(land, images), images)
    }

    private fun parse(context: Context, info: SkinInfo, rel: String): OverlayCfg {
        val text = info.open(context, rel).bufferedReader().use { it.readText() }
        return OverlayCfgParser.parse(text, rel.substringBeforeLast('/', ""))
    }

    /** Old cfgs with `normalized = false` express coordinates in background-image pixels; convert them. */
    private fun normalize(cfg: OverlayCfg, images: Map<String, ImageBitmap>): OverlayCfg {
        if (cfg.overlays.all { it.normalized }) return cfg
        return OverlayCfg(cfg.overlays.map { o ->
            if (o.normalized) return@map o
            val bg = o.backgroundImage?.let(images::get) ?: return@map o.copy(normalized = true)
            val w = bg.width.toFloat(); val h = bg.height.toFloat()
            o.copy(normalized = true, descs = o.descs.map { d -> d.copy(x = d.x / w, y = d.y / h, rx = d.rx / w, ry = d.ry / h) })
        })
    }

    private fun decodeImage(context: Context, info: SkinInfo, rel: String): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        info.open(context, rel).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        info.open(context, rel).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    }.getOrNull()
}
