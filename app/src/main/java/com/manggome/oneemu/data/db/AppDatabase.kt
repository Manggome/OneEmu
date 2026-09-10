package com.manggome.oneemu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GameEntity::class, FolderEntity::class, CheatEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun folders(): FolderDao
    abstract fun cheats(): CheatDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "oneemu.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
