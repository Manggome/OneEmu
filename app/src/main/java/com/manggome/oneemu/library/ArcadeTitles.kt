package com.manggome.oneemu.library

import android.content.Context
import java.io.InputStream

/**
 * MAME short name → game title.
 *
 * - Korean: [KOREAN_TABLE] (assets/arcade_titles_ko.tsv), the names Korean players know the games by
 *   ("bublbobl" → "보글보글"). Each line is `name <TAB> 한글 제목 [<TAB> clone clone ...]`; the clone column is
 *   filled by scripts/gen-arcade-titles-ko.py so regional sets share their parent's name. A set with a line of
 *   its own beats the same set in another line's clone column (that is how "테크 로맨서 (미국)" gets its suffix).
 * - English: the title tables shipped with each arcade core (coreassets/<coreId>/titles.tsv: name, description,
 *   year, manufacturer), consulted in [ArcadeCoreRouter.CORE_IDS] order, so a name in both DATs gets the MAME
 *   2003-Plus title.
 *
 * [open] reads an asset path; tests hand in tables of their own.
 */
class ArcadeTitles(private val open: (String) -> InputStream?) {
    constructor(context: Context) : this({ path -> runCatching { context.assets.open(path) }.getOrNull() })

    data class Entry(val title: String, val year: String, val manufacturer: String)

    private val tables: List<Map<String, Entry>> by lazy { ArcadeCoreRouter.CORE_IDS.map(::load) }
    private val korean: Map<String, String> by lazy {
        runCatching { open(KOREAN_TABLE)?.bufferedReader()?.useLines(::parseKorean) }.getOrNull() ?: emptyMap()
    }

    private fun load(coreId: String): Map<String, Entry> = runCatching {
        open("coreassets/$coreId/titles.tsv")?.bufferedReader()?.useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size >= 2) p[0] to Entry(p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }) else null
            }.toMap()
        }
    }.getOrNull() ?: emptyMap()

    fun lookup(shortName: String): Entry? {
        val key = shortName.lowercase()
        for (t in tables) t[key]?.let { return it }
        return null
    }

    /** "Street Fighter II - The World Warrior (World 910522)" → "Street Fighter II - The World Warrior" */
    fun englishTitle(shortName: String): String? = lookup(shortName)?.title?.let { RomInfo.cleanTitle("$it.zip") }

    /** "sf2ua" → "스트리트 파이터 2", or null when the table does not know the game. */
    fun koreanTitle(shortName: String): String? = korean[shortName.lowercase()]

    /** The title a newly scanned game gets: the Korean name when there is one, the English DAT title otherwise. */
    fun cleanTitle(shortName: String): String? = koreanTitle(shortName) ?: englishTitle(shortName)

    /**
     * The Korean title an existing library row should switch to, or null to leave it alone. Only a title the
     * scanner made itself is replaced — the English DAT title, or the cleaned file name from before the DAT
     * titles existed — so a name the user typed stays. [fileName] is the ROM's file name with extension.
     */
    fun localizedTitle(fileName: String, currentTitle: String): String? {
        val shortName = fileName.substringBeforeLast('.')
        val ko = koreanTitle(shortName) ?: return null
        if (currentTitle == ko) return null
        // The English tables are read only here, i.e. only while some row still waits for its Korean name.
        val auto = currentTitle == englishTitle(shortName) || currentTitle == RomInfo.cleanTitle(fileName)
        return ko.takeIf { auto }
    }

    /** Changes whenever the Korean table does, so the library can skip the title migration when nothing is new. */
    fun koreanTableStamp(): Int = korean.hashCode()

    companion object {
        const val KOREAN_TABLE = "arcade_titles_ko.tsv"

        /**
         * Parses [KOREAN_TABLE] lines into short name → Korean title, clones included. Blank lines and `#`
         * comments are skipped, as are lines without a title. A set's own line wins over a clone column.
         */
        fun parseKorean(lines: Sequence<String>): Map<String, String> {
            val own = HashMap<String, String>()
            val viaParent = HashMap<String, String>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                val name = p[0].trim().lowercase()
                val title = p.getOrNull(1)?.trim().orEmpty()
                if (name.isEmpty() || title.isEmpty()) continue
                own[name] = title
                p.getOrNull(2)?.split(' ')?.forEach { c ->
                    val clone = c.trim().lowercase()
                    if (clone.isNotEmpty()) viaParent.putIfAbsent(clone, title)
                }
            }
            return viaParent + own
        }

        @Volatile private var instance: ArcadeTitles? = null
        fun get(context: Context): ArcadeTitles = instance ?: synchronized(this) {
            instance ?: ArcadeTitles(context.applicationContext).also { instance = it }
        }
    }
}
