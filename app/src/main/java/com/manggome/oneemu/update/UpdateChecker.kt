package com.manggome.oneemu.update

import android.util.Log
import com.manggome.oneemu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub for the latest release of Manggome/OneEmu and decides whether it is newer than the
 * running build. Release tags are `v<versionName>` (e.g. v0.1.42) and versionName is 0.1.<commit count>.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val repo: String = BuildConfig.GITHUB_REPO,
) {
    sealed interface Result {
        data class Available(val info: UpdateInfo) : Result
        data object UpToDate : Result
        data class Failed(val message: String, val cause: Throwable? = null) : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OneEmu")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.Failed("HTTP ${response.code}")
                val body = response.body.string()
                val release = json.decodeFromString<GitHubRelease>(body)
                evaluate(release, currentVersion)
            }
        } catch (e: IOException) {
            Log.i(TAG, "update check failed (network): ${e.message}")
            Result.Failed(e.message ?: "network error", e)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    internal fun evaluate(release: GitHubRelease, currentVersion: String): Result {
        if (release.draft) return Result.UpToDate
        val remote = release.tagName.removePrefix("v").trim()
        if (remote.isEmpty()) return Result.Failed("empty tag")
        if (!isNewer(remote, currentVersion)) return Result.UpToDate
        val apk = pickApk(release.assets) ?: return Result.UpToDate
        return Result.Available(
            UpdateInfo(
                version = remote,
                notes = release.body?.trim().orEmpty(),
                apkUrl = apk.browserDownloadUrl,
                apkSize = apk.size,
                htmlUrl = release.htmlUrl,
            ),
        )
    }

    companion object {
        private const val TAG = "UpdateChecker"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** "0.1.42-debug" -> [0, 1, 42]. Non-numeric segments count as 0. */
        fun parseVersion(v: String): List<Int> =
            v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
                .split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        /** Numeric segment-by-segment comparison; missing segments are 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = parseVersion(a)
            val pb = parseVersion(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

        internal fun pickApk(assets: List<GitHubAsset>): GitHubAsset? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank() }
            return apks.firstOrNull { it.name.contains("arm64", ignoreCase = true) }
                ?: apks.firstOrNull { it.name.contains("release", ignoreCase = true) }
                ?: apks.firstOrNull()
        }
    }
}
