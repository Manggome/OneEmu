package com.manggome.oneemu.emu.pad

import com.manggome.oneemu.emu.pad.PadElementId.*
import com.manggome.oneemu.model.SystemId

/**
 * Factory layouts. Portrait puts the game image on top and the pad in the lower ~45% of the
 * screen; landscape puts the d-pad left and the buttons right. Positions are normalized so they
 * work on any resolution; users rearrange them in the layout editor.
 */
object DefaultLayouts {
    fun forSystem(system: SystemId, landscape: Boolean): PadLayout = when (system) {
        SystemId.NES, SystemId.GB, SystemId.GBC -> twoButton(landscape, shoulders = false)
        SystemId.GBA -> twoButton(landscape, shoulders = true)
        SystemId.NDS -> fourButton(landscape, shoulders = true, leftStick = false, rightStick = false, triggers = false, lowPortrait = true)
        SystemId.N3DS -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = false, triggers = false, lowPortrait = true)
        SystemId.PSP -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = false, triggers = false, lowPortrait = false)
        SystemId.PS2 -> fourButton(landscape, shoulders = true, leftStick = true, rightStick = true, triggers = true, lowPortrait = false)
        SystemId.ARCADE -> arcade(landscape)
    }

    private fun e(id: PadElementId, x: Float, y: Float, scale: Float = 1f) = PadElement(id, x, y, scale)

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
