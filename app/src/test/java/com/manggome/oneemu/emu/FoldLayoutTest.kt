package com.manggome.oneemu.emu

import androidx.compose.ui.geometry.Size
import com.manggome.oneemu.emu.pad.DefaultLayouts
import com.manggome.oneemu.emu.pad.PadElementId
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldLayoutTest {
    private val unfolded = Size(2208f, 1768f) // a Fold opened and held sideways

    @Test fun dsScreensSitAcrossTheTopWhenUnfoldedSideways() {
        FoldLayout.enabled = true
        val vp = ViewportStore.default(SystemId.NDS, ScreenConfig.LANDSCAPE_WIDE, unfolded)
        assertEquals(0f, vp.y, 0.001f)
        assertEquals(1f, vp.w, 0.001f)
        assertEquals(2208f / (512f / 192f) / 1768f, vp.h, 0.001f)
        // The pad goes below them: the d-pad is in the lower half.
        val dpad = DefaultLayouts.forProfile(PadProfile(SystemId.NDS), ScreenConfig.LANDSCAPE_WIDE)[PadElementId.DPAD]!!
        assert(dpad.y > 0.5f)
    }

    @Test fun nothingChangesFoldedOrForOtherSystemsOrWhenOff() {
        FoldLayout.enabled = true
        assertEquals(ViewportRect.FULL, ViewportStore.default(SystemId.NDS, ScreenConfig.LANDSCAPE, Size(2400f, 1080f)))
        assertEquals(ViewportRect.FULL, ViewportStore.default(SystemId.GBA, ScreenConfig.LANDSCAPE_WIDE, unfolded))
        FoldLayout.enabled = false
        assertEquals(ViewportRect.FULL, ViewportStore.default(SystemId.NDS, ScreenConfig.LANDSCAPE_WIDE, unfolded))
        FoldLayout.enabled = true
    }

    @Test fun eachCoreGetsItsOwnOption() {
        assertEquals("melonds_screen_layout1" to "left-right", FoldLayout.option("melondsds", true))
        assertEquals("citra_layout_option" to "default", FoldLayout.option("azaharplus", false))
        assertEquals(null, FoldLayout.option("mgba", true))
    }
}
