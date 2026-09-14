package com.manggome.oneemu.library

import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.db.GameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies a [ArcadeRomCheck.Status.RENAME_SUGGESTED] result: renames the zip in place to the DAT short name
 * and updates the library row (path, and the title when the DAT knows the game). The caller confirms first.
 */
object ArcadeRename {
    sealed class Result {
        data class Done(val game: GameEntity, val newFile: File) : Result()
        data object SourceMissing : Result()
        data class TargetExists(val target: File) : Result()
        data class Failed(val target: File) : Result()
    }

    suspend fun apply(game: GameEntity, res: ArcadeRomCheck.Resolution, app: OneEmuApp = OneEmuApp.get()): Result = withContext(Dispatchers.IO) {
        val newName = res.report.suggestedName ?: return@withContext Result.SourceMissing
        val src = File(game.path)
        if (!src.isFile) return@withContext Result.SourceMissing
        val dst = File(src.parentFile, "$newName.${src.extension.ifEmpty { "zip" }}")
        if (dst.exists()) return@withContext Result.TargetExists(dst)
        if (!src.renameTo(dst)) return@withContext Result.Failed(dst)

        val checker = ArcadeRomChecker.get(app)
        val title = res.coreId?.let { checker.db(it)[newName]?.description }?.let { RomInfo.cleanTitle("$it.zip") }?.takeIf { it.isNotBlank() }
        val updated = game.copy(path = dst.absolutePath, title = title ?: game.title, fileSize = dst.length())
        app.db.games().update(updated)
        checker.invalidate(game.path)
        Result.Done(updated, dst)
    }
}
