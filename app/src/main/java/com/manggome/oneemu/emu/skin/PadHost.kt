package com.manggome.oneemu.emu.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.rememberScreenConfig
import com.manggome.oneemu.emu.pad.PadInput
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.VirtualPad
import com.manggome.oneemu.emu.pad.PadProfile

/**
 * Drop-in replacement for the `VirtualPad(...)` call in EmulatorScreen: observes the skin selected for
 * [profile]'s system and renders either the image [SkinPad] or the vector [VirtualPad] with the same
 * callbacks. An empty [layout] (the "gamepad connected" case) hides both.
 */
@Composable
fun PadHost(
    layout: PadLayout,
    profile: PadProfile,
    opacity: Float,
    globalScale: Float,
    hapticMs: Int,
    haptics: Haptics?,
    gameRect: Rect?,
    fastForwardActive: Boolean,
    onInput: (PadInput) -> Unit,
    onPointer: (x: Float, y: Float, pressed: Boolean) -> Unit,
    onMenu: () -> Unit,
    onFastForward: (Boolean) -> Unit,
    speedLabel: String = "1×",
    onSpeedCycle: () -> Unit = {},
    turboActive: Boolean = false,
    onTurbo: () -> Unit = {},
    onSaveState: () -> Unit = {},
    onLoadState: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = rememberScreenConfig()
    val landscape = config.landscape
    val system = profile.system
    val selection by produceState<SkinSelection>(SkinSelection.Loading, profile) {
        SkinStore.observeSelectedSkin(context, profile).collect { value = it }
    }
    val hidden = layout.elements.isEmpty()

    when (val sel = selection) {
        SkinSelection.Loading -> {}
        SkinSelection.Vector -> VirtualPad(
            layout, profile, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive,
            onInput, onPointer, onMenu, onFastForward, speedLabel, onSpeedCycle, modifier,
            turboActive, onTurbo, onSaveState, onLoadState,
        )
        is SkinSelection.Skin -> {
            val loaded by produceState<Result<LoadedSkin>?>(null, sel.info.id) {
                value = runCatching { SkinLoader.load(context, sel.info) }
            }
            val skinLayout by produceState(SkinLayout.EMPTY, sel.info.id, config) {
                SkinStore.observeLayout(sel.info.id, profile.key, config).collect { value = it }
            }
            val result = loaded ?: return
            val skin = result.getOrNull()
            if (skin == null || skin.cfg(landscape).isEmpty) {
                // Broken import: fall back to the vector pad rather than leaving the user without controls.
                VirtualPad(layout, profile, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive, onInput, onPointer, onMenu, onFastForward, speedLabel, onSpeedCycle, modifier, turboActive, onTurbo, onSaveState, onLoadState)
            } else if (!hidden) {
                SkinPad(
                    skin = skin, layout = skinLayout, system = system, landscape = landscape,
                    opacity = opacity, globalScale = globalScale, hapticMs = hapticMs, haptics = haptics,
                    gameRect = gameRect, fastForwardActive = fastForwardActive,
                    onInput = onInput, onPointer = onPointer, onMenu = onMenu, onFastForward = onFastForward,
                    modifier = modifier,
                )
                // After SkinPad so it sits on top: the skin's pointer handler consumes every touch below it.
                ExtraOverlay(layout, profile, opacity, globalScale, hapticMs, haptics, speedLabel, onSpeedCycle, turboActive, onTurbo, onSaveState, onLoadState, modifier)
            } else if (gameRect != null) {
                // Pad hidden (physical gamepad) but the touch screen must still work.
                VirtualPad(layout, profile, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive, onInput, onPointer, onMenu, onFastForward, speedLabel, onSpeedCycle, modifier, turboActive, onTurbo, onSaveState, onLoadState)
            }
        }
    }
}

/**
 * The 배속, 연사, 저장 and 불러오기 buttons on top of an image skin. RetroArch overlays have no such controls, so they
 * are taken from the vector layout (the skin editor's chips add them there) and drawn as a pad of
 * their own.
 */
@Composable
private fun ExtraOverlay(
    layout: PadLayout,
    profile: PadProfile,
    opacity: Float,
    globalScale: Float,
    hapticMs: Int,
    haptics: Haptics?,
    speedLabel: String,
    onSpeedCycle: () -> Unit,
    turboActive: Boolean,
    onTurbo: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    modifier: Modifier,
) {
    val extras = listOf(PadElementId.SPEED, PadElementId.TURBO, PadElementId.SAVE_STATE, PadElementId.LOAD_STATE)
        .mapNotNull { layout[it]?.takeIf { e -> e.visible } }
    if (extras.isEmpty()) return
    VirtualPad(
        layout = PadLayout(extras),
        profile = profile,
        opacity = opacity,
        globalScale = globalScale,
        hapticMs = hapticMs,
        haptics = haptics,
        gameRect = null,
        fastForwardActive = false,
        onInput = {},
        onPointer = { _, _, _ -> },
        onMenu = {},
        onFastForward = {},
        speedLabel = speedLabel,
        onSpeedCycle = onSpeedCycle,
        modifier = modifier,
        turboActive = turboActive,
        onTurbo = onTurbo,
        onSaveState = onSaveState,
        onLoadState = onLoadState,
    )
}
