package com.manggome.oneemu.emu.skin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** `skin.json` inside a bundled skin folder. Imported skins may omit it; the store then infers everything. */
@Serializable
data class SkinMeta(
    val id: String,
    val name: String,
    val systems: List<String> = emptyList(),
    val author: String = "",
    val license: String = "",
    val source: String? = null,
    val portraitCfg: String? = null,
    val landscapeCfg: String? = null,
)

/** One installed skin: either an APK asset folder (`assets/skins/<dir>`) or an imported folder under `skins/`. */
data class SkinInfo(
    val id: String,
    val name: String,
    val systems: Set<String>,
    val author: String,
    val license: String,
    val source: String?,
    val portraitCfg: String,
    val landscapeCfg: String,
    val assetDir: String?,
    val fileDir: File?,
) {
    val builtIn: Boolean get() = assetDir != null
    val neutral: Boolean get() = systems.isEmpty()
    fun suits(system: SystemId): Boolean = system.id in systems

    /** Opens a skin-relative path (cfg or image). */
    fun open(context: Context, relPath: String): InputStream = when {
        assetDir != null -> context.assets.open("$assetDir/$relPath")
        fileDir != null -> File(fileDir, relPath).inputStream()
        else -> throw FileNotFoundException(relPath)
    }
}

/** User edits for one (skin, system, orientation): per-desc offsets (normalized to the screen), scale, visibility. */
@Serializable
data class DescEdit(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val visible: Boolean = true)

@Serializable
data class SkinLayout(val items: Map<String, DescEdit> = emptyMap()) {
    operator fun get(overlay: Overlay, desc: OverlayDesc): DescEdit? = items[key(overlay, desc)]

    fun update(overlay: Overlay, desc: OverlayDesc, transform: (DescEdit) -> DescEdit): SkinLayout =
        copy(items = items + (key(overlay, desc) to transform(items[key(overlay, desc)] ?: DescEdit())))

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val EMPTY = SkinLayout()
        fun key(overlay: Overlay, desc: OverlayDesc) = "${overlay.name}#${desc.index}"
        fun fromJson(text: String): SkinLayout? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Skin discovery, per-system selection and per-skin layout persistence.
 *
 * Settings keys owned here: `skin.<systemId>` = skin id or [VECTOR]; `skin_layout.<skinId>.<systemId>.<port|land>` = [SkinLayout] JSON.
 * Imported skins live in `<externalFilesDir>/skins/<name>/`.
 */
object SkinStore {
    const val VECTOR = "vector"
    private const val ASSET_ROOT = "skins"
    private const val USER_PREFIX = "user:"

    object Keys {
        fun selected(systemId: String) = stringPreferencesKey("skin.$systemId")
        fun layout(skinId: String, systemId: String, landscape: Boolean) =
            stringPreferencesKey("skin_layout.$skinId.$systemId.${if (landscape) "land" else "port"}")
    }

    private val settings get() = OneEmuApp.get().settings
    private val json = Json { ignoreUnknownKeys = true }

    private var bundledCache: List<SkinInfo>? = null
    private val importedFlow = MutableStateFlow<List<SkinInfo>?>(null)

    fun userDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "skins").also { it.mkdirs() }

    /** Default skin for a system when the user never chose one. */
    fun defaultSkinId(system: SystemId): String = when (system) {
        SystemId.NES -> "nes-classic"
        SystemId.GB, SystemId.GBC -> "gameboy-classic"
        SystemId.GBA -> "gba-classic"
        SystemId.NDS -> "lite-nds"
        SystemId.N3DS -> "flat-3ds"
        SystemId.PSP -> "flat-psp"
        SystemId.PS2 -> "dualshock-classic"
        SystemId.ARCADE -> "arcade-classic"
    }

    // ---- discovery ----

    suspend fun bundled(context: Context): List<SkinInfo> = bundledCache ?: withContext(Dispatchers.IO) {
        val am = context.assets
        val dirs = runCatching { am.list(ASSET_ROOT)?.toList() }.getOrNull().orEmpty()
        dirs.mapNotNull { dir ->
            val metaText = runCatching { am.open("$ASSET_ROOT/$dir/skin.json").bufferedReader().readText() }.getOrNull() ?: return@mapNotNull null
            val meta = runCatching { json.decodeFromString(SkinMeta.serializer(), metaText) }.getOrNull() ?: return@mapNotNull null
            val cfgs = am.list("$ASSET_ROOT/$dir")?.filter { it.endsWith(".cfg", true) }.orEmpty()
            val port = meta.portraitCfg ?: cfgs.firstOrNull() ?: return@mapNotNull null
            SkinInfo(
                id = meta.id.ifBlank { dir }, name = meta.name, systems = meta.systems.toSet(), author = meta.author,
                license = meta.license, source = meta.source, portraitCfg = port, landscapeCfg = meta.landscapeCfg ?: port,
                assetDir = "$ASSET_ROOT/$dir", fileDir = null,
            )
        }.sortedBy { it.name }.also { bundledCache = it }
    }

    /** Imported skins; refreshed by [refreshImported] and after import/delete. */
    fun imported(): StateFlow<List<SkinInfo>?> = importedFlow

    suspend fun refreshImported(context: Context): List<SkinInfo> = withContext(Dispatchers.IO) {
        val list = userDir(context).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { describeUserDir(it) }.sortedBy { it.name }
        importedFlow.value = list
        list
    }

    suspend fun all(context: Context): List<SkinInfo> = bundled(context) + (importedFlow.value ?: refreshImported(context))

    suspend fun find(context: Context, id: String): SkinInfo? = all(context).firstOrNull { it.id == id }

    private fun describeUserDir(dir: File): SkinInfo? {
        val cfgs = dir.walkTopDown().maxDepth(2).filter { it.isFile && it.extension.equals("cfg", true) }
            .map { it.relativeTo(dir).path.replace('\\', '/') }.sorted().toList()
        if (cfgs.isEmpty()) return null
        val meta = File(dir, "skin.json").takeIf { it.exists() }?.let { f -> runCatching { json.decodeFromString(SkinMeta.serializer(), f.readText()) }.getOrNull() }
        var port = meta?.portraitCfg?.takeIf { File(dir, it).exists() }
        var land = meta?.landscapeCfg?.takeIf { File(dir, it).exists() }
        if (port == null || land == null) {
            // Infer from content: a cfg that has both orientations wins, otherwise best per orientation.
            val parsed = cfgs.associateWith { rel -> runCatching { OverlayCfgParser.parse(File(dir, rel).readText(), rel.substringBeforeLast('/', "")) }.getOrNull() }
            fun best(o: OverlayOrientation) = parsed.entries.firstOrNull { (_, c) -> c?.overlays?.any { it.orientation == o && it.hasPadButtons } == true }?.key
            port = port ?: best(OverlayOrientation.PORTRAIT) ?: cfgs.first()
            land = land ?: best(OverlayOrientation.LANDSCAPE) ?: port
        }
        return SkinInfo(
            id = USER_PREFIX + dir.name,
            name = meta?.name?.ifBlank { null } ?: dir.name,
            systems = meta?.systems?.toSet().orEmpty(),
            author = meta?.author.orEmpty(),
            license = meta?.license.orEmpty(),
            source = meta?.source,
            portraitCfg = port!!, landscapeCfg = land!!,
            assetDir = null, fileDir = dir,
        )
    }

    // ---- selection ----

    /** Raw selection: skin id or [VECTOR]. Unset falls back to the console-appropriate bundled skin. */
    fun observeSelected(systemId: String): Flow<String> =
        settings.observe(Keys.selected(systemId), "").map { it.ifBlank { SystemId.fromId(systemId)?.let(::defaultSkinId) ?: VECTOR } }

    /** Resolved selection; emits [SkinSelection.Vector] when the id is unknown (e.g. a deleted import). */
    fun observeSelectedSkin(context: Context, system: SystemId): Flow<SkinSelection> =
        combine(observeSelected(system.id), importedFlow) { id, _ -> id }.map { id ->
            if (id == VECTOR) SkinSelection.Vector else find(context, id)?.let { SkinSelection.Skin(it) } ?: SkinSelection.Vector
        }

    suspend fun select(systemId: String, skinId: String) = settings.set(Keys.selected(systemId), skinId)

    // ---- layouts ----

    fun observeLayout(skinId: String, systemId: String, landscape: Boolean): Flow<SkinLayout> =
        settings.observe(Keys.layout(skinId, systemId, landscape), "").map { it.takeIf(String::isNotBlank)?.let(SkinLayout::fromJson) ?: SkinLayout.EMPTY }

    suspend fun loadLayout(skinId: String, systemId: String, landscape: Boolean): SkinLayout =
        settings.get(Keys.layout(skinId, systemId, landscape), "").takeIf(String::isNotBlank)?.let(SkinLayout::fromJson) ?: SkinLayout.EMPTY

    suspend fun saveLayout(skinId: String, systemId: String, landscape: Boolean, layout: SkinLayout) =
        settings.set(Keys.layout(skinId, systemId, landscape), layout.toJson())

    suspend fun resetLayout(skinId: String, systemId: String, landscape: Boolean) = settings.remove(Keys.layout(skinId, systemId, landscape))

    // ---- import / delete ----

    class ImportException(message: String) : Exception(message)

    /** Imports a `.zip` RetroArch overlay (cfg + images). A single top-level folder inside the zip is flattened. */
    suspend fun importZip(context: Context, uri: Uri): SkinInfo = withContext(Dispatchers.IO) {
        val display = queryDisplayName(context, uri)?.substringBeforeLast('.') ?: "skin"
        val target = uniqueDir(context, display)
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (entry.isDirectory || name.startsWith("__MACOSX") || name.substringAfterLast('/').startsWith(".")) { zip.closeEntry(); continue }
                        val out = File(target, name).canonicalFile
                        if (!out.path.startsWith(target.canonicalPath + File.separator)) { zip.closeEntry(); continue } // zip-slip
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                    }
                }
            } ?: throw ImportException("open failed")
            flattenSingleDir(target)
            finishImport(context, target)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
    }

    /** Imports a folder chosen with ACTION_OPEN_DOCUMENT_TREE by copying it (nested folders included). */
    suspend fun importTree(context: Context, treeUri: Uri): SkinInfo = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw ImportException("tree")
        val target = uniqueDir(context, root.name ?: "skin")
        try {
            copyTree(context, root, target, depth = 0)
            finishImport(context, target)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
    }

    suspend fun delete(context: Context, info: SkinInfo) {
        if (info.builtIn) return
        withContext(Dispatchers.IO) { info.fileDir?.deleteRecursively() }
        SkinLoader.evict(info.id)
        refreshImported(context)
    }

    private suspend fun finishImport(context: Context, target: File): SkinInfo {
        val info = describeUserDir(target) ?: throw ImportException("no cfg")
        // Make sure at least one cfg parses to something with buttons.
        val cfg = runCatching { OverlayCfgParser.parse(File(target, info.portraitCfg).readText(), info.portraitCfg.substringBeforeLast('/', "")) }.getOrNull()
        if (cfg == null || cfg.overlays.none { it.hasPadButtons }) throw ImportException("invalid cfg")
        refreshImported(context)
        return info
    }

    private fun copyTree(context: Context, dir: DocumentFile, target: File, depth: Int) {
        if (depth > 3) return
        target.mkdirs()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) copyTree(context, child, File(target, AppDirs.sanitize(name)), depth + 1)
            else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input -> File(target, AppDirs.sanitize(name)).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun flattenSingleDir(target: File) {
        val children = target.listFiles().orEmpty()
        val only = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        for (f in only.listFiles().orEmpty()) f.renameTo(File(target, f.name))
        only.delete()
    }

    private fun uniqueDir(context: Context, name: String): File {
        val base = AppDirs.sanitize(name.trim().ifBlank { "skin" }).take(60)
        var dir = File(userDir(context), base)
        var n = 2
        while (dir.exists()) { dir = File(userDir(context), "$base ($n)"); n++ }
        dir.mkdirs()
        return dir
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

sealed interface SkinSelection {
    data object Loading : SkinSelection
    data object Vector : SkinSelection
    data class Skin(val info: SkinInfo) : SkinSelection
}
