package com.manggome.oneemu.library

import android.util.Log
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renames the ROM file itself, and everything the app had filed under its old name with it.
 *
 * Saves are named after the file, not after the library title: a core writes `<saves>/<system>/<rom>.srm`
 * and save states live in `<states>/<system>/<rom>/`. Renaming the ROM and leaving those behind would look
 * exactly like losing the save, so they move too.
 *
 * A .cue or .m3u is renamed on its own: the text inside still names the .bin it always did, which has not
 * moved. The files those sheets point at are deliberately left alone - rewriting a cue sheet to chase a
 * rename is a good way to break a disc image.
 */
object RomRename {
    sealed class Result {
        data class Done(val game: GameEntity, val file: File) : Result()
        /** The new name has nothing usable in it once the characters a file name cannot hold are removed. */
        data object InvalidName : Result()
        data object SourceMissing : Result()
        data class TargetExists(val target: File) : Result()
        data class Failed(val target: File) : Result()
    }

    /**
     * Renames [game]'s file to [newBaseName] (without extension), keeping the extension it has.
     * The library row follows; [title] is written too when given.
     */
    suspend fun apply(
        game: GameEntity,
        newBaseName: String,
        title: String? = null,
        app: OneEmuApp = OneEmuApp.get(),
    ): Result = withContext(Dispatchers.IO) {
        val src = File(game.path)
        if (!src.isFile) return@withContext Result.SourceMissing
        val oldBase = src.nameWithoutExtension
        val dst = targetFor(src, newBaseName) ?: return@withContext Result.InvalidName
        if (dst.absolutePath == src.absolutePath) {
            // Nothing to move; the user may still have changed the title.
            val same = game.copy(title = title?.takeIf { it.isNotBlank() } ?: game.title)
            app.db.games().update(same)
            return@withContext Result.Done(same, src)
        }
        // A case-only change on a case-insensitive volume reports the target as existing; the rename is
        // still what the user asked for, so only a genuinely different file blocks it.
        if (dst.exists() && !dst.absolutePath.equals(src.absolutePath, ignoreCase = true)) {
            return@withContext Result.TargetExists(dst)
        }
        if (!src.renameTo(dst)) return@withContext Result.Failed(dst)

        moveSaveData(app.dirs, game.system, oldBase, dst.nameWithoutExtension)

        val updated = game.copy(
            path = dst.absolutePath,
            title = title?.takeIf { it.isNotBlank() } ?: game.title,
            fileSize = dst.length(),
        )
        app.db.games().update(updated)
        Result.Done(updated, dst)
    }

    /**
     * Where [src] would end up if it were renamed to [newBaseName], or null when nothing usable is left of
     * that name. The extension is kept: it is how the scanner decides which system a file belongs to.
     */
    fun targetFor(src: File, newBaseName: String): File? {
        val base = AppDirs.sanitize(newBaseName.trim()).trim().trim('.').trim()
        if (base.isEmpty()) return null
        return File(src.parentFile, if (src.extension.isEmpty()) base else "$base.${src.extension}")
    }

    /** `<saves>/<system>/<name>.*` and `<states>/<system>/<name>/` follow the ROM. */
    private fun moveSaveData(dirs: AppDirs, systemId: String, oldBase: String, newBase: String) {
        val system = SystemId.fromId(systemId)?.id ?: systemId
        runCatching {
            val saves = dirs.saves(system)
            saves.listFiles()?.forEach { f ->
                if (f.isFile && f.nameWithoutExtension == oldBase) {
                    val target = File(saves, if (f.extension.isEmpty()) newBase else "$newBase.${f.extension}")
                    if (!target.exists() && !f.renameTo(target)) Log.w(TAG, "could not move ${f.name}")
                }
            }
        }.onFailure { Log.w(TAG, "save move failed", it) }
        runCatching {
            val from = dirs.states(system, oldBase)
            val to = dirs.states(system, newBase)
            // states() creates the folder it names, so an empty one at the target is not a collision.
            if (from.isDirectory && from.absolutePath != to.absolutePath) {
                if (to.list()?.isEmpty() != false) {
                    to.delete()
                    if (!from.renameTo(to)) Log.w(TAG, "could not move states $from -> $to")
                } else {
                    Log.w(TAG, "states already exist at $to, leaving $from alone")
                }
            }
        }.onFailure { Log.w(TAG, "state move failed", it) }
    }

    private const val TAG = "RomRename"
}
