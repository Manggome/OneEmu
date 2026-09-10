package com.manggome.oneemu.core

import android.content.Context
import com.manggome.oneemu.model.SystemId
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Knows which libretro cores are bundled in this APK (from the JSON files in assets/cores/) and where
 * their .so files live. Cores whose .so is missing from the APK are reported but marked unavailable.
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

    /** Default core for a system: the first bundled one whose library is present. */
    fun defaultCoreFor(system: SystemId): CoreInfo? =
        coresFor(system).firstOrNull { isAvailable(it) } ?: coresFor(system).firstOrNull()

    fun libraryPath(core: CoreInfo): File = File(context.applicationInfo.nativeLibraryDir, core.libFile)

    fun isAvailable(core: CoreInfo): Boolean = libraryPath(core).exists()

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

    private fun versionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0" }.getOrDefault("0")
}
