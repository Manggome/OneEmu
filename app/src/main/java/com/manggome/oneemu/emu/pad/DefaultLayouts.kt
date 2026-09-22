package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.emu.pad.PadElementId.*
import com.manggome.oneemu.model.SystemId

/**
 * Factory layouts. Portrait puts the game image on top and the pad in the lower ~45% of the
 * screen; landscape puts the d-pad left and the buttons right. Positions are normalized so they
 * work on any resolution; users rearrange them in the layout editor. The wide configurations
 * (unfolded foldable / tablet) reuse the phone layout of the same orientation with smaller buttons,
 * since dp-sized controls otherwise look giant on a near-square 7–8" panel.
 */
object DefaultLayouts {
    /** Element scale applied on top of the phone default for the *_WIDE configurations. */
    const val WIDE_SCALE = 0.85f

    /** Arrangements the layout editor can drop onto any system. */
    enum class Preset { DEFAULT, ARCADE }

    fun forSystem(system: SystemId, config: ScreenConfig): PadLayout = forProfile(PadProfile(system), config, Preset.DEFAULT)

    fun forSystem(system: SystemId, config: ScreenConfig, preset: Preset): PadLayout = forProfile(PadProfile(system), config, preset)

    fun forProfile(profile: PadProfile, config: ScreenConfig): PadLayout = forProfile(profile, config, Preset.DEFAULT)

    fun forProfile(profile: PadProfile, config: ScreenConfig, preset: Preset): PadLayout {
        val base = when (preset) {
            Preset.DEFAULT -> forProfile(profile, config.landscape)
            Preset.ARCADE -> arcadeStyle(profile.system, config.landscape)
        }
        return if (config.wide) PadLayout(base.elements.map { it.copy(scale = it.scale * WIDE_SCALE) }) else base
    }

    /**
     * The cabinet arrangement (stick left, two arched rows of round buttons right) applied to any system,
     * using that system's own buttons rather than the arcade 1-6 wiring: a GameCube fighting game is much
     * easier to play on six flat buttons than on the ABXY cluster it ships with.
     */
    fun arcadeStyle(system: SystemId, landscape: Boolean): PadLayout {
        if (system == SystemId.ARCADE) return arcade(landscape)
        // Only the buttons this system actually has, face buttons first and then the shoulders, up to the six
        // a cabinet has: a Game Boy game gets B/A/L/R, a GameCube one the full six.
        val own = forSystem(system, landscape).elements.map { it.id }.toMutableSet()
        if (ABXY_CLUSTER in own) own += listOf(BUTTON_B, BUTTON_A, BUTTON_Y, BUTTON_X)
        val keys = listOf(BUTTON_B, BUTTON_A, BUTTON_Y, BUTTON_X, L, R, L2, R2).filter { it in own }.take(6)
        val hasStick = system.hasAnalog
        val list = mutableListOf<PadElement>()
        if (landscape) {
            list += e(if (hasStick) LEFT_STICK else DPAD, 0.13f, 0.60f)
            val spots = listOf(0.72f to 0.72f, 0.82f to 0.68f, 0.92f to 0.64f, 0.72f to 0.48f, 0.82f to 0.44f, 0.92f to 0.40f)
            keys.forEachIndexed { i, id -> list += e(id, spots[i].first, spots[i].second) }
            list += e(SELECT, 0.42f, 0.92f)
            list += e(START, 0.58f, 0.92f)
            list += e(MENU, 0.04f, 0.08f)
            list += e(FAST_FORWARD, 0.96f, 0.08f)
        } else {
            list += e(if (hasStick) LEFT_STICK else DPAD, 0.20f, 0.76f)
            val spots = listOf(0.60f to 0.82f, 0.76f to 0.79f, 0.91f to 0.76f, 0.60f to 0.68f, 0.76f to 0.65f, 0.91f to 0.62f)
            keys.forEachIndexed { i, id -> list += e(id, spots[i].first, spots[i].second) }
            list += e(SELECT, 0.40f, 0.94f)
            list += e(START, 0.60f, 0.94f)
            list += e(MENU, 0.44f, 0.58f)
            list += e(FAST_FORWARD, 0.56f, 0.58f)
        }
        // Carry the system's remaining controls hidden rather than dropping them: PadLayoutStore.resolve()
        // adds back any default element a saved layout does not mention, which would put the d-pad, the
        // second stick and the triggers straight back on top of the cabinet buttons.
        val placed = list.map { it.id }.toSet()
        list += forSystem(system, landscape).elements
            .filter { it.id !in placed }
            .map { it.copy(visible = false) }
        return PadLayout(list)
    }

    /** Phone / folded default for one orientation. */
    fun forProfile(profile: PadProfile, landscape: Boolean): PadLayout =
        if (profile.isWiimote) wiimote(landscape) else forSystem(profile.system, landscape)

    /** Phone / folded default for one orientation. */
    fun forSystem(system: SystemId, landscape: Boolean): PadLayout = when (system) {
        SystemId.NES, SystemId.GB, SystemId.GBC -> twoButton(landscape, shoulders = false)
        SystemId.GBA -> twoButton(landscape, shoulders = true)
        // melonDS DS reads L3 as the microphone and R3 as "next screen layout", so a DS pad without
        // them cannot blow into the mic or move the touch screen where the player wants it.
        SystemId.NDS -> withNdsExtras(
            fourButton(landscape, shoulders = true, leftStick = false, rightStick = false, triggers = false, lowPortrait = true),
            landscape,
        )
        SystemId.N3DS -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = false, triggers = false, lowPortrait = true)
        SystemId.PSX -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = true, triggers = true, lowPortrait = false)
        SystemId.PSP -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = false, triggers = false, lowPortrait = false)
        SystemId.PS2 -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = true, triggers = true, lowPortrait = false)
        SystemId.GC -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = true, triggers = true, lowPortrait = false)
        // Mega Drive's six-button pad: the core wires Y/B/A to A/B/C and L/X/R to X/Y/Z, so the four face
        // buttons plus the shoulders are exactly it. Master System and Game Gear use two of them.
        SystemId.MD -> fourButton(landscape, shoulders = true, leftStick = false, rightStick = false, triggers = false, lowPortrait = false)
        SystemId.SMS, SystemId.GG -> twoButton(landscape, shoulders = false)
        SystemId.ARCADE -> arcade(landscape)
        // Jazz Jackrabbit 2: run/jump/shoot on the face buttons, weapon switching on the shoulders.
        SystemId.JAZZ2 -> fourButton(landscape, shoulders = true, leftStick = false, rightStick = false, triggers = false, lowPortrait = false)
    }

    private fun e(id: PadElementId, x: Float, y: Float, scale: Float = 1f) = PadElement(id, x, y, scale)

    /**
     * Wii Remote + Nunchuk, the device Dolphin needs for a Wii disc. The ergonomics are a four-button pad's
     * - a d-pad and a stick on the left, two big buttons and two small ones on the right - because that is
     * what a phone screen can do; only the legend changes (X is C, Y is Z, L/R are minus/plus, START is 1,
     * SELECT is 2, and the triggers shake the two halves). HOME is the one button a GameCube pad has no
     * room for, so it is added here.
     */
    private fun wiimote(landscape: Boolean): PadLayout {
        val base = fourButton(landscape, shoulders = true, leftStick = true, rightStick = false, triggers = true, lowPortrait = false)
        if (landscape) return PadLayout(base.elements + e(R3, 0.50f, 0.80f, 0.85f))
        // Portrait: a four-button pad drops SELECT/START to the very bottom edge to clear the stick, which on a
        // phone puts them under the navigation bar. 1, 2 and HOME are menu buttons a Wii game needs, so they sit
        // in a row of their own beside the stick instead.
        return PadLayout(
            base.elements.map {
                when (it.id) {
                    SELECT -> it.copy(x = 0.45f, y = 0.93f)
                    START -> it.copy(x = 0.62f, y = 0.93f)
                    else -> it
                }
            } + e(R3, 0.80f, 0.93f, 0.85f),
        )
    }

    /** The two stick clicks melonDS DS uses, next to the shoulders they sit beside on a real pad. */
    private fun withNdsExtras(base: PadLayout, landscape: Boolean): PadLayout = PadLayout(
        base.elements + if (landscape) {
            listOf(e(L3, 0.19f, 0.12f, 0.85f), e(R3, 0.81f, 0.12f, 0.85f))
        } else {
            listOf(e(L3, 0.28f, 0.66f, 0.85f), e(R3, 0.72f, 0.66f, 0.85f))
        },
    )

    private fun twoButton(landscape: Boolean, shoulders: Boolean): PadLayout {
        val list = mutableListOf<PadElement>()
        if (landscape) {
            list += e(DPAD, 0.13f, 0.62f)
            list += e(BUTTON_B, 0.80f, 0.70f)
            list += e(BUTTON_A, 0.91f, 0.54f)
            list += e(SELECT, 0.42f, 0.92f)
            list += e(START, 0.58f, 0.92f)
            list += e(MENU, 0.04f, 0.08f)
            list += e(FAST_FORWARD, 0.96f, 0.08f)
            if (shoulders) { list += e(L, 0.08f, 0.10f); list += e(R, 0.92f, 0.10f) }
        } else {
            list += e(DPAD, 0.20f, 0.74f)
            list += e(BUTTON_B, 0.72f, 0.79f)
            list += e(BUTTON_A, 0.87f, 0.70f)
            list += e(SELECT, 0.40f, 0.93f)
            list += e(START, 0.60f, 0.93f)
            list += e(MENU, 0.44f, 0.60f)
            list += e(FAST_FORWARD, 0.56f, 0.60f)
            if (shoulders) { list += e(L, 0.12f, 0.57f); list += e(R, 0.88f, 0.57f) }
        }
        return PadLayout(list)
    }

    private fun fourButton(
        landscape: Boolean,
        shoulders: Boolean,
        leftStick: Boolean,
        rightStick: Boolean,
        triggers: Boolean,
        lowPortrait: Boolean,
    ): PadLayout {
        val list = mutableListOf<PadElement>()
        if (landscape) {
            list += e(DPAD, 0.13f, 0.58f)
            // Diamond on the right: X top, Y left, A right, B bottom.
            val cx = 0.85f; val cy = 0.58f; val dx = 0.065f; val dy = 0.17f
            list += e(BUTTON_X, cx, cy - dy)
            list += e(BUTTON_Y, cx - dx, cy)
            list += e(BUTTON_A, cx + dx, cy)
            list += e(BUTTON_B, cx, cy + dy)
            list += e(SELECT, 0.42f, 0.92f)
            list += e(START, 0.58f, 0.92f)
            list += e(MENU, 0.04f, 0.08f)
            list += e(FAST_FORWARD, 0.96f, 0.08f)
            if (shoulders) { list += e(L, 0.08f, 0.12f); list += e(R, 0.92f, 0.12f) }
            if (triggers) { list += e(L2, 0.19f, 0.12f); list += e(R2, 0.81f, 0.12f) }
            if (leftStick) list += e(LEFT_STICK, 0.30f, 0.80f, 0.9f)
            if (rightStick) list += e(RIGHT_STICK, 0.70f, 0.80f, 0.9f)
        } else {
            // NDS/3DS render two stacked screens, so the pad sits lower and slightly smaller.
            val base = if (lowPortrait) 0.80f else 0.72f
            val s = if (lowPortrait) 0.9f else 1f
            list += e(DPAD, 0.19f, base, s)
            val cx = 0.80f; val cy = base; val dx = 0.11f; val dy = 0.075f
            list += e(BUTTON_X, cx, cy - dy, s)
            list += e(BUTTON_Y, cx - dx, cy, s)
            list += e(BUTTON_A, cx + dx, cy, s)
            list += e(BUTTON_B, cx, cy + dy, s)
            list += e(SELECT, 0.40f, 0.94f)
            list += e(START, 0.60f, 0.94f)
            val topRow = if (lowPortrait) 0.66f else 0.57f
            list += e(MENU, 0.44f, topRow + 0.02f)
            list += e(FAST_FORWARD, 0.56f, topRow + 0.02f)
            if (shoulders) { list += e(L, 0.12f, topRow); list += e(R, 0.88f, topRow) }
            if (triggers) { list += e(L2, 0.12f, topRow - 0.05f); list += e(R2, 0.88f, topRow - 0.05f) }
            if (leftStick) list += e(LEFT_STICK, 0.20f, if (lowPortrait) 0.93f else 0.88f, if (lowPortrait) 0.7f else 0.8f)
            if (rightStick) list += e(RIGHT_STICK, 0.80f, 0.88f, 0.8f)
            // Keep SELECT/START clear of the sticks on stick systems.
            if (leftStick && !lowPortrait) {
                list.replaceAll { if (it.id == SELECT) it.copy(x = 0.42f, y = 0.98f) else if (it.id == START) it.copy(x = 0.58f, y = 0.98f) else it }
            }
        }
        return PadLayout(list)
    }

    private fun arcade(landscape: Boolean): PadLayout {
        val list = mutableListOf<PadElement>()
        if (landscape) {
            list += e(DPAD, 0.13f, 0.60f)
            // Two rows of three buttons, slightly arched like a cabinet.
            list += e(ARCADE_1, 0.72f, 0.72f); list += e(ARCADE_2, 0.82f, 0.68f); list += e(ARCADE_3, 0.92f, 0.64f)
            list += e(ARCADE_4, 0.72f, 0.48f); list += e(ARCADE_5, 0.82f, 0.44f); list += e(ARCADE_6, 0.92f, 0.40f)
            list += e(COIN, 0.42f, 0.92f)
            list += e(START, 0.58f, 0.92f)
            list += e(MENU, 0.04f, 0.08f)
            list += e(FAST_FORWARD, 0.96f, 0.08f)
        } else {
            list += e(DPAD, 0.20f, 0.76f)
            list += e(ARCADE_1, 0.60f, 0.82f); list += e(ARCADE_2, 0.76f, 0.79f); list += e(ARCADE_3, 0.91f, 0.76f)
            list += e(ARCADE_4, 0.60f, 0.68f); list += e(ARCADE_5, 0.76f, 0.65f); list += e(ARCADE_6, 0.91f, 0.62f)
            list += e(COIN, 0.40f, 0.94f)
            list += e(START, 0.60f, 0.94f)
            list += e(MENU, 0.44f, 0.58f)
            list += e(FAST_FORWARD, 0.56f, 0.58f)
        }
        return PadLayout(list)
    }
}
