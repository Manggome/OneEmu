package com.manggome.oneemu.emu

import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.RomInfo
import com.manggome.oneemu.model.SystemId
import java.io.File

/**
 * Core options a handful of individual games need to behave, applied as that game's defaults.
 *
 * These sit between the core-wide settings and the per-game ones: the option screen shows the quirk as
 * the value the row falls back to, so it is visible and the user can still override it. A game that
 * matches no rule is untouched.
 */
object GameQuirks {
    data class Quirk(val options: Map<String, String>, val note: String)

    /** The quirk for [game] under [coreId], or null when there is none. */
    fun forGame(coreId: String, game: GameEntity): Quirk? =
        RULES.firstOrNull { it.coreId == coreId && it.matches(game) }?.quirk

    fun optionsFor(coreId: String, game: GameEntity): Map<String, String> =
        forGame(coreId, game)?.options ?: emptyMap()

    private class Rule(
        val coreId: String,
        val system: SystemId,
        /** Header game codes that identify the game outright (NDS: 4 ASCII characters at 0x0C). */
        val gameCodes: Set<String> = emptySet(),
        /** All of these must appear in the title or the file name; empty means "game code only". */
        val words: List<Regex> = emptyList(),
        val quirk: Quirk,
    ) {
        fun matches(game: GameEntity): Boolean {
            if (game.system != system.id) return false
            val file = File(game.path)
            if (gameCodes.isNotEmpty() && file.isFile) {
                val code = runCatching { RomInfo.ndsGameCode(file) }.getOrNull()
                if (code != null && code in gameCodes) return true
            }
            if (words.isEmpty()) return false
            val haystack = "${game.title} ${file.name}".lowercase()
            return words.all { it.containsMatchIn(haystack) }
        }
    }

    private val RULES = listOf(
        // Yu-Gi-Oh! World Championship 2008 shows Korean only when the emulated firmware is set to German;
        // on the default language it comes up in another one. Reported from a real dump, 2026-09-22.
        Rule(
            coreId = "melondsds",
            system = SystemId.NDS,
            gameCodes = setOf("AYWK"),
            words = listOf(Regex("""유희왕|yu[ _-]?gi[ _-]?oh"""), Regex("""(?<!\d)2008(?!\d)""")),
            quirk = Quirk(
                options = mapOf("melonds_firmware_language" to "de"),
                note = "이 게임은 펌웨어 언어가 독일어일 때 한글로 표시되어, 언어를 독일어로 맞춰 둡니다.",
            ),
        ),
    )
}
