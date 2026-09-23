package com.manggome.oneemu.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Where OneEmu keeps its own files.
 *
 * Everything the user ever needs to find — saves, save states, BIOS, screenshots — lives together
 * in one folder at the top of internal storage:
 *
 * ```
 * /sdcard/OneEmu/
 *   saves/<sys>/                 battery saves, memory cards, and whatever else a core writes
 *   states/<sys>/<rom>/slotN.state (+ .png thumbnail)
 *   system/                      libretro system dir; BIOS goes here, cores may add subfolders
 *   screenshots/
 *   thumbnails/                  box art and icons the library shows
 *   skins/                       pad skins the user imported
 * ```
 *
 * It used to sit under `Android/data/<package>/files`, which Android 11 put out of reach of most
 * file managers — the very place a user has to copy BIOS into. Existing installs are moved across
 * once, on the first run that has permission. Without "모든 파일 접근" the old location is still used,
 * since nothing outside it would be readable anyway.
 */
class AppDirs(private val context: Context) {
    /** Resolved once per process: flipping mid-session would strand files half in each place. */
    private val root: File by lazy { resolveRoot() }

    val system: File get() = dir("system")
    val screenshots: File get() = dir("screenshots")
    val thumbnails: File get() = dir("thumbnails")
    val skins: File get() = dir("skins")
    val temp: File get() = File(context.cacheDir, "temp").also { it.mkdirs() }
    val updates: File get() = File(context.cacheDir, "updates").also { it.mkdirs() }

    /** Cached listings of the libretro thumbnail server, so box art lookups stay cheap. */
    val boxArtIndex: File get() = File(context.cacheDir, "boxart").also { it.mkdirs() }

    fun saves(systemId: String): File = dir("saves/$systemId")

    /** One of the save-data folders by name ("saves" / "states"), for backup and restore. */
    fun saveRoot(name: String): File = dir(name)

    /** The one folder to point a user at; everything of theirs is under it. */
    val dataPath: String get() = root.absolutePath

    /** Shown in the settings so the folder can be found with a file manager. */
    val savesPath: String get() = File(root, "saves").absolutePath

    fun states(systemId: String, romBaseName: String): File = dir("states/$systemId/${sanitize(romBaseName)}")

    fun statePath(systemId: String, romBaseName: String, slot: Int): File = File(states(systemId, romBaseName), "slot$slot.state")
    fun stateThumbPath(systemId: String, romBaseName: String, slot: Int): File = File(states(systemId, romBaseName), "slot$slot.png")

    fun clearTemp() { temp.listFiles()?.forEach { it.deleteRecursively() } }

    private fun dir(relative: String): File = File(root, relative).also { d ->
        d.mkdirs()
        // Created with the folder, so the first picture written there is already hidden.
        GALLERY_HIDDEN.firstOrNull { relative == it || relative.startsWith("$it/") }?.let { top ->
            runCatching { File(File(root, top), NO_MEDIA).createNewFile() }
        }
    }

    /**
     * Keeps the app's own pictures out of the phone's gallery.
     *
     * Moving everyone's data to /sdcard/OneEmu put it where the media scanner looks, and it indexes every
     * image it finds: the box art and banner icons in thumbnails/, every image of every pad skin, and the
     * little screenshot saved next to each save state. A user opens the gallery and finds it full of
     * pictures from their ROMs. A `.nomedia` file tells the scanner to skip a folder; the files it had
     * already indexed are handed back to it so it drops them too. Screenshots taken on purpose are not
     * affected - 스크린샷 copies them into Pictures/OneEmu, which stays visible.
     *
     * @return how many already-indexed files were handed to the scanner.
     */
    fun hideFromGallery(): Int {
        // Its own once-only record rather than "is .nomedia there yet": dir() drops that marker the
        // moment any of these folders is touched, which on an existing install happens before this runs,
        // and the pictures the scanner had already indexed would then never be handed back.
        val done = File(root, RESCANNED)
        if (done.exists()) return 0
        val stale = ArrayList<String>()
        for (top in GALLERY_HIDDEN) {
            val folder = File(root, top)
            if (!folder.isDirectory) continue
            runCatching { File(folder, NO_MEDIA).createNewFile() }
            folder.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                .take(MAX_RESCAN)
                .mapTo(stale) { it.absolutePath }
        }
        if (stale.isNotEmpty()) {
            runCatching { android.media.MediaScannerConnection.scanFile(context, stale.toTypedArray(), null, null) }
                .onFailure { Log.w(TAG, "rescan failed", it) }
        }
        runCatching { done.createNewFile() }
        return stale.size
    }

    /** The visible folder when it is usable, otherwise the app-private one we started out with. */
    private fun resolveRoot(): File {
        val legacy = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
        if (!StorageAccess.hasAllFilesAccess()) return legacy
        val visible = File(Environment.getExternalStorageDirectory(), PUBLIC_DIR)
        val usable = runCatching { visible.mkdirs(); visible.isDirectory && visible.canWrite() }.getOrDefault(false)
        if (!usable) {
            Log.w(TAG, "cannot use $visible, staying in $legacy")
            return legacy
        }
        migrate(legacy, visible)
        return visible
    }

    /**
     * Moves each folder across once, copying rather than renaming: scoped storage puts the private
     * directory and the visible one on different mounts, so a rename only ever fails with EXDEV.
     *
     * A folder already present at the destination is left alone rather than merged, and a copy that
     * does not complete leaves the original untouched — the worst case is the old location keeping
     * the data, never losing it.
     */
    private fun migrate(from: File, to: File) = migrateFolders(from, to, MIGRATED)



    companion object {
        private const val TAG = "AppDirs"
        /** Folders holding pictures that are the app's business, not the user's photos. */
        internal val GALLERY_HIDDEN = listOf("thumbnails", "skins", "states", "screenshots")
        private const val NO_MEDIA = ".nomedia"
        /** Written once the already-indexed pictures have been handed back to the media scanner. */
        private const val RESCANNED = ".gallery-hidden"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private const val MAX_RESCAN = 5000

        /**
         * Moves each of [names] from [from] to [to], returning the ones that were moved.
         *
         * Copies rather than renames: scoped storage puts the private directory and the visible one
         * on different mounts, so a rename only ever fails with EXDEV. A folder already present at
         * the destination is left alone rather than merged, and a copy that does not complete
         * leaves the original untouched — the worst case is the old location keeping the data,
         * never losing it.
         *
         * [log] is a parameter so a test can exercise this without android.util.Log.
         */
        internal fun migrateFolders(
            from: File,
            to: File,
            names: List<String>,
            log: (String) -> Unit = { Log.i(TAG, it) },
        ): List<String> {
            val moved = ArrayList<String>()
            for (name in names) {
                val src = File(from, name)
                if (!src.isDirectory || src.list().isNullOrEmpty()) continue
                val dst = File(to, name)
                if (dst.exists()) continue
                val bytes = runCatching { src.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
                if (!runCatching { src.copyRecursively(dst, overwrite = false); true }.getOrDefault(false)) {
                    log("could not move $name (${bytes / 1024} KB); leaving it in $src")
                    runCatching { dst.deleteRecursively() }
                    continue
                }
                runCatching { src.deleteRecursively() }
                log("moved $name (${bytes / 1024} KB) -> $dst")
                moved += name
            }
            return moved
        }

        /** Top-level folder name on internal storage. */
        const val PUBLIC_DIR = "OneEmu"

        /** Folders carried over from the old app-private location. */
        private val MIGRATED = listOf("saves", "states", "system", "screenshots", "thumbnails", "skins")

        fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 9
    }
}
