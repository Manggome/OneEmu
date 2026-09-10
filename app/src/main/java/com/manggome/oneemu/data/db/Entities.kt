package com.manggome.oneemu.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A ROM the scanner found (or the user added manually). */
@Entity(tableName = "games", indices = [Index(value = ["path"], unique = true), Index("system")])
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute filesystem path. */
    val path: String,
    /** Display title; user-editable. Defaults to a cleaned-up file name or the ROM header title. */
    val title: String,
    /** SystemId.id */
    val system: String,
    /** Overrides the default core for this game; null = system default. */
    val coreId: String? = null,
    /** Custom thumbnail file path or content URI; null = auto (header icon or placeholder). */
    val thumbnail: String? = null,
    /** Auto-extracted icon (e.g. NDS banner) file path, if any. */
    val autoIcon: String? = null,
    val fileSize: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0,
    val playTimeSec: Long = 0,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    /** Which library folder produced this entry; null for manually added files. */
    val folderId: Long? = null,
)

/** A folder the user asked the app to scan. */
@Entity(tableName = "folders", indices = [Index(value = ["path"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val recursive: Boolean = true,
    val lastScannedAt: Long = 0,
)

/** Cheat codes per game. */
@Entity(tableName = "cheats", indices = [Index("gameId")])
data class CheatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val name: String,
    val code: String,
    val enabled: Boolean = true,
)
