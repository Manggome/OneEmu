package com.manggome.oneemu.emu

import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.RomInfo
import com.manggome.oneemu.model.SystemId
import java.io.File

/**
 * Core options a handful of individual games need to behave, applied as that game's defaults.
 *
 * These sit on top of core.json's defaults and below anything the user set, core-wide or for the game:
 * the option screen shows the quirk as the value the row falls back to, so it is visible and the user
 * can still override it. A game that matches no rule is untouched.
 *
 * PS2 discs have no game code the library reads yet, so PS2 rules match the title or file name (which
 * can be the disc serial). Matching the serial inside the image would need RomInfo to read SYSTEM.CNF
 * from the ISO9660 (or CHD/CSO) image - `BOOT2 = cdrom0:\SLKA_253.29;1`.
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

    // DW4_XL_* are declared before RULES on purpose: an object initialises its properties in order.

    /**
     * Shin Sangoku Musou 4 Moushouden / Dynasty Warriors 4 Xtreme Legends (Korean release 진・삼국무쌍 4
     * 맹장전, SLKA-25329) was reported very slow on ARMSX2. The game is heavy on the EE (dozens of
     * soldiers on screen) and its GameDB entry turns on full auto flush (`autoFlush: 2`, "fixes sun
     * luminosity"), which costs a render pass per overlapping draw on a phone GPU. Auto flush cannot be
     * changed through a core option, so the preset takes the levers that exist: the EE one step slower
     * and its mildest cycle skip (the game draws fewer frames, so the GS has less to do as well), and the
     * cheapest blending level - the same levers ARMSX2's own "Low-End" preset pulls (EE cycle skip 1,
     * blending Minimum). Everything here is a speed hack: the game may run at a lower internal frame rate.
     */
    private val DW4_XL_SPEED = Quirk(
        options = mapOf(
            "armsx2_ee_cycle_rate" to "75%",
            "armsx2_ee_cycle_skip" to "mild",
            "armsx2_blending_accuracy" to "Minimum",
        ),
        note = "무거운 게임이라 속도 우선 설정(EE 사이클 75%·사이클 스킵 약하게·블렌딩 최소)으로 시작합니다. " +
            "화면이 깨지거나 게임 속 속도가 느려지면 여기서 원래 값으로 되돌릴 수 있습니다.",
    )

    /** Title/file-name word sets for that game; each set alone identifies it (all words in a set must match). */
    private val DW4_XL_SERIES = listOf(
        listOf(
            Regex("""(진|真)\s*[・·.\-]?\s*(삼국|三國|三国)\s*(무쌍|無双)\s*4(?!\d)|(shin|jin)[ _.-]*(sangoku|samguk)[ _.-]*(musou|mussang)[ _.-]*4(?!\d)|dynasty[ _.-]*warriors[ _.-]*4(?!\d)"""),
            Regex("""맹장전|猛将伝|moushouden|maengjangjeon|xtreme[ _.-]*legends"""),
        ),
        // A dump named after its disc serial: the Korean release and the Japanese/Asian ones, the entries that
        // carry the auto-flush fix in GameIndex.yaml.
        listOf(Regex("""(?<![a-z])(slka[ _.-]?25329|slpm[ _.-]?66101|slpm[ _.-]?74250|slaj[ _.-]?25068)(?!\d)""")),
    )

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
    ) + DW4_XL_SERIES.map { words -> Rule(coreId = "armsx2", system = SystemId.PS2, words = words, quirk = DW4_XL_SPEED) }
}
