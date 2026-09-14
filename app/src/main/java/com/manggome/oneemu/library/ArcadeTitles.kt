package com.manggome.oneemu.library

import android.content.Context

/**
 * MAME short name → full title, from the title tables shipped with each arcade core
 * (coreassets/<coreId>/titles.tsv: name, description, year, manufacturer). Cores are consulted in
 * [ArcadeCoreRouter.CORE_IDS] order, so a name in both DATs gets the MAME 2003-Plus title.
 */
class ArcadeTitles(private val context: Context) {
    data class Entry(val title: String, val year: String, val manufacturer: String)

    private val tables: List<Map<String, Entry>> by lazy { ArcadeCoreRouter.CORE_IDS.map(::load) }

    private fun load(coreId: String): Map<String, Entry> = runCatching {
        context.assets.open("coreassets/$coreId/titles.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size >= 2) p[0] to Entry(p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }) else null
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    fun lookup(shortName: String): Entry? {
        val key = shortName.lowercase()
        for (t in tables) t[key]?.let { return it }
        return null
    }

    /** "Street Fighter II - The World Warrior (World 910522)" → "Street Fighter II - The World Warrior" */
    fun cleanTitle(shortName: String): String? = lookup(shortName)?.title?.let { RomInfo.cleanTitle("$it.zip") }
}
