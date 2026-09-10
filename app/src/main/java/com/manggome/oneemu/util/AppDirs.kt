package com.manggome.oneemu.util

import android.content.Context
import java.io.File

/**
 * Where OneEmu keeps its own files. Everything lives in external app-specific storage so users
 * can reach BIOS/saves with a file manager, but no permission is required.
 *
 * <ext>/Android/data/com.manggome.oneemu/files/
 *   system/        libretro system dir (BIOS files go here; cores may create subfolders)
 *   saves/<sys>/   SRAM (.srm) per system
 *   states/<sys>/<rom-name>/slot<N>.state (+ .png thumbnail)
 *   screenshots/
 *   thumbnails/    user-chosen and auto-extracted game thumbnails
 *   temp/          zip extraction for need_fullpath cores
 */
class AppDirs(private val context: Context) {
    private val root: File get() = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

    val system: File get() = File(root, "system").also { it.mkdirs() }
    val screenshots: File get() = File(root, "screenshots").also { it.mkdirs() }
    val thumbnails: File get() = File(root, "thumbnails").also { it.mkdirs() }
    val temp: File get() = File(context.cacheDir, "temp").also { it.mkdirs() }
    val updates: File get() = File(context.cacheDir, "updates").also { it.mkdirs() }

    fun saves(systemId: String): File = File(root, "saves/$systemId").also { it.mkdirs() }
    fun states(systemId: String, romBaseName: String): File = File(root, "states/$systemId/${sanitize(romBaseName)}").also { it.mkdirs() }

    fun statePath(systemId: String, romBaseName: String, slot: Int): File = File(states(systemId, romBaseName), "slot$slot.state")
    fun stateThumbPath(systemId: String, romBaseName: String, slot: Int): File = File(states(systemId, romBaseName), "slot$slot.png")

    fun clearTemp() { temp.listFiles()?.forEach { it.deleteRecursively() } }

    companion object {
        fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 9
    }
}
