package com.manggome.oneemu.emu

/**
 * Core options whose right value depends on the game rather than the core: changed while a game runs,
 * they are remembered for that game alone instead of for every game on the core.
 *
 * Deinterlacing is the one people meet: a PS2 game that renders interlaced shimmers or looks doubled
 * with one mode and is fine with another, and the next game wants a different one.
 */
object PerGameOptions {
    /** ARMSX2 (PS2) deinterlacing, and PCSX-ReARMed's interlace handling for PS1. */
    val DEINTERLACE = setOf("armsx2_deinterlacing", "pcsx_rearmed_neon_interlace_enable_v2")

    fun isPerGame(key: String): Boolean = key in DEINTERLACE

    /** Word ids for a deinterlace value's Korean label, plus the field order ("TFF"/"BFF") left as is. */
    fun deinterlaceWord(value: String): Pair<DeinterlaceWord, String> {
        val v = value.trim()
        val field = listOf("TFF", "BFF").firstOrNull { v.endsWith(it, ignoreCase = true) }.orEmpty()
        val word = when {
            v.startsWith("auto", ignoreCase = true) || v.equals("Automatic", ignoreCase = true) -> DeinterlaceWord.AUTO
            v.equals("off", ignoreCase = true) || v.equals("disabled", ignoreCase = true) -> DeinterlaceWord.OFF
            v.equals("enabled", ignoreCase = true) || v.equals("on", ignoreCase = true) -> DeinterlaceWord.ON
            v.startsWith("Weave", ignoreCase = true) -> DeinterlaceWord.WEAVE
            v.startsWith("Bob", ignoreCase = true) -> DeinterlaceWord.BOB
            v.startsWith("Blend", ignoreCase = true) -> DeinterlaceWord.BLEND
            v.startsWith("Adaptive", ignoreCase = true) -> DeinterlaceWord.ADAPTIVE
            else -> DeinterlaceWord.OTHER
        }
        return word to field
    }

    enum class DeinterlaceWord { AUTO, OFF, ON, WEAVE, BOB, BLEND, ADAPTIVE, OTHER }
}
