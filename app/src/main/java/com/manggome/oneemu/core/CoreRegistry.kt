package com.manggome.oneemu.core

import android.content.Context
import com.manggome.oneemu.model.SystemId
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Knows which libretro cores this APK knows about (from the JSON files in assets/cores/) and where their .so
 * files live: bundled cores in the APK's native folder, `distribution: download` cores under
 * <filesDir>/cores/<id>/. Cores whose .so is missing are reported but marked unavailable.
 */
class CoreRegistry(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val cores: List<CoreInfo> by lazy {
        val am = context.assets
        (am.list("cores") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                runCatching { am.open("cores/$name").bufferedReader().use { json.decodeFromString<CoreInfo>(it.readText()) } }.getOrNull()
            }
            .sortedBy { it.id }
    }

    fun core(id: String): CoreInfo? = cores.firstOrNull { it.id == id }

    fun coresFor(system: SystemId): List<CoreInfo> = cores.filter { system.id in it.systems }

    /** User-chosen default core ids per system (Settings.Keys.coreForSystem), kept in sync by [OneEmuApp]. */
    @Volatile var preferredCoreIds: Map<String, String> = emptyMap()

    /** Default core for a system: the user's choice if valid and present, else the first bundled one whose library is present. */
    fun defaultCoreFor(system: SystemId): CoreInfo? {
        val candidates = coresFor(system)
        preferredCoreIds[system.id]?.let { id -> candidates.firstOrNull { it.id == id && isAvailable(it) }?.let { return it } }
        return candidates.firstOrNull { isAvailable(it) } ?: candidates.firstOrNull()
    }

    /** Where a downloadable core is installed: `<filesDir>/cores/<id>/` (the .so plus `version.txt`). */
    fun downloadDir(core: CoreInfo): File = File(File(context.filesDir, "cores"), core.id)

    /**
     * Absolute path the frontend dlopens: the APK's native folder for bundled cores, [downloadDir] for
     * `distribution: download` cores (present only after [CoreDownloadManager] installed them).
     */
    fun libraryPath(core: CoreInfo): File =
        if (core.isDownloadable) File(downloadDir(core), core.libFile) else File(context.applicationInfo.nativeLibraryDir, core.libFile)

    fun isAvailable(core: CoreInfo): Boolean = libraryPath(core).exists()

    /** Installed version (manifest `version`, i.e. sourceCommit[0..12]) of a downloadable core, from `version.txt`; null when absent. */
    fun installedVersion(core: CoreInfo): String? {
        if (!core.isDownloadable || !isAvailable(core)) return null
        return runCatching { File(downloadDir(core), VERSION_FILE).readText().trim().ifEmpty { null } }.getOrNull()
    }

    /** All file extensions (lowercase, no dot) any bundled core can open, mapped to candidate systems. */
    fun extensionToSystems(): Map<String, List<SystemId>> {
        val map = mutableMapOf<String, MutableList<SystemId>>()
        for (core in cores) for (ext in core.extensions) for (sys in core.systems) {
            SystemId.fromId(sys)?.let { s -> map.getOrPut(ext.lowercase()) { mutableListOf() }.let { if (s !in it) it.add(s) } }
        }
        // Systems without a bundled core still get their extensions so the library can show them as "코어 없음".
        for (s in SystemId.entries) for (ext in s.extensions) map.getOrPut(ext) { mutableListOf() }.let { if (s !in it) it.add(s) }
        return map
    }

    /**
     * Copies everything under coreassets/<id>/ from the APK into <systemDir>/<assetsInstallDir> once per app version.
     * Some cores (PPSSPP) need shader/font assets on disk.
     */
    fun installAssets(core: CoreInfo, systemDir: File) {
        if (core.assetsInstallDir.isEmpty()) return
        val target = File(systemDir, core.assetsInstallDir)
        val stamp = File(target, ".oneemu-assets-${versionName()}")
        if (stamp.exists()) return
        copyAssetDir("coreassets/${core.id}", target)
        target.mkdirs()
        stamp.writeText("ok")
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val am = context.assets
        val children = am.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (c in children) copyAssetDir("$assetPath/$c", File(target, c))
    }

    companion object { const val VERSION_FILE = "version.txt" }

    private fun versionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0" }.getOrDefault("0")
}
