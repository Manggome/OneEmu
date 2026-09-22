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

/** The three picture sets the server keeps for every system. */
enum class BoxArtKind(val folder: String) {
    BOXART("Named_Boxarts"),
    TITLE("Named_Titles"),
    SNAP("Named_Snaps"),
}

/** One picture the user can choose, as the server names it. */
data class BoxArtCandidate(val systemFolder: String, val name: String)

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
     * The release the server files [game] under, without downloading anything. The name is a
     * No-Intro / Redump one ("Sonic the Hedgehog 3 (USA)"), so it doubles as the game's official
     * English title; [releaseTitle] strips the tags off it.
     */
    suspend fun locate(game: GameEntity, searchName: String? = null): BoxArtCandidate? = withContext(Dispatchers.IO) {
        val system = SystemId.fromId(game.system) ?: return@withContext null
        val folders = FOLDERS[system] ?: return@withContext null
        val title = searchName ?: game.title
        val fileName = File(game.path).name
        val fileBase = fileName.substringBeforeLast('.')
        val keys = listOf(normalize(title), normalize(fileBase))
        // A Korean dump should get the Korean cover, not the American one that happens to sort first.
        val preferred = regionsOf(title) + regionsOf(fileName)

        // Once a folder's listing is in hand every lookup is local, which is what a whole-library
        // run does; the first game of a run still goes through the cheap pass below.
        for (folder in folders) {
            val listing = lookupByFolder[folder] ?: continue
            val hit = keys.firstNotNullOfOrNull { listing[it] }?.let { best(it, preferred) } ?: continue
            return@withContext BoxArtCandidate(folder, hit)
        }

        // Cheap pass: the obvious spellings, which hit for most well-named files.
        for (folder in folders) {
            if (lookupByFolder[folder] != null) continue
            for (name in candidates(title, fileName)) {
                coroutineContext.ensureActive()
                if (exists(fileUrl(folder, sanitize(name)))) return@withContext BoxArtCandidate(folder, sanitize(name))
            }
        }

        // The server is case-sensitive and its names carry tags ours do not, so fall back to its
        // own listing and match on letters and digits alone.
        for (folder in folders) {
            coroutineContext.ensureActive()
            val listing = lookup(folder) ?: continue
            val hit = keys.firstNotNullOfOrNull { listing[it] }?.let { best(it, preferred) } ?: continue
            return@withContext BoxArtCandidate(folder, hit)
        }
        null
    }

    /**
     * Downloads the box art for [game] and returns the file it was written to, or null when the
     * server has no picture under any of the names we can guess.
     */
    suspend fun fetch(game: GameEntity, searchName: String? = null): File? =
        locate(game, searchName)?.let { fetchChosen(game, it, BoxArtKind.BOXART) }

    /**
     * Every release the server has for one folder, read from its directory listing and cached on
     * disk: the listings run to a couple of megabytes and barely ever change.
     */
    private suspend fun names(folder: String): List<String>? {
        namesByFolder[folder]?.let { return it }
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
        namesByFolder[folder] = names
        return names
    }

    /**
     * Normalized release name → every file that carries it. Which one is best depends on the game
     * asking, so the choice is made at lookup time rather than baked into the cache.
     */
    private suspend fun lookup(folder: String): Map<String, List<String>>? {
        lookupByFolder[folder]?.let { return it }
        val all = names(folder) ?: return null
        val map = HashMap<String, MutableList<String>>(all.size * 2)
        for (name in all) {
            val key = normalize(name)
            if (key.isEmpty()) continue
            map.getOrPut(key) { ArrayList(1) } += name
        }
        lookupByFolder[folder] = map
        return map
    }

    /**
     * Everything the server has whose name contains [query], for the picker to show. Matching
     * ignores case, punctuation and bracketed tags, so "sonic 3" finds "Sonic The Hedgehog 3
     * (USA)"; the plainest, widest releases come first.
     */
    suspend fun search(
        systemId: String,
        query: String,
        preferredRegions: Set<String> = emptySet(),
        limit: Int = 60,
    ): List<BoxArtCandidate> =
        withContext(Dispatchers.IO) {
            val folders = SystemId.fromId(systemId)?.let { FOLDERS[it] } ?: return@withContext emptyList()
            val needle = normalize(query)
            if (needle.isEmpty()) return@withContext emptyList()
            val hits = ArrayList<BoxArtCandidate>()
            for (folder in folders) {
                coroutineContext.ensureActive()
                val all = names(folder) ?: continue
                for (name in all) if (normalize(name).contains(needle)) hits += BoxArtCandidate(folder, name)
            }
            rank(hits, needle, limit, preferredRegions)
        }

    /** Where [candidate] can be seen, for the picker's own image loading. */
    fun url(candidate: BoxArtCandidate, kind: BoxArtKind = BoxArtKind.BOXART): String =
        "$BASE/${Uri.encode(candidate.systemFolder)}/${kind.folder}/${Uri.encode(candidate.name)}.png"

    /** Downloads the picture the user chose and stores it as [game]'s thumbnail file. */
    suspend fun fetchChosen(game: GameEntity, candidate: BoxArtCandidate, kind: BoxArtKind): File? =
        withContext(Dispatchers.IO) {
            val bytes = download(url(candidate, kind)) ?: return@withContext null
            write(game, bytes)
        }

    private fun fileUrl(folder: String, name: String): String =
        "$BASE/${Uri.encode(folder)}/${BoxArtKind.BOXART.folder}/${Uri.encode(name)}.png"

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

    /** Listings for this process; the disk copy survives restarts. */
    private val namesByFolder = HashMap<String, List<String>>()
    private val lookupByFolder = HashMap<String, Map<String, List<String>>>()

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

        /**
         * The order the picker shows matches in: the exact title first, then the release a player is
         * most likely to mean, so "sonic 3" leads with Sonic 3 rather than Sonic 3D Blast.
         */
        internal fun rank(
            hits: List<BoxArtCandidate>,
            normalizedQuery: String,
            limit: Int,
            preferred: Set<String> = emptySet(),
        ): List<BoxArtCandidate> =
            hits.sortedWith(compareBy({ if (normalize(it.name) == normalizedQuery) 0 else 1 }, { score(it.name, preferred) }))
                .take(limit)

        /** The one to take out of several files sharing a name. */
        internal fun best(names: List<String>, preferred: Set<String> = emptySet()): String? =
            names.minByOrNull { score(it, preferred) }

        /**
         * Regions named in a release's tags, lowercased: "(Japan, Korea) (Ja)" gives japan and korea.
         * Language codes and version tags are not regions and are left out.
         */
        fun regionsOf(name: String): Set<String> =
            Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(name)
                .flatMap { it.groupValues[1].splitToSequence(',') }
                .map { it.trim().lowercase() }
                .filter { it in KNOWN_REGIONS }
                .toSet()

        private val KNOWN_REGIONS = setOf(
            "usa", "world", "europe", "japan", "korea", "asia", "australia", "brazil", "canada",
            "china", "france", "germany", "hong kong", "italy", "netherlands", "russia", "spain",
            "sweden", "taiwan",
        )

        /**
         * What to search for first. The server only holds English and romanized release names, so a
         * title with nothing it could ever match — a Korean one, which normalizes to an empty
         * string — is dropped in favour of the ROM's file name, which is usually English.
         */
        fun searchSeed(title: String, fileName: String): String {
            val fileBase = fileName.substringBeforeLast('.')
            return if (normalize(title).isNotEmpty()) title else fileBase
        }

        /**
         * A release name without its bracketed tags: "Sonic the Hedgehog 3 (USA)" -> "Sonic the Hedgehog 3".
         * Unlike [RomInfo.cleanTitle] this never touches dots, which belong to plenty of real titles
         * ("Super Mario Bros. 3").
         */
        fun releaseTitle(name: String): String =
            name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { name }

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
        fun score(name: String, preferred: Set<String> = emptySet()): Int {
            // A region the game itself names beats the default order: someone playing a Korean dump
            // wants the Korean cover, which would otherwise sort below every western release.
            if (preferred.isNotEmpty() && regionsOf(name).any { it in preferred }) return name.length
            val region = when {
                "(USA" in name -> 1
                "(World" in name -> 2
                "(Europe" in name -> 3
                "(Japan" in name -> 4
                else -> 5
            }
            return region * 10_000 + name.length
        }
    }
}
