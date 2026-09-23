package com.manggome.oneemu.emu

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.model.SystemId

/**
 * A foldable opened and held sideways is wide enough for a DS or 3DS to show its two screens next to
 * each other instead of stacked. Then the pair sits across the top of the panel and the pad gets the
 * whole lower half, rather than the stacked screens filling the middle with the buttons squeezed onto
 * their edges. Folded, or held upright, nothing changes.
 */
object FoldLayout {
    /** On by default; off keeps the stacked screens everywhere. */
    val SIDE_BY_SIDE = booleanPreferencesKey("fold_dual_side_by_side")

    fun isDualScreen(system: SystemId): Boolean = system == SystemId.NDS || system == SystemId.N3DS

    /**
     * The last value of [SIDE_BY_SIDE] seen, for the default layout and picture position, which are worked
     * out without a coroutine. Kept current by the app from the preference.
     */
    @Volatile var enabled: Boolean = true

    /** Where the layout applies: switched on, an unfolded panel, landscape, a two-screen system. */
    fun applies(system: SystemId, config: ScreenConfig): Boolean = enabled && config.wide && config.landscape && isDualScreen(system)

    /** Width / height of the two screens side by side: 2 x 256x192 for a DS, 400x240 + 320x240 for a 3DS. */
    fun sideBySideAspect(system: SystemId): Float = if (system == SystemId.N3DS) 720f / 240f else 512f / 192f

    /** The core option that arranges the screens, and its value for side by side or stacked. */
    fun option(coreId: String, sideBySide: Boolean): Pair<String, String>? = when (coreId) {
        "melondsds" -> "melonds_screen_layout1" to if (sideBySide) "left-right" else "top-bottom"
        "azaharplus" -> "citra_layout_option" to if (sideBySide) "side_by_side" else "default"
        else -> null
    }
}
