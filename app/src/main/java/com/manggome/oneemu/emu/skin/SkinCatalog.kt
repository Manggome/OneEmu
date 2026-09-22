package com.manggome.oneemu.emu.skin

import android.content.Context
import android.util.Log
import com.manggome.oneemu.BuildConfig
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.update.GitHubRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** One skin of `catalog.json` (schema in scripts/README-skins.md). */
@Serializable
data class CatalogSkin(
    val id: String,
    val name: String = "",
    val family: String = "",
    val author: String = "",
    val license: String = "CC BY 4.0",
    /**
     * Pads this skin suits, as [PadProfile.key] - a plain system id for almost every one, and "gc-wii"
     * for a Wii Remote overlay, which is a different pad from the GameCube controller on the same core.
     * `"*"` = universal, empty = a console without a core (SNES, N64 …).
     */
    val systems: List<String> = emptyList(),
    val source: String = "",
    val previewPortrait: String = "",
    val previewLandscape: String = "",
    val zip: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val buttons: List<String> = emptyList(),
    val hasAnalog: Boolean = false,
    val hasPortrait: Boolean = true,
    val hasLandscape: Boolean = true,
) {
    val universal: Boolean get() = "*" in systems
    fun suits(systemId: String): Boolean = systemId in systems
    /** Skin id once installed (`user:<id>`), the same id [SkinStore] gives a manual import of this folder. */
    val installedId: String get() = SkinStore.userSkinId(id)
}

@Serializable
data class SkinCatalog(
    val schema: Int = 1,
    val generatedAt: String = "",
    val source: String = "",
    val license: String = "",
    val skins: List<CatalogSkin> = emptyList(),
)

sealed class SkinCatalogState {
    data object Idle : SkinCatalogState()
    data object Loading : SkinCatalogState()
    data class Loaded(val catalog: SkinCatalog, val fetchedAt: Long, val fromCache: Boolean) : SkinCatalogState()
    /** [message] is the Korean explanation shown in place of the grid. */
    data class Unavailable(val message: String) : SkinCatalogState()
}

/** Progress of one skin download; absent from [SkinCatalogManager.downloads] means nothing is happening. */
sealed class SkinDownloadState {
    /** [progress] 0..1, or -1 while the size is unknown. */
    data class Downloading(val progress: Float) : SkinDownloadState()
    data object Installing : SkinDownloadState()
    data object Installed : SkinDownloadState()
    data class Failed(val message: String) : SkinDownloadState()

    val isBusy: Boolean get() = this is Downloading || this is Installing
}

/**
 * Online pad-skin catalog: reads `catalog.json` from the rolling GitHub release tagged `skins` (built by
 * scripts/build-skin-catalog.py), caches it for an hour in memory and in [Context.getCacheDir], and installs
 * skins through [SkinStore.installZip] so they show up as imported (`user:`) skins. Downloads run in the app
 * scope, so leaving the picker does not cancel them. Mirrors the CoreDownloadManager conventions.
 */
object SkinCatalogManager {
    private const val TAG = "SkinCatalog"
    const val RELEASE_TAG = "skins"
    const val CATALOG_NAME = "catalog.json"
    private const val TTL_MS = 60 * 60 * 1000L
    private const val CACHE_FILE = "skin-catalog.json"

    private val repo: String get() = BuildConfig.GITHUB_REPO
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val _state = MutableStateFlow<SkinCatalogState>(SkinCatalogState.Idle)
    val state: StateFlow<SkinCatalogState> = _state.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, SkinDownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, SkinDownloadState>> = _downloads.asStateFlow()

    private val lock = Mutex()
    private val jobs = HashMap<String, Job>()

    fun clearDownloadState(id: String) = _downloads.update { it - id }

    /** Loads the catalog: memory (1 h) → disk cache (1 h) → GitHub API (`releases/tags/skins`) → direct asset URL. */
    suspend fun refresh(context: Context, force: Boolean = false): SkinCatalogState = lock.withLock {
        val cur = _state.value
        val now = System.currentTimeMillis()
        if (!force && cur is SkinCatalogState.Loaded && now - cur.fetchedAt < TTL_MS) return@withLock cur
        _state.value = SkinCatalogState.Loading
        val result = withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, CACHE_FILE)
            if (!force) {
                readCache(cacheFile)?.let { return@withContext SkinCatalogState.Loaded(it, cacheFile.lastModified(), fromCache = true) }
            }
            when (val fetched = fetch(context)) {
                is Fetched.Ok -> {
                    runCatching { cacheFile.writeText(fetched.text) }
                    SkinCatalogState.Loaded(fetched.catalog, now, fromCache = false)
                }
                is Fetched.Error -> {
                    // Stale cache beats an error screen.
                    readCache(cacheFile, ignoreAge = true)?.let { SkinCatalogState.Loaded(it, now, fromCache = true) } ?: SkinCatalogState.Unavailable(fetched.message)
                }
            }
        }
        _state.value = result
        result
    }

    private fun readCache(file: File, ignoreAge: Boolean = false): SkinCatalog? {
        if (!file.isFile) return null
        if (!ignoreAge && System.currentTimeMillis() - file.lastModified() > TTL_MS) return null
        return runCatching { json.decodeFromString<SkinCatalog>(file.readText()) }.getOrNull()?.takeIf { it.skins.isNotEmpty() }
    }

    private sealed class Fetched {
        class Ok(val catalog: SkinCatalog, val text: String) : Fetched()
        class Error(val message: String) : Fetched()
    }

    private fun fetch(context: Context): Fetched {
        var networkError = false
        try {
            val api = Request.Builder().url("https://api.github.com/repos/$repo/releases/tags/$RELEASE_TAG")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "OneEmu").build()
            client.newCall(api).execute().use { r ->
                when {
                    r.code == 404 -> return Fetched.Error(context.getString(R.string.skins_online_err_no_release))
                    r.isSuccessful -> {
                        val release = json.decodeFromString<GitHubRelease>(r.body.string())
                        val asset = release.assets.firstOrNull { it.name == CATALOG_NAME }
                            ?: return Fetched.Error(context.getString(R.string.skins_online_err_no_release))
                        return fetchFrom(context, asset.browserDownloadUrl)
                    }
                    else -> Log.i(TAG, "catalog API HTTP ${r.code}; trying direct URL")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "catalog API failed (network): ${e.message}")
            networkError = true
        } catch (e: Exception) {
            Log.w(TAG, "catalog API failed", e)
        }
        return try {
            fetchFrom(context, "https://github.com/$repo/releases/download/$RELEASE_TAG/$CATALOG_NAME")
        } catch (e: IOException) {
            Log.i(TAG, "catalog download failed (network): ${e.message}")
            Fetched.Error(context.getString(R.string.skins_online_err_network))
        } catch (e: Exception) {
            Log.w(TAG, "catalog download failed", e)
            Fetched.Error(context.getString(R.string.skins_online_err_generic, e.message ?: if (networkError) "network" else e.javaClass.simpleName))
        }
    }

    private fun fetchFrom(context: Context, url: String): Fetched {
        val req = Request.Builder().url(url).header("User-Agent", "OneEmu").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return Fetched.Error(context.getString(R.string.skins_online_err_no_release))
            if (!r.isSuccessful) return Fetched.Error(context.getString(R.string.skins_online_err_http, r.code))
            val text = r.body.string()
            val catalog = json.decodeFromString<SkinCatalog>(text)
            Log.i(TAG, "catalog: ${catalog.skins.size} skins (generated ${catalog.generatedAt})")
            return Fetched.Ok(catalog, text)
        }
    }

    /** Starts (or returns the running) download + install of [skin]; progress and outcome in [downloads]. */
    fun download(context: Context, skin: CatalogSkin): Job {
        val app = context.applicationContext
        synchronized(jobs) {
            jobs[skin.id]?.takeIf { it.isActive }?.let { return it }
            val job = OneEmuApp.get().appScope.launch { run(app, skin) }
            jobs[skin.id] = job
            return job
        }
    }

    private fun set(id: String, s: SkinDownloadState) = _downloads.update { it + (id to s) }

    private suspend fun run(context: Context, skin: CatalogSkin) {
        set(skin.id, SkinDownloadState.Downloading(-1f))
        val tmp = File(context.cacheDir, "skin-${skin.id}.zip.part")
        try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(skin.zip).header("User-Agent", "OneEmu").build()
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw DownloadException(context.getString(R.string.skins_online_err_http, r.code))
                    val body = r.body
                    val total = if (skin.size > 0) skin.size else body.contentLength()
                    var read = 0L
                    var lastPublish = 0L
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        tmp.outputStream().buffered(128 * 1024).use { out ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastPublish > 120) {
                                    lastPublish = now
                                    set(skin.id, SkinDownloadState.Downloading(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f))
                                }
                            }
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (skin.sha256.isNotBlank() && !sha.equals(skin.sha256, ignoreCase = true)) {
                    throw DownloadException(context.getString(R.string.skins_online_err_sha))
                }
            }
            set(skin.id, SkinDownloadState.Installing)
            SkinStore.installZip(context, tmp, skin.id)
            set(skin.id, SkinDownloadState.Installed)
            Log.i(TAG, "installed ${skin.id}")
        } catch (e: DownloadException) {
            set(skin.id, SkinDownloadState.Failed(e.message ?: ""))
        } catch (e: SkinStore.ImportException) {
            Log.w(TAG, "install failed for ${skin.id}", e)
            set(skin.id, SkinDownloadState.Failed(context.getString(R.string.skins_online_err_invalid)))
        } catch (e: IOException) {
            Log.w(TAG, "download failed for ${skin.id}", e)
            set(skin.id, SkinDownloadState.Failed(context.getString(R.string.skins_online_err_network_during)))
        } catch (e: CancellationException) {
            _downloads.update { it - skin.id }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download failed for ${skin.id}", e)
            set(skin.id, SkinDownloadState.Failed(context.getString(R.string.skins_online_err_generic, e.message ?: e.javaClass.simpleName)))
        } finally {
            tmp.delete()
        }
    }

    private class DownloadException(message: String) : Exception(message)
}
