package com.manggome.oneemu.emu

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.manggome.oneemu.R

/**
 * The four screen shapes a layout can be tuned for. Foldables (Galaxy Z Fold) and tablets report
 * `smallestScreenWidthDp >= 600` when unfolded / large, which makes the *_WIDE variants; ordinary phones
 * and a folded Fold only ever use [PORTRAIT] / [LANDSCAPE]. [key] is the suffix used in every per-config
 * preference (`layout.<system>.<key>`, `skin_layout.<skin>.<system>.<key>`, `viewport.<system>.<key>`);
 * the non-wide keys are the pre-existing `port`/`land` values so old saves stay valid.
 */
enum class ScreenConfig(val key: String, val landscape: Boolean, val wide: Boolean, @StringRes val labelRes: Int) {
    PORTRAIT("port", landscape = false, wide = false, R.string.sc_portrait),
    LANDSCAPE("land", landscape = true, wide = false, R.string.sc_landscape),
    PORTRAIT_WIDE("port_wide", landscape = false, wide = true, R.string.sc_portrait_wide),
    LANDSCAPE_WIDE("land_wide", landscape = true, wide = true, R.string.sc_landscape_wide);

    /** The non-wide config with the same orientation (defaults for the wide ones derive from it). */
    val narrow: ScreenConfig get() = if (landscape) LANDSCAPE else PORTRAIT

    fun withLandscape(landscape: Boolean): ScreenConfig = of(landscape, wide)

    companion object {
        /** `smallestScreenWidthDp` at or above this counts as an unfolded foldable / tablet. */
        const val WIDE_MIN_SW_DP = 600

        fun isWide(configuration: Configuration): Boolean = configuration.smallestScreenWidthDp >= WIDE_MIN_SW_DP

        fun from(configuration: Configuration): ScreenConfig =
            of(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, isWide(configuration))

        fun of(landscape: Boolean, wide: Boolean): ScreenConfig = when {
            landscape && wide -> LANDSCAPE_WIDE
            landscape -> LANDSCAPE
            wide -> PORTRAIT_WIDE
            else -> PORTRAIT
        }

        fun fromKey(key: String?): ScreenConfig? = entries.firstOrNull { it.key == key }
    }
}

/** The configuration the window is currently in; recomposes on rotation and fold/unfold. */
@Composable
fun rememberScreenConfig(): ScreenConfig {
    val configuration = LocalConfiguration.current
    return remember(configuration.orientation, configuration.smallestScreenWidthDp) { ScreenConfig.from(configuration) }
}
