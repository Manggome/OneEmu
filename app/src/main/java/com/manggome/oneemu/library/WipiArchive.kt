package com.manggome.oneemu.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * Feature-phone (WIPI) game packages. Dumps are usually one .zip per game holding a descriptor and the jar:
 * KTF `__adf__` (or `<name>.adf`) + `<AID>.jar`, LGT `app_info` + a jar with `binary.mod`, SKT `<id>.msd` +
 * jar. A zip that only wraps a jar is a plain J2ME game. The core does the real detection; this only decides
 * that a .zip is a phone game rather than a zipped ROM or a MAME set, and finds a library icon.
 */
object WipiArchive {
    enum class Carrier { KTF, LGT, SKT, J2ME }

    /** Carrier from the (any case) entry names of a zip, or null when it is not a phone package. */
    fun detect(names: List<String>): Carrier? {
        val bare = names.map { it.substringAfterLast('/').lowercase() }
        return when {
            bare.any { it == "__adf__" || it.endsWith(".adf") } -> Carrier.KTF
            bare.any { it == "app_info" } -> Carrier.LGT
            bare.any { it.endsWith(".msd") } -> Carrier.SKT
            bare.any { it.endsWith(".jar") } -> Carrier.J2ME
            else -> null
        }
    }

    /** Entries phones used as the menu icon, most detailed first. */
    private val ICON_NAMES = listOf("big.png", "big.icon", "middle.png", "middle.icon", "icon.png", "small.png", "small.icon")

    /** The game's own menu icon, when the package carries one in a format Android can decode. */
    fun icon(file: File): Bitmap? = runCatching {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.size in 1..512_000 }.toList()
            for (name in ICON_NAMES) {
                val entry = entries.firstOrNull { it.name.substringAfterLast('/').equals(name, ignoreCase = true) } ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return@use it }
            }
            null
        }
    }.getOrNull()
}
