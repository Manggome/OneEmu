package com.manggome.oneemu.library

import android.util.Log
import com.manggome.oneemu.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Installs MAME 2003-Plus support databases (cheat.dat / hiscore.dat / history.dat) into
 * `<system>/mame2003-plus/`.
 *
 * Primary source is the libretro buildbot "core system files" bundle
 * (`assets/system/MAME 2003-Plus.zip`, ~2.6 MB, verified to contain exactly these three files);
 * if that fails each file is fetched from the core's GitHub `metadata/` folder. Sample zips are not
 * offered: the buildbot bundle's `samples/` folder is empty and there is no public per-game URL.
 */
class ArcadeDatDownloader(
    private val targetDir: File,
    private val client: OkHttpClient = UpdateChecker.defaultClient(),
) {
    sealed class State {
        data object Idle : State()
        /** [progress] 0..1, or -1 while the size is unknown. */
        data class Running(val progress: Float, val source: String) : State()
        data class Done(val files: List<String>) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun installed(): List<String> = DAT_FILES.filter { File(targetDir, it).isFile }

    /** Downloads everything that is not installed yet; returns the installed list. Sets [state] along the way. */
    suspend fun download(): List<String> = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        _state.value = State.Running(-1f, BUNDLE_HOST)
        val fromBundle = runCatching { downloadBundle() }
            .onFailure { Log.w(TAG, "bundle download failed, falling back to GitHub", it) }
            .getOrDefault(emptySet())
        var lastError: Throwable? = null
        for (name in DAT_FILES) {
            if (name in fromBundle || File(targetDir, name).isFile) continue
            runCatching { downloadRaw(name) }.onFailure { lastError = it }
        }
        val result = installed()
        _state.value = if (result.size == DAT_FILES.size || (result.isNotEmpty() && lastError == null)) State.Done(result)
        else State.Failed(lastError?.message ?: "HTTP")
        if (result.isEmpty() && lastError != null) throw IOException(lastError)
        result
    }

    /** Streams the zip and extracts *.dat entries straight into [targetDir]. Returns the file names written. */
    private suspend fun downloadBundle(): Set<String> {
        val request = Request.Builder().url(BUNDLE_URL).header("User-Agent", "OneEmu").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength()
            val counting = CountingStream(body.byteStream()) { read ->
                _state.value = State.Running(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f, BUNDLE_HOST)
            }
            val written = HashSet<String>()
            ZipInputStream(counting).use { zin ->
                while (true) {
                    coroutineContext.ensureActive()
                    val e = zin.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || name !in DAT_FILES) { zin.closeEntry(); continue }
                    val part = File(targetDir, "$name.part")
                    part.outputStream().use { out -> zin.copyTo(out, 64 * 1024) }
                    val target = File(targetDir, name)
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("rename failed: $name")
                    written.add(name)
                    zin.closeEntry()
                }
            }
            Log.i(TAG, "bundle: installed $written into $targetDir")
            return written
        }
    }

    private suspend fun downloadRaw(name: String) {
        _state.value = State.Running(-1f, RAW_HOST)
        val request = Request.Builder().url(RAW_BASE + name).header("User-Agent", "OneEmu").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ($name)")
            val body = response.body
            val total = body.contentLength()
            val part = File(targetDir, "$name.part")
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        _state.value = State.Running(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f, RAW_HOST)
                    }
                }
            }
            val target = File(targetDir, name)
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed: $name")
            Log.i(TAG, "raw: installed $name (${target.length()} bytes)")
        }
    }

    private class CountingStream(private val inner: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun close() = inner.close()
        private fun bump(n: Long) {
            count += n
            if (count - lastReport > 32 * 1024) { lastReport = count; onRead(count) }
        }
    }

    companion object {
        private const val TAG = "ArcadeDatDownloader"
        val DAT_FILES = listOf("cheat.dat", "hiscore.dat", "history.dat")
        const val BUNDLE_URL = "https://buildbot.libretro.com/assets/system/MAME%202003-Plus.zip"
        const val BUNDLE_HOST = "buildbot.libretro.com"
        const val RAW_BASE = "https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/master/metadata/"
        const val RAW_HOST = "raw.githubusercontent.com"
    }
}
