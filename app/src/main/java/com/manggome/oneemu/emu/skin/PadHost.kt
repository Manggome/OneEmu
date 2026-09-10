package com.manggome.oneemu.emu.skin

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.manggome.oneemu.emu.Haptics
import com.manggome.oneemu.emu.pad.PadInput
import com.manggome.oneemu.emu.pad.PadLayout
import com.manggome.oneemu.emu.pad.VirtualPad
import com.manggome.oneemu.model.SystemId

/**
 * Drop-in replacement for the `VirtualPad(...)` call in EmulatorScreen: observes the skin selected for
 * [system] and renders either the image [SkinPad] or the vector [VirtualPad] with the same callbacks.
 * An empty [layout] (the "gamepad connected" case) hides both.
 */
@Composable
fun PadHost(
    layout: PadLayout,
    system: SystemId,
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val selection by produceState<SkinSelection>(SkinSelection.Loading, system) {
        SkinStore.observeSelectedSkin(context, system).collect { value = it }
    }
    val hidden = layout.elements.isEmpty()

    when (val sel = selection) {
        SkinSelection.Loading -> {}
        SkinSelection.Vector -> VirtualPad(
            layout, system, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive,
            onInput, onPointer, onMenu, onFastForward, modifier,
        )
        is SkinSelection.Skin -> {
            val loaded by produceState<Result<LoadedSkin>?>(null, sel.info.id) {
                value = runCatching { SkinLoader.load(context, sel.info) }
            }
            val skinLayout by produceState(SkinLayout.EMPTY, sel.info.id, landscape) {
                SkinStore.observeLayout(sel.info.id, system.id, landscape).collect { value = it }
            }
            val result = loaded ?: return
            val skin = result.getOrNull()
            if (skin == null || skin.cfg(landscape).isEmpty) {
                // Broken import: fall back to the vector pad rather than leaving the user without controls.
                VirtualPad(layout, system, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive, onInput, onPointer, onMenu, onFastForward, modifier)
            } else if (!hidden) {
                SkinPad(
                    skin = skin, layout = skinLayout, system = system, landscape = landscape,
                    opacity = opacity, globalScale = globalScale, hapticMs = hapticMs, haptics = haptics,
                    gameRect = gameRect, fastForwardActive = fastForwardActive,
                    onInput = onInput, onPointer = onPointer, onMenu = onMenu, onFastForward = onFastForward,
                    modifier = modifier,
                )
            } else if (gameRect != null) {
                // Pad hidden (physical gamepad) but the touch screen must still work.
                VirtualPad(layout, system, opacity, globalScale, hapticMs, haptics, gameRect, fastForwardActive, onInput, onPointer, onMenu, onFastForward, modifier)
            }
        }
    }
}
