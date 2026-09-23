package com.manggome.oneemu.emu.pad

/**
 * What the pad's app buttons do - 배속, 연사, 저장/불러오기, 빠른 저장/불러오기, 되감기 - and the state they show.
 * One object instead of a parameter each, passed from the emulator screen through PadHost to whichever
 * pad is drawn (vector, image skin, or the extras over a skin).
 */
data class PadActions(
    /** Text drawn on the 배속 button, e.g. "3×"; a tap asks for the next step. */
    val speedLabel: String = "1×",
    val onSpeedCycle: () -> Unit = {},
    /** Autofire is on, which keeps the 연사 button lit. */
    val turboActive: Boolean = false,
    val onTurbo: () -> Unit = {},
    /** 저장 / 불러오기 open the slot list, as the pause menu does. */
    val onSaveState: () -> Unit = {},
    val onLoadState: () -> Unit = {},
    /** 빠른 저장 / 빠른 불러오기 use one slot of their own, no list. */
    val onQuickSave: () -> Unit = {},
    val onQuickLoad: () -> Unit = {},
    /** 되감기 is held: true on press, false on release. */
    val onRewind: (Boolean) -> Unit = {},
    /** Lit while rewinding. */
    val rewinding: Boolean = false,
)
