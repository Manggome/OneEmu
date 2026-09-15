package com.manggome.oneemu.core

import android.content.Context
import android.util.Log
import com.manggome.oneemu.BuildConfig
import com.manggome.oneemu.R
import com.manggome.oneemu.update.GitHubRelease
import com.manggome.oneemu.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
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
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * One entry of the `manifest.json` that `.github/workflows/cores.yml` publishes on the rolling GitHub release
 * tagged `cores` (schema in cores/README.md, "내려받기형 코어").
 */
@Serializable
data class CoreManifestEntry(
    val id: String,
    /** First 12 chars of the core's sourceCommit; written to `version.txt` after installing. */
    val version: String = "",
    val file: String = "",
    val url: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val libFile: String = "",
    val minAppVersion: String = "",
)

@Serializable
data class CoreManifest(val schema: Int = 1, val cores: List<CoreManifestEntry> = emptyList()) {
    fun entry(coreId: String): CoreManifestEntry? = cores.firstOrNull { it.id == coreId }
}

/** Progress of one core's download; absent from [CoreDownloadManager.state] means nothing is happening. */
sealed class DownloadState {
    data object Idle : DownloadState()
    /** [progress] 0..1, or -1 while the total size is unknown. */
    data class Downloading(val progress: Float, val bytes: Long, val total: Long) : DownloadState()
    data object Extracting : DownloadState()
    data class Installed(val version: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()

    val isBusy: Boolean get() = this is Downloading || this is Extracting
}

/** Whether the `cores` release manifest could be read. */
sealed class ManifestState {
    data object Idle : ManifestState()
    data object Loading : ManifestState()
    data class Loaded(val manifest: CoreManifest, val fetchedAt: Long) : ManifestState()
    /** [message] is the Korean explanation; [noRelease] = the `cores` release (or its manifest) does not exist yet. */
    data class Unavailable(val message: String, val noRelease: Boolean) : ManifestState()
}

/**
 * Installs `distribution: download` cores from the `cores` GitHub release into [CoreRegistry.downloadDir]:
 * streams `<id>-<commit12>-arm64.zip` to `tmp.zip` (progress in [state]), verifies its sha256, extracts
 * `lib<id>_libretro.so` next to a `version.txt`, and deletes the temp file. Runs in [scope] (the app scope),
 * so leaving a screen does not cancel a download. Availability is file based ([CoreRegistry.isAvailable]),
 * so nothing else needs to be told when an install finishes.
 *
 * A missing `cores` release (nothing published yet) and a missing network are reported through [manifest]
 * with Korean messages instead of exceptions.
 */
class CoreDownloadManager(
    private val context: Context,
    private val registry: CoreRegistry,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    private val repo: String = BuildConfig.GITHUB_REPO,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _manifest = MutableStateFlow<ManifestState>(ManifestState.Idle)
    val manifest: StateFlow<ManifestState> = _manifest.asStateFlow()

    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()

    private val manifestLock = Mutex()
    private val jobs = HashMap<String, Job>()

    fun stateOf(coreId: String): DownloadState = _state.value[coreId] ?: DownloadState.Idle

    private fun setState(coreId: String, s: DownloadState) = _state.update { it + (coreId to s) }

    /** Manifest entry for [coreId], when the manifest has been loaded and lists it. */
    fun entry(coreId: String): CoreManifestEntry? = (_manifest.value as? ManifestState.Loaded)?.manifest?.entry(coreId)

    /** True when the manifest lists a version other than the installed one (installed cores only). */
    fun updateAvailable(coreId: String): Boolean {
        val core = registry.core(coreId) ?: return false
        val installed = registry.installedVersion(core) ?: return false
        val remote = entry(coreId)?.version ?: return false
        return remote.isNotEmpty() && remote != installed
    }

    /**
     * Loads the manifest (cached for [MANIFEST_TTL_MS] unless [force]). Tries the GitHub API
     * (`releases/tags/cores` → asset `manifest.json`) first, then the direct download URL.
     */
    suspend fun refreshManifest(force: Boolean = false): ManifestState = manifestLock.withLock {
        val cur = _manifest.value
        if (!force && cur is ManifestState.Loaded && System.currentTimeMillis() - cur.fetchedAt < MANIFEST_TTL_MS) return@withLock cur
        _manifest.value = ManifestState.Loading
        val result = withContext(Dispatchers.IO) { fetchManifest() }
        _manifest.value = result
        result
    }

    private fun fetchManifest(): ManifestState {
        var networkError: String? = null
        // 1. API: tells a missing release (404) apart from a network problem.
        try {
            val api = Request.Builder().url("https://api.github.com/repos/$repo/releases/tags/$RELEASE_TAG")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "OneEmu").build()
            client.newCall(api).execute().use { r ->
                when {
                    r.code == 404 -> return noRelease()
                    r.isSuccessful -> {
                        val release = json.decodeFromString<GitHubRelease>(r.body.string())
                        val asset = release.assets.firstOrNull { it.name == MANIFEST_NAME } ?: return noRelease()
                        return fetchManifestFrom(asset.browserDownloadUrl)
                    }
                    else -> Log.i(TAG, "manifest API HTTP ${r.code}; trying direct URL")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "manifest API failed (network): ${e.message}")
            networkError = e.message
        } catch (e: Exception) {
            Log.w(TAG, "manifest API failed", e)
        }
        // 2. Direct asset URL (no API rate limit).
        return try {
            fetchManifestFrom("https://github.com/$repo/releases/download/$RELEASE_TAG/$MANIFEST_NAME")
        } catch (e: IOException) {
            Log.i(TAG, "manifest download failed (network): ${e.message}")
            ManifestState.Unavailable(context.getString(R.string.core_dl_err_network), noRelease = false)
        } catch (e: Exception) {
            Log.w(TAG, "manifest download failed", e)
            ManifestState.Unavailable(context.getString(R.string.core_dl_err_generic, e.message ?: networkError ?: e.javaClass.simpleName), noRelease = false)
        }
    }

    private fun noRelease() = ManifestState.Unavailable(context.getString(R.string.core_dl_err_no_release), noRelease = true)

    private fun fetchManifestFrom(url: String): ManifestState {
        val req = Request.Builder().url(url).header("User-Agent", "OneEmu").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return noRelease()
            if (!r.isSuccessful) return ManifestState.Unavailable(context.getString(R.string.core_dl_err_http, r.code), noRelease = false)
            val m = json.decodeFromString<CoreManifest>(r.body.string())
            Log.i(TAG, "manifest: ${m.cores.size} cores (${m.cores.joinToString { "${it.id}@${it.version}" }})")
            return ManifestState.Loaded(m, System.currentTimeMillis())
        }
    }

    /**
     * Starts (or returns the running) download of [coreId]. Progress and the outcome are published in [state];
     * the Installed/Failed state stays until the next [download] or [delete].
     */
    fun download(coreId: String): Job? {
        val core = registry.core(coreId) ?: return null
        synchronized(jobs) {
            jobs[coreId]?.takeIf { it.isActive }?.let { return it }
            val job = scope.launch { runDownload(core) }
            jobs[coreId] = job
            return job
        }
    }

    private suspend fun runDownload(core: CoreInfo) {
        setState(core.id, DownloadState.Downloading(-1f, 0, 0))
        try {
            val m = refreshManifest()
            val entry = when (m) {
                is ManifestState.Loaded -> m.manifest.entry(core.id)
                    ?: throw DownloadException(context.getString(R.string.core_dl_err_not_in_manifest, core.displayName))
                is ManifestState.Unavailable -> throw DownloadException(m.message)
                else -> throw DownloadException(context.getString(R.string.core_dl_err_network))
            }
            if (entry.minAppVersion.isNotBlank() && UpdateChecker.compareVersions(BuildConfig.VERSION_NAME, entry.minAppVersion) < 0) {
                throw DownloadException(context.getString(R.string.core_dl_err_app_too_old, entry.minAppVersion))
            }
            withContext(Dispatchers.IO) { install(core, entry) }
            setState(core.id, DownloadState.Installed(entry.version))
            Log.i(TAG, "installed ${core.id}@${entry.version} at ${registry.libraryPath(core)}")
        } catch (e: DownloadException) {
            setState(core.id, DownloadState.Failed(e.message ?: ""))
        } catch (e: IOException) {
            Log.w(TAG, "download failed for ${core.id}", e)
            setState(core.id, DownloadState.Failed(context.getString(R.string.core_dl_err_network_during)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            setState(core.id, DownloadState.Idle)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download failed for ${core.id}", e)
            setState(core.id, DownloadState.Failed(context.getString(R.string.core_dl_err_generic, e.message ?: e.javaClass.simpleName)))
        }
    }

    private suspend fun install(core: CoreInfo, entry: CoreManifestEntry) {
        val dir = registry.downloadDir(core).also { it.mkdirs() }
        val tmp = File(dir, TMP_NAME)
        val libFile = entry.libFile.ifBlank { core.libFile }
        try {
            // --- download tmp.zip with progress + sha256 ---
            val req = Request.Builder().url(entry.url).header("User-Agent", "OneEmu").build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw DownloadException(context.getString(R.string.core_dl_err_http, r.code))
                val body = r.body
                val total = if (entry.size > 0) entry.size else body.contentLength()
                var read = 0L
                var lastPublish = 0L
                val buf = ByteArray(128 * 1024)
                body.byteStream().use { input ->
                    tmp.outputStream().buffered(256 * 1024).use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (now - lastPublish > 150) {
                                lastPublish = now
                                setState(core.id, DownloadState.Downloading(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f, read, total))
                            }
                        }
                    }
                }
                if (entry.size > 0 && read != entry.size) throw DownloadException(context.getString(R.string.core_dl_err_size, read, entry.size))
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (entry.sha256.isNotBlank() && !sha.equals(entry.sha256, ignoreCase = true)) {
                throw DownloadException(context.getString(R.string.core_dl_err_sha))
            }
            // --- extract the .so (core.json in the zip is informational only) ---
            setState(core.id, DownloadState.Extracting)
            val part = File(dir, "$libFile.part")
            var found = false
            ZipInputStream(tmp.inputStream().buffered(256 * 1024)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name.substringAfterLast('/') == libFile) {
                        part.outputStream().buffered(256 * 1024).use { out -> zin.copyTo(out) }
                        found = true
                    }
                    zin.closeEntry()
                    if (found) break
                }
            }
            if (!found || part.length() == 0L) { part.delete(); throw DownloadException(context.getString(R.string.core_dl_err_lib_missing, libFile)) }
            val target = File(dir, core.libFile)
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed: ${part.name}")
            target.setReadable(true, false)
            File(dir, CoreRegistry.VERSION_FILE).writeText(entry.version)
        } finally {
            tmp.delete()
        }
    }

    /** Removes the installed library and version file of a downloadable core (no-op for bundled cores). */
    fun delete(coreId: String) {
        val core = registry.core(coreId) ?: return
        if (!core.isDownloadable) return
        synchronized(jobs) { jobs.remove(coreId)?.cancel() }
        registry.downloadDir(core).deleteRecursively()
        _state.update { it - coreId }
        Log.i(TAG, "deleted $coreId")
    }

    private class DownloadException(message: String) : Exception(message)

    companion object {
        private const val TAG = "CoreDownloads"
        const val RELEASE_TAG = "cores"
        const val MANIFEST_NAME = "manifest.json"
        private const val TMP_NAME = "tmp.zip"
        private const val MANIFEST_TTL_MS = 10 * 60 * 1000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
