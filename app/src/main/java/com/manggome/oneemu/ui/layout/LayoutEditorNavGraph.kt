package com.manggome.oneemu.ui.layout

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes
import com.manggome.oneemu.ui.theme.OneEmuColors

/** Registers Routes.LAYOUT_EDITOR ("layout/{profile}") in the main NavHost. */
fun NavGraphBuilder.layoutEditorGraph(nav: NavHostController) {
    composable(
        Routes.LAYOUT_EDITOR,
        arguments = listOf(navArgument("profile") { type = NavType.StringType }),
    ) { entry ->
        val profile = PadProfile.fromKey(entry.arguments?.getString("profile")) ?: PadProfile(SystemId.GBA)
        LayoutEditorRoute(profile, onClose = { nav.popBackStack() })
    }
}

/**
 * Standalone editor screen: dark background with the viewport as the mock game rectangle and a
 * 접은 세로/접은 가로/펼친 세로/펼친 가로 selector. Orientation is forced while editing so the preview matches
 * what the user will see; the wide/narrow half of a configuration cannot be forced (it is the physical
 * panel), so editing e.g. 펼친 세로 on a folded phone shows a note that the shape differs.
 */
@Composable
fun LayoutEditorRoute(profile: PadProfile, onClose: () -> Unit) {
    val system = profile.system
    // 설정 offers a one-tap way back to whichever pad was edited last.
    LaunchedEffect(profile) {
        OneEmuApp.get().settings.set(Settings.Keys.lastPadProfile, profile.key)
    }
    val context = LocalContext.current
    val activity = context as? Activity
    val startConfig = ScreenConfig.from(LocalConfiguration.current)
    var configKey by rememberSaveable { mutableStateOf(startConfig.key) }
    val config = ScreenConfig.fromKey(configKey) ?: startConfig
    val previous = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    DisposableEffect(config.landscape) {
        activity?.requestedOrientation =
            if (config.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = previous }
    }

    /*
     * The game runs with the system bars hidden, so the pad has the whole screen. This editor is a
     * normal screen in the main activity, where the navigation bar sits on top of the bottom of that
     * same pad: a START or SELECT placed low is drawn under the bar and cannot be dragged out of it
     * again - the bar takes the touch. Hiding the bars here makes the editor the shape the pad will
     * actually have, which is what it is for.
     */
    val window = activity?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        bars?.apply {
            // A swipe brings them back for anyone who needs them without leaving the editor.
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Only render the editor once the window actually has the requested orientation, so the
    // normalized positions are laid out against the right screen shape.
    val actual = LocalConfiguration.current
    val actualLandscape = actual.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (actualLandscape != config.landscape) return
    val wideMismatch = ScreenConfig.isWide(actual) != config.wide

    LayoutEditor(
        system = system,
        profile = profile,
        config = config,
        showMockGame = true,
        onClose = onClose,
        configSelector = {
            Column {
                ScreenConfigSelector(config, onSelect = { configKey = it.key })
                if (wideMismatch) {
                    Text(
                        stringResource(if (config.wide) R.string.sc_edit_wide_on_narrow else R.string.sc_edit_narrow_on_wide),
                        style = MaterialTheme.typography.labelSmall, color = OneEmuColors.OnSurfaceMuted, maxLines = 1,
                    )
                }
            }
        },
    )
}
