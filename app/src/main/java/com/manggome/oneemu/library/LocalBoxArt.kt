package com.manggome.oneemu.library

import android.util.Log
import androidx.room.withTransaction
import com.manggome.oneemu.data.db.AppDatabase
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Pictures the user drops into OneEmu/boxart/ — one per game, named after the game's file:
 * `포켓몬스터 에메랄드(K).png` for `포켓몬스터 에메랄드(K).gba`, or the whole file name with the
 * picture's extension added (`Foo.zip.png`). Subfolders one level down (boxart/GBA/...) count too, so a
 * collection can be kept sorted by system.
 *
 * Such a picture is the game's thumbnail unless the user picked one for that game themselves; it beats
 * the auto icons and box art the server pass downloaded. The thumbnail then simply points at the file in
 * the folder: nothing is copied, and a picture taken out of the folder lets the game fall back again.
 */
class LocalBoxArt(private val dirs: AppDirs, private val db: AppDatabase) {

    /** The picture in the box art folder for [game], if there is one. */
    suspend fun find(game: GameEntity): File? = withContext(Dispatchers.IO) {
        match(index(dirs.boxArtFolder), game.path)
    }

    /**
     * Brings every game's thumbnail in line with the box art folder: matching pictures go in, pictures
     * that left the folder come out. The folder is listed once, so this is cheap enough to run whenever
     * the library is loaded or scanned.
     *
     * @return how many games were given a picture from the folder.
     */
    suspend fun applyAll(): Int = withContext(Dispatchers.IO) {
        val root = dirs.boxArtFolder
        val index = index(root)
        val thumbs = dirs.thumbnails
        val changes = db.games().allOnce().mapNotNull { game ->
            val next = thumbnailFor(game, index, root, thumbs)
            if (next == game.thumbnail) null else game to next
        }
        if (changes.isEmpty()) return@withContext 0
        db.withTransaction {
            for ((game, next) in changes) db.games().setThumbnail(game.id, next)
        }
        // Box art the server pass downloaded is ours to delete once the folder's picture replaces it.
        for ((game, _) in changes) {
            val old = game.thumbnail ?: continue
            if (BoxArtFetcher.isAutoFetched(old, game.id, thumbs)) runCatching { File(old).delete() }
        }
        val applied = changes.count { it.second != null }
        Log.i(TAG, "box art folder: ${changes.size} thumbnails changed ($applied from ${root.absolutePath})")
        applied
    }

    companion object {
        private const val TAG = "LocalBoxArt"

        /** Picture types looked for, lower case. */
        val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        /**
         * The form names are compared in. Android keeps a file name in whatever normalisation it was
         * written with, and names pushed from a Mac (or through adb from one) arrive decomposed (NFD):
         * "에메랄드" as separate jamo, which never equals the composed spelling the scanner saw elsewhere.
         */
        fun key(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        /**
         * Normalised base name -> picture, for every picture in [root] and its direct subfolders. A
         * picture at the top wins over one in a subfolder, then the file name decides, so the choice
         * does not depend on the order the filesystem lists things in.
         */
        fun index(root: File): Map<String, File> {
            val top = root.listFiles().orEmpty().sortedBy { it.name }
            val files = top.filter { it.isFile } +
                top.filter { it.isDirectory }.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name } }
            return indexOf(files)
        }

        /** [index] without the listing: earlier files win over later ones with the same name. */
        fun indexOf(files: List<File>): Map<String, File> {
            val map = LinkedHashMap<String, File>()
            for (f in files) {
                // "._Foo.png" is a Mac's resource-fork twin, not a picture.
                if (f.name.startsWith(".")) continue
                if (f.extension.lowercase(Locale.ROOT) !in EXTENSIONS) continue
                map.putIfAbsent(key(f.nameWithoutExtension), f)
            }
            return map
        }

        /**
         * The picture for the game at [gamePath]: one named like the file without its extension, or
         * like the whole file name ("Foo.zip.png").
         */
        fun match(index: Map<String, File>, gamePath: String): File? {
            if (index.isEmpty()) return null
            val name = gamePath.substringAfterLast('/').substringAfterLast('\\')
            if (name.isEmpty()) return null
            return index[key(name.substringBeforeLast('.'))] ?: index[key(name)]
        }

        /**
         * What [game]'s thumbnail should be given the folder's [index]. Unchanged when the user chose
         * the picture themselves (from the gallery or the box art picker); otherwise the folder's
         * picture, and when there is none, whatever the game had — unless that was a folder picture
         * that has since gone.
         */
        fun thumbnailFor(game: GameEntity, index: Map<String, File>, boxArtRoot: File, thumbnailsDir: File): String? {
            val current = game.thumbnail?.takeIf { it.isNotBlank() }
            val fromFolder = current != null && isInside(current, boxArtRoot)
            val replaceable = current == null || fromFolder || BoxArtFetcher.isAutoFetched(current, game.id, thumbnailsDir)
            if (!replaceable) return game.thumbnail
            match(index, game.path)?.let { return it.absolutePath }
            return if (fromFolder) null else game.thumbnail
        }

        /** True when [path] is a file in [root] or one of its direct subfolders. */
        internal fun isInside(path: String, root: File): Boolean {
            if (path.startsWith("content:")) return false
            val dir = root.absoluteFile
            return generateSequence(File(path).absoluteFile.parentFile) { it.parentFile }.take(2).any { it == dir }
        }
    }
}
