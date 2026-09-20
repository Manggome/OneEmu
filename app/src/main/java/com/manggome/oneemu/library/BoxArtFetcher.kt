package com.manggome.oneemu.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.update.UpdateChecker
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Box art from the public libretro thumbnail server. Files there are named after the No-Intro /
 * Redump release ("Sonic the Hedgehog 3 (USA).png"), while our titles come from the file name and
 * usually have the region tag stripped, so a handful of spellings are tried before giving up.
 *
 * Nothing but the picture is downloaded: no account, no tracking, and a miss is just a 404.
 */
class BoxArtFetcher(
    private val dirs: AppDirs,
    private val client: OkHttpClient = UpdateChecker.defaultClient(),
) {
    /**
     * Downloads the box art for [game] and returns the file it was written to, or null when the
     * server has no picture under any of the names we can guess.
     */
    suspend fun fetch(game: GameEntity, searchName: String? = null): File? = withContext(Dispatchers.IO) {
        val system = SystemId.fromId(game.system) ?: return@withContext null
        val folders = FOLDERS[system] ?: return@withContext null
        val title = searchName ?: game.title
        val fileBase = File(game.path).name.substringBeforeLast('.')
        val keys = listOf(normalize(title), normalize(fileBase))

        // Once a folder's listing is in hand every lookup is local, which is what a whole-library
        // run does; the first game of a run still goes through the cheap pass below.
        for (folder in folders) {
            val listing = memory[folder] ?: continue
            val hit = keys.firstNotNullOfOrNull { listing[it] } ?: continue
            download(fileUrl(folder, hit))?.let { return@withContext write(game, it) }
        }

        // Cheap pass: the obvious spellings, which hit for most well-named files.
        for (folder in folders) {
            if (memory[folder] != null) continue
            for (name in candidates(title, File(game.path).name)) {
                coroutineContext.ensureActive()
                val url = fileUrl(folder, sanitize(name))
                if (!exists(url)) continue
                download(url)?.let { return@withContext write(game, it) }
            }
        }

        // The server is case-sensitive and its names carry tags ours do not, so fall back to its
        // own listing and match on letters and digits alone.
        for (folder in folders) {
            coroutineContext.ensureActive()
            val listing = index(folder) ?: continue
            val hit = keys.firstNotNullOfOrNull { listing[it] } ?: continue
            download(fileUrl(folder, hit))?.let { return@withContext write(game, it) }
        }
        null
    }

    /**
     * Normalized release name → file name for one folder, read from the server's directory listing
     * and cached on disk: the listings run to a couple of megabytes and barely ever change.
     */
    private suspend fun index(folder: String): Map<String, String>? {
        memory[folder]?.let { return it }
        val cache = File(dirs.boxArtIndex, "${sanitize(folder).replace(' ', '_')}.txt")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS
        val names = if (fresh) {
            runCatching { cache.readLines() }.getOrNull()
        } else {
            val html = runCatching {
                client.newCall(Request.Builder().url("$BASE/${Uri.encode(folder)}/Named_Boxarts/").header("User-Agent", UA).build())
                    .execute().use { if (it.isSuccessful) it.body.string() else null }
            }.onFailure { Log.w(TAG, "listing failed: $folder", it) }.getOrNull()
            html?.let { parseListing(it) }?.also { list ->
                runCatching { cache.writeText(list.joinToString("\n")) }
            } ?: runCatching { cache.takeIf { it.isFile }?.readLines() }.getOrNull()
        }
        if (names.isNullOrEmpty()) return null
        val map = HashMap<String, String>(names.size * 2)
        for (name in names) {
            val key = normalize(name)
            if (key.isEmpty()) continue
            val current = map[key]
            if (current == null || score(name) < score(current)) map[key] = name
        }
        memory[folder] = map
        return map
    }

    private fun fileUrl(folder: String, name: String): String =
        "$BASE/${Uri.encode(folder)}/Named_Boxarts/${Uri.encode(name)}.png"

    private fun exists(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).head().header("User-Agent", UA).build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun download(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.bytes()
        }
    }.onFailure { Log.w(TAG, "box art download failed: $url", it) }.getOrNull()

    /** Stored the same way a hand-picked thumbnail is, so the library's cleanup finds it. */
    private fun write(game: GameEntity, bytes: ByteArray): File? {
        val bmp = decodeScaled(bytes, MAX_PX) ?: return null
        val out = File(dirs.thumbnails, "${game.id}_${System.currentTimeMillis()}.jpg")
        val ok = runCatching { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.isSuccess
        bmp.recycle()
        if (!ok) { out.delete(); return null }
        return out
    }

    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(maxPx.toFloat() / decoded.width, maxPx.toFloat() / decoded.height, 1f)
        if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    }.getOrNull()

    /** Parsed listings for this process; the disk copy survives restarts. */
    private val memory = HashMap<String, Map<String, String>>()

    companion object {
        private const val TAG = "BoxArt"
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val BASE = "https://thumbnails.libretro.com"
        private const val UA = "OneEmu"
        private const val MAX_PX = 512

        /** Region tags tried on a title that has none; the common releases first. */
        private val REGIONS = listOf("USA", "Europe", "Japan", "World", "USA, Europe", "Japan, USA")

        /**
         * libretro's folder per system. A system is absent when the server has no set for it, and
         * the GameCube entry also covers Wii because both land in [SystemId.GC].
         */
        val FOLDERS: Map<SystemId, List<String>> = mapOf(
            SystemId.NES to listOf("Nintendo - Nintendo Entertainment System"),
            SystemId.GB to listOf("Nintendo - Game Boy"),
            SystemId.GBC to listOf("Nintendo - Game Boy Color"),
            SystemId.GBA to listOf("Nintendo - Game Boy Advance"),
            SystemId.NDS to listOf("Nintendo - Nintendo DS"),
            SystemId.N3DS to listOf("Nintendo - Nintendo 3DS"),
            SystemId.GC to listOf("Nintendo - GameCube", "Nintendo - Wii"),
            SystemId.MD to listOf("Sega - Mega Drive - Genesis"),
            SystemId.SMS to listOf("Sega - Master System - Mark III"),
            SystemId.GG to listOf("Sega - Game Gear"),
            SystemId.PSX to listOf("Sony - PlayStation"),
            SystemId.PSP to listOf("Sony - PlayStation Portable"),
            SystemId.PS2 to listOf("Sony - PlayStation 2"),
            SystemId.ARCADE to listOf("MAME"),
        )

        /** True when we could even try: a system with no thumbnail set is skipped silently. */
        fun isSupported(game: GameEntity): Boolean = SystemId.fromId(game.system)?.let { it in FOLDERS } == true

        /**
         * Spellings to try, best first: the title as it is, the title without its bracketed tags,
         * then that title with each region tag, and the same for the file name.
         */
        fun candidates(title: String, fileName: String): List<String> {
            val out = LinkedHashSet<String>()
            for (base in listOf(title, fileName.substringBeforeLast('.'))) {
                val clean = base.replace(Regex("\\s+"), " ").trim()
                if (clean.isEmpty()) continue
                out += clean
                val bare = clean.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()
                if (bare.isEmpty()) continue
                out += bare
                for (region in REGIONS) out += "$bare ($region)"
            }
            return out.toList()
        }

        /** RetroArch's own rule for turning a release name into a file name. */
        fun sanitize(name: String): String = name.replace(Regex("[&*/:`\"<>?\\\\|]"), "_")

        /** `href="..."` entries of the server's directory listing, URL-decoded, without ".png". */
        fun parseListing(html: String): List<String> =
            Regex("href=\"([^\"]+)\\.png\"").findAll(html)
                .mapNotNull { runCatching { Uri.decode(it.groupValues[1]) }.getOrNull() }
                .toList()

        /**
         * Letters and digits only, with bracketed tags dropped: "Sonic the Hedgehog 3 (USA)" and
         * "Sonic The Hedgehog 3" both become "sonicthehedgehog3".
         */
        fun normalize(name: String): String = name.lowercase()
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("[^a-z0-9]+"), "")

        /**
         * Which of several files sharing a normalized name to keep: the widest release first, then
         * the plainest name, so "(USA)" beats "(USA) (Beta)" and a demo.
         */
        fun score(name: String): Int {
            val region = when {
                "(USA" in name -> 0
                "(World" in name -> 1
                "(Europe" in name -> 2
                "(Japan" in name -> 3
                else -> 4
            }
            return region * 1000 + name.length
        }
    }
}
