package com.manggome.oneemu.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Feature-phone (WIPI) compatibility list, judged per exact file the way WIPI-X does it: a game file whose SHA-256
 * is in the list gets that entry's status ("검증된 파일"); a different file of the same game (same PID - the
 * product id, unlike the AID several KTF games share) is flagged as another edition, since dumps of one title
 * differ by carrier build and repack. Anything else is 미확인.
 *
 * The list ships in the APK (cores/wipi/assets/compat.json -> coreassets/wipi/) and is refreshed from the
 * repository at most once a day, so results can grow without an app release.
 */
class WipiCompat private constructor(private val context: Context) {

    enum class Status(val key: String, val rank: Int) {
        PERFECT("perfect", 5), PLAYABLE("playable", 4), MENU("menu", 3), INTRO("intro", 2), BROKEN("broken", 1), UNKNOWN("", 0);

        companion object {
            fun of(key: String?): Status = entries.firstOrNull { it.key == key && it != UNKNOWN } ?: UNKNOWN
        }
    }

    enum class Match { EXACT, OTHER_EDITION, NONE }

    @Serializable
    data class FileRef(val sha256: String, val size: Long = 0)

    @Serializable
    data class Entry(
        val title: String,
        val carrier: String = "",
        val pid: String = "",
        val aid: String = "",
        val status: String = "",
        val tested: String = "",
        /** "manual": checked by a person on a device; "auto": wipi_headless run. */
        val verified: String = "auto",
        val note: String? = null,
        val files: List<FileRef> = emptyList(),
    ) {
        val statusValue: Status get() = Status.of(status)
    }

    @Serializable
    data class Database(val schema: Int = 1, val updated: String = "", val games: List<Entry> = emptyList())

    /** What the file itself is: its hash and the product id from its descriptor. */
    @Serializable
    data class Identity(val path: String, val size: Long, val modified: Long, val sha256: String, val pid: String = "", val carrier: String = "")

    data class Verdict(val status: Status, val match: Match, val entry: Entry?, val identity: Identity?)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    @Volatile private var db: Database? = null
    private val identities = HashMap<String, Identity>()
    private var identitiesLoaded = false

    private val cacheDir get() = File(context.filesDir, "wipi").apply { mkdirs() }
    private val onlineFile get() = File(cacheDir, "compat.json")
    private val identityFile get() = File(cacheDir, "identities.json")
    private val checkedFile get() = File(cacheDir, "compat.checked")

    /** Cached result for an unchanged file, without touching the disk beyond a stat. */
    fun cached(path: String): Verdict? {
        val base = db ?: return null
        val f = File(path)
        val id = synchronized(identities) { identities[path] }?.takeIf { it.size == f.length() && it.modified == f.lastModified() } ?: return null
        return match(base, id)
    }

    suspend fun resolve(path: String): Verdict = withContext(Dispatchers.IO) {
        val base = database()
        val id = identify(path) ?: return@withContext Verdict(Status.UNKNOWN, Match.NONE, null, null)
        match(base, id)
    }

    private fun match(base: Database, id: Identity): Verdict {
        base.games.firstOrNull { g -> g.files.any { it.sha256.equals(id.sha256, ignoreCase = true) } }?.let {
            return Verdict(it.statusValue, Match.EXACT, it, id)
        }
        if (id.pid.isNotEmpty()) {
            base.games.firstOrNull { it.pid.equals(id.pid, ignoreCase = true) }?.let {
                return Verdict(it.statusValue, Match.OTHER_EDITION, it, id)
            }
        }
        return Verdict(Status.UNKNOWN, Match.NONE, null, id)
    }

    /** Bundled list, or the downloaded one when it is newer. */
    suspend fun database(): Database = db ?: mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val bundled = runCatching {
                context.assets.open(BUNDLED_ASSET).use { json.decodeFromString<Database>(it.readBytes().decodeToString()) }
            }.getOrElse { Log.w(TAG, "bundled compat list unreadable", it); Database() }
            val online = runCatching { json.decodeFromString<Database>(onlineFile.readText()) }.getOrNull()
            val best = if (online != null && online.schema == 1 && online.updated > bundled.updated) online else bundled
            loadIdentities()
            best.also { db = it }
        }
    }

    /**
     * Fetches the repository's current list once a day. Returns true when a newer list replaced the one in use.
     * Offline or failing requests keep the current list silently.
     */
    suspend fun refreshOnline(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = runCatching { checkedFile.readText().trim().toLong() }.getOrDefault(0L)
        if (!force && now - last < REFRESH_INTERVAL_MS) return@withContext false
        runCatching { checkedFile.writeText(now.toString()) }
        val text = runCatching {
            client.newCall(Request.Builder().url(ONLINE_URL).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
        }.getOrNull() ?: return@withContext false
        val fetched = runCatching { json.decodeFromString<Database>(text) }.getOrNull() ?: return@withContext false
        val current = database()
        if (fetched.schema != 1 || fetched.updated <= current.updated) return@withContext false
        onlineFile.writeText(text)
        db = fetched
        true
    }

    // ---- file identity ----

    private fun loadIdentities() {
        if (identitiesLoaded) return
        identitiesLoaded = true
        runCatching {
            json.decodeFromString(ListSerializer(Identity.serializer()), identityFile.readText()).forEach { synchronized(identities) { identities[it.path] = it } }
        }
    }

    private fun saveIdentities() {
        runCatching { identityFile.writeText(json.encodeToString(ListSerializer(Identity.serializer()), synchronized(identities) { identities.values.toList() })) }
    }

    private fun identify(path: String): Identity? {
        val f = File(path)
        if (!f.isFile) return null
        synchronized(identities) { identities[path] }?.let { if (it.size == f.length() && it.modified == f.lastModified()) return it }
        val sha = runCatching { sha256(f) }.getOrNull() ?: return null
        val (pid, carrier) = descriptor(f)
        val id = Identity(path, f.length(), f.lastModified(), sha, pid, carrier)
        synchronized(identities) { identities[path] = id }
        saveIdentities()
        return id
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** PID and carrier from a KTF `__adf__` / LGT `app_info` descriptor (plain text, ASCII fields). */
    private fun descriptor(f: File): Pair<String, String> = runCatching {
        ZipFile(f).use { zip ->
            for (e in zip.entries()) {
                val name = e.name.substringAfterLast('/')
                val carrier = when {
                    name == "__adf__" || name.endsWith(".adf", ignoreCase = true) -> "KTF"
                    name == "app_info" -> "LGT"
                    else -> continue
                }
                val text = zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                val pid = text.lineSequence().firstOrNull { it.startsWith("PID:") }?.removePrefix("PID:")?.trim().orEmpty()
                return@use pid to carrier
            }
            "" to ""
        }
    }.getOrDefault("" to "")

    companion object {
        private const val TAG = "WipiCompat"
        const val BUNDLED_ASSET = "coreassets/wipi/compat.json"
        const val ONLINE_URL = "https://raw.githubusercontent.com/Manggome/OneEmu/main/cores/wipi/assets/compat.json"
        private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        }

        @Volatile private var instance: WipiCompat? = null
        fun get(context: Context): WipiCompat = instance ?: synchronized(this) {
            instance ?: WipiCompat(context.applicationContext).also { instance = it }
        }
    }
}
