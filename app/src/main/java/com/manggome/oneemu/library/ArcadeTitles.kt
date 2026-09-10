package com.manggome.oneemu.library

import android.content.Context

/**
 * MAME short name → full title, from the MAME 2003-Plus DAT shipped as an asset
 * (coreassets/mame2003plus/titles.tsv: name, description, year, manufacturer).
 */
class ArcadeTitles(private val context: Context) {
    data class Entry(val title: String, val year: String, val manufacturer: String)

    private val table: Map<String, Entry> by lazy {
        runCatching {
            context.assets.open("coreassets/mame2003plus/titles.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) p[0] to Entry(p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }) else null
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    fun lookup(shortName: String): Entry? = table[shortName.lowercase()]

    /** "Street Fighter II - The World Warrior (World 910522)" → "Street Fighter II - The World Warrior" */
    fun cleanTitle(shortName: String): String? = lookup(shortName)?.title?.let { RomInfo.cleanTitle("$it.zip") }
}
