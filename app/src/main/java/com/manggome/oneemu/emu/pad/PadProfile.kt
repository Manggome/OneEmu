package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.model.SystemId

/**
 * Which controller the on-screen pad draws.
 *
 * Almost every system has exactly one, so the profile is just its [SystemId]. Dolphin is the exception:
 * the same core, the same file extensions and the same saves folder cover two different machines, and a
 * Wii disc is played with a Wii Remote whose buttons mean something else entirely (X is the nunchuk's C,
 * START is "1", R3 is HOME). The profile picks the button legend, the factory arrangement and the key the
 * layout is saved under, so the GameCube pad and the Wii Remote never overwrite each other.
 */
data class PadProfile(
    val system: SystemId,
    val variant: Variant = Variant.STANDARD,
    /**
     * Set when one game keeps a layout, screen position and skin of its own (이 게임만 따로 배치). Its
     * settings are saved under a key of their own, and anything it has not saved falls back to [base].
     */
    val gameId: Long? = null,
) {
    /** [suffix] is appended to [SystemId.id] to key this profile's saved layout. */
    enum class Variant(val suffix: String) { STANDARD(""), WIIMOTE("-wii") }

    /** Identifier for the saved layout and for the layout editor's route; plain [SystemId.id] by default. */
    val key: String get() = system.id + variant.suffix + (gameId?.let { "$GAME_MARK$it" } ?: "")

    /** The pad without the per-game part: what a game with nothing of its own uses. */
    val base: PadProfile get() = if (gameId == null) this else copy(gameId = null)

    val displayName: String get() = when (variant) {
        Variant.STANDARD -> system.displayName
        Variant.WIIMOTE -> "Wii (리모컨 + 눈차크)"
    }

    val shortName: String get() = if (variant == Variant.WIIMOTE) "Wii" else system.shortName

    val isWiimote: Boolean get() = variant == Variant.WIIMOTE

    companion object {
        /** The Wii Remote + Nunchuk pad Dolphin offers for Wii discs. */
        val WIIMOTE = PadProfile(SystemId.GC, Variant.WIIMOTE)

        /** Separates the pad from the game in [key]; "@" is safe in a navigation route and a settings key. */
        const val GAME_MARK = "@g"

        fun fromKey(key: String?): PadProfile? {
            if (key.isNullOrEmpty()) return null
            if (GAME_MARK in key) {
                val game = key.substringAfterLast(GAME_MARK).toLongOrNull() ?: return null
                return fromKey(key.substringBeforeLast(GAME_MARK))?.copy(gameId = game)
            }
            Variant.entries.filter { it.suffix.isNotEmpty() }.forEach { v ->
                if (key.endsWith(v.suffix)) {
                    SystemId.fromId(key.removeSuffix(v.suffix))?.let { return PadProfile(it, v) }
                }
            }
            return SystemId.fromId(key)?.let { PadProfile(it) }
        }

        /** Every profile the layout editor lists, in library order, with the Wii pad right after GameCube. */
        val ordered: List<PadProfile>
            get() = SystemId.ordered.flatMap { s ->
                if (s == SystemId.GC) listOf(PadProfile(s), WIIMOTE) else listOf(PadProfile(s))
            }
    }
}
