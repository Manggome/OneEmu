package com.manggome.oneemu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE hidden = 0 ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE hidden = 0 AND lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: Long): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE path = :path")
    suspend fun getByPath(path: String): GameEntity?

    @Query("SELECT * FROM games")
    suspend fun allOnce(): List<GameEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(game: GameEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameEntity>): List<Long>

    @Update
    suspend fun update(game: GameEntity)

    @Delete
    suspend fun delete(game: GameEntity)

    @Query("DELETE FROM games WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM games WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    @Query("UPDATE games SET lastPlayedAt = :at, playTimeSec = playTimeSec + :addSec WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long, addSec: Long)

    @Query("UPDATE games SET thumbnail = :thumbnail WHERE id = :id")
    suspend fun setThumbnail(id: Long, thumbnail: String?)

    @Query("UPDATE games SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE games SET coreId = :coreId WHERE id = :id")
    suspend fun setCore(id: Long, coreId: String?)

    @Query("UPDATE games SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE games SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY path")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun allOnce(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)
}

@Dao
interface CheatDao {
    @Query("SELECT * FROM cheats WHERE gameId = :gameId ORDER BY id")
    fun observeForGame(gameId: Long): Flow<List<CheatEntity>>

    @Query("SELECT * FROM cheats WHERE gameId = :gameId ORDER BY id")
    suspend fun forGame(gameId: Long): List<CheatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cheat: CheatEntity): Long

    @Delete
    suspend fun delete(cheat: CheatEntity)
}
