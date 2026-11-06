package com.manggome.oneemu.emu.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A skin with both orientation cfgs parsed and every referenced PNG decoded once. */
class LoadedSkin(
    val info: SkinInfo,
    val portrait: OverlayCfg,
    val landscape: OverlayCfg,
    val images: Map<String, ImageBitmap>,
) {
    fun cfg(landscape: Boolean): OverlayCfg = if (landscape) this.landscape else portrait
    fun image(path: String?): ImageBitmap? = path?.let(images::get)

    internal val bytes: Long by lazy { images.values.sumOf { runCatching { it.asAndroidBitmap().allocationByteCount.toLong() }.getOrDefault(0L) } }
}

/** Decodes skins on IO and keeps the most recent ones in memory (bitmaps are shared between pad, editor and picker). */
object SkinLoader {
    private const val MAX_SIDE = 1024
    /** Delta backgrounds are whole-screen pictures; render them at screen resolution, within reason. */
    private const val DELTA_MAX_SIDE = 2400
    private const val MAX_CACHED = 12
    /** Delta skins are screen-sized bitmaps; keep the cache from holding a dozen of them. */
    private const val MAX_CACHED_BYTES = 96L * 1024 * 1024
    private val cache = LinkedHashMap<String, LoadedSkin>(16, 0.75f, true)

    suspend fun load(context: Context, info: SkinInfo): LoadedSkin {
        val key = cacheKey(context, info)
        synchronized(cache) { cache[key]?.let { return it } }
        val loaded = withContext(Dispatchers.IO) { if (info.isDelta) decodeDelta(context, info) else decode(context, info) }
        synchronized(cache) {
            cache[key] = loaded
            while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            while (cache.size > 1 && cache.values.sumOf { it.bytes } > MAX_CACHED_BYTES) cache.remove(cache.keys.first())
        }
        return loaded
    }

    fun evict(id: String) { synchronized(cache) { cache.keys.removeAll { it == id || it.startsWith("$id@") } } }

    /** A Delta skin's representation depends on the screen shape, which a fold or a resize changes. */
    private fun cacheKey(context: Context, info: SkinInfo): String {
        if (!info.isDelta) return info.id
        val c = context.resources.configuration
        return "${info.id}@${minOf(c.screenWidthDp, c.screenHeightDp)}x${maxOf(c.screenWidthDp, c.screenHeightDp)}"
    }

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

    // ---- Delta skins ----

    /**
     * Picks the representation that suits this screen for each orientation and renders its pictures at the
     * size they will be drawn. An orientation with nothing usable gets an empty cfg, so the pad falls back to
     * the built-in one there.
     */
    private fun decodeDelta(context: Context, info: SkinInfo): LoadedSkin {
        val dir = info.fileDir ?: throw SkinStore.ImportException("no folder")
        val skin = DeltaSkinParser.parse(File(dir, SkinStore.DELTA_INFO).readText())
        val system = DeltaGameTypes.primarySystem(skin.gameTypeIdentifier) ?: throw SkinStore.ImportException("unknown game type")
        val dm = context.resources.displayMetrics
        val shortPx = minOf(dm.widthPixels, dm.heightPixels).toFloat().coerceAtLeast(1f)
        val longPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat().coerceAtLeast(1f)

        // Pixel size each picture is drawn at: the largest use wins.
        val wanted = HashMap<String, Pair<Int, Int>>()
        fun want(path: String?, w: Float, h: Float) {
            if (path == null || w <= 0f || h <= 0f) return
            val cur = wanted[path]
            val nw = ceil(w).toInt(); val nh = ceil(h).toInt()
            if (cur == null || nw * nh > cur.first * cur.second) wanted[path] = nw to nh
        }
        fun orientation(landscape: Boolean): OverlayCfg {
            val sw = if (landscape) longPx else shortPx
            val sh = if (landscape) shortPx else longPx
            val (rep, overlay) = DeltaSkinLayout.pick(skin, landscape, sw / sh, system) ?: return OverlayCfg(emptyList())
            val scale = minOf(sw / rep.mappingWidth, sh / rep.mappingHeight)
            val boxW = rep.mappingWidth * scale
            val boxH = rep.mappingHeight * scale
            want(rep.background, boxW, boxH)
            // Desc sizes are normalized to the design box.
            for (d in overlay.descs) want(d.image, d.rx * 2f * d.imageScaleX * boxW, d.ry * 2f * d.imageScaleY * boxH)
            return OverlayCfg(listOf(overlay))
        }
        val port = orientation(landscape = false)
        val land = orientation(landscape = true)
        val images = HashMap<String, ImageBitmap>(wanted.size)
        for ((path, size) in wanted) {
            val file = File(dir, path).canonicalFile
            if (!file.path.startsWith(dir.canonicalPath + File.separator) || !file.isFile) continue
            renderDeltaImage(file, size.first, size.second)?.let { images[path] = it.asImageBitmap() }
        }
        return LoadedSkin(info, port, land, images)
    }

    /** PDF pages are rasterised at exactly [w]x[h] (capped); PNG/JPEG are decoded no larger than needed. */
    private fun renderDeltaImage(file: File, w: Int, h: Int): Bitmap? = runCatching {
        var tw = w.coerceAtLeast(1)
        var th = h.coerceAtLeast(1)
        val longest = maxOf(tw, th)
        if (longest > DELTA_MAX_SIDE) {
            val f = DELTA_MAX_SIDE.toFloat() / longest
            tw = (tw * f).roundToInt().coerceAtLeast(1); th = (th * f).roundToInt().coerceAtLeast(1)
        }
        if (file.extension.equals("pdf", ignoreCase = true)) renderPdf(file, tw, th) else decodeBitmap(file, tw, th)
    }.getOrNull()

    private fun renderPdf(file: File, w: Int, h: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount < 1) return null
                val page = renderer.openPage(0)
                try {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // Null transform: the page is scaled to fill the bitmap; transparent areas stay transparent.
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally { page.close() }
            } finally { renderer.close() }
        } finally { pfd.close() }
    }

    private fun decodeBitmap(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.path, opts)
    }
}
