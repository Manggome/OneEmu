package com.manggome.oneemu.update

import android.util.Log
import com.manggome.oneemu.util.AppDirs
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
import kotlin.coroutines.coroutineContext

/**
 * Streams a release APK into `<cache>/updates/OneEmu-<version>.apk` (exposed via FileProvider path
 * "updates"). Progress is 0..1; -1 while the total size is unknown.
 */
class UpdateDownloader(
    private val dirs: AppDirs,
    private val client: OkHttpClient = UpdateChecker.defaultClient(),
) {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun fileFor(info: UpdateInfo): File = File(dirs.updates, "OneEmu-${info.version}.apk")

    /** Returns the downloaded, size-verified file. Throws IOException on failure. */
    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val target = fileFor(info)
        // Drop old downloads (and any half-finished .part) so the cache never accumulates APKs.
        dirs.updates.listFiles()?.forEach { if (it != target) it.delete() }
        if (target.exists() && info.apkSize > 0 && target.length() == info.apkSize) {
            _progress.value = 1f
            return@withContext target
        }
        target.delete()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        _progress.value = 0f

        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "OneEmu").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = if (info.apkSize > 0) info.apkSize else body.contentLength()
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
                        _progress.value = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
                    }
                }
            }
            if (info.apkSize > 0 && read != info.apkSize) {
                part.delete()
                throw IOException("size mismatch: expected ${info.apkSize}, got $read")
            }
        }
        if (!part.renameTo(target)) throw IOException("rename failed")
        _progress.value = 1f
        Log.i(TAG, "downloaded ${target.absolutePath} (${target.length()} bytes)")
        target
    }

    companion object { private const val TAG = "UpdateDownloader" }
}
